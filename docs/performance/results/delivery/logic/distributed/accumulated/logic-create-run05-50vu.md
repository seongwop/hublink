# 배송 생성 로직 Run 05 50VU 결과

### 1. 테스트 목적

분산된 `RECEIVER_COMPANY_IDS` 조건을 유지한 채 50VU 스트레스 구간에서 현재 배송 생성 로직의 ceiling과 붕괴 양상을 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 05 50VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,...,20000000-0000-0000-0000-000000000027' STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 2750 |
| HTTP TPS | 5.72 req/s |
| 실패 요청 수 | 1 |
| 실패율 | 0.03% |
| checks 성공률 | 99.96% |
| 평균 응답 시간 | 6.15s |
| p95 응답 시간 | 8.56s |
| p99 응답 시간 | 9.00s |
| 최대 응답 시간 | 14.04s |
| max VU | 50 |

Threshold 결과는 실패다.

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✗ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. 처리 결과

| 항목 | 값 |
| --- | --- |
| 백엔드 성공 처리 수 | 2749 |
| 생성 delivery 수 | 2749 |
| 생성 route history 수 | 5498 |
| `delivery.create.succeed` outbox 수 | 2749 |
| `delivery.create.failed` outbox 수 | 0 |
| `delivery.create.dlq` outbox 수 | 0 |
| Kafka lag / 회복 시간 | N/A |

HTTP 성공 2749건과 DB delivery 생성 2749건, success outbox 2749건은 일치한다.
실패 1건은 비동기 유실이 아니라 요청 단계 종료 케이스로 보이며, 로그상 lock timeout 1건과 대응한다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 32.16% |
| delivery-service system CPU 최대값 | 47.37% |
| delivery-service heap 최대값 | 458,632,184 bytes |
| delivery-service GC pause 최대값 | 85ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 37 |
| user-service `/internal/delivery-managers/search` 평균 | 78.46ms |
| user-service `/internal/delivery-managers/search` 최대 | 79.23ms |
| hub-service `/internal/hub-routes/path` 평균 | 3.23ms |
| hub-service `/internal/hub-routes/path` 최대 | 3.59ms |
| `/internal/deliveries` 최대 RPS | 6.4 req/s |

50VU까지 올렸지만 처리량은 30VU run의 `5.65 req/s`에서 거의 서지 않고 `5.72 req/s` 수준에 머물렀다.
반면 p95는 `5.18s -> 8.56s`, Hikari pending은 `17 -> 37`로 악화되어 현재 로직이 처리량을 더 올리지 못한 채 내부 대기만 급격히 증가하는 상태로 보인다.

### 6. 로그 및 원인 분석

Loki 기준으로 `WARN` 1건, `ERROR` 0건이었고, 경고는 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 1건이었다.
해당 경고는 `lock:delivery:company:10000000-0000-0000-0000-000000000002`와 `lock:delivery:hub:10000000-0000-0000-0000-000000000001` 조합에서 확인됐다.

`DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_REQUEST_BODY_INVALID`는 모두 0건이었다.
즉 50VU에서도 lock timeout은 일부 재현되지만, 전체 성능 악화의 주원인은 lock 자체보다 내부 대기 증가 쪽으로 보는 편이 맞다.

### 7. Zipkin 병목 분석

성공 trace 표본에서는 `http post /internal/deliveries` root span이 대략 `237~314ms`, `delivery-service -> UserClient -> /delivery-managers/search` client span이 `65~71ms`, 대응하는 `user-service` server span이 `72~78ms` 수준이었다.
`hub-service /internal/hub-routes/path`는 `1.5ms` 안팎으로 계속 가벼웠다.

즉 외부 호출 시간은 이전 run들과 큰 차이가 없고, p95 `8.56s` 급 tail latency는 delivery 내부 대기와 connection pending 확대의 영향으로 보는 해석이 더 맞다.

### 8. 결론

```text
WARN delivery create logic 50VU

- 총 요청 2750건
- checks 성공률 99.96%
- HTTP 실패율 0.03%
- 백엔드 성공 2749건 / 실패 1건
- lock timeout 1건
- p95 8.56s, p99 9.00s
- Hikari pending 최대 37
```

이번 50VU run은 현재 배송 생성 로직의 ceiling 데이터를 보여준다.
30VU에서 이미 처리량이 멈췄고 50VU에서는 지연과 pending만 더 커졌으므로, 다음 단계는 같은 분산 조건에서 delivery 내부 로직과 DB 대기를 최적화한 뒤 20VU, 30VU, 50VU를 다시 비교하는 쪽이 적절하다.
