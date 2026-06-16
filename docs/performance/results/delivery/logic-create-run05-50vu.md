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

HTTP 성공 2749건과 DB delivery 생성 2749건, success outbox 2749건이 일치했다.
실패 1건은 비동기 누락이 아니라 요청 단계에서 종료된 케이스로 보이며, 로그상 lock timeout 1건과 대응하는 것으로 판단했다.

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

50VU까지 올렸지만 처리량은 30VU run의 `5.65 req/s`에서 거의 늘지 않고 `5.72 req/s`에 머물렀다.
반면 p95는 `5.18s -> 8.56s`, Hikari pending은 `17 -> 37`로 더 악화돼, 현재 로직은 처리량을 더 늘리지 못한 채 내부 대기만 급격히 증가하는 상태로 보인다.

### 6. 로그 및 원인 분석

Loki 기준으로 이번 구간에는 `WARN` 1건, `ERROR` 0건이었고, 경고는 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 1건이었다.
첫 경고는 `lock:delivery:company:10000000-0000-0000-0000-000000000002`와 `lock:delivery:hub:10000000-0000-0000-0000-000000000001` 조합에서 발생했다.

`DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_REQUEST_BODY_INVALID`는 모두 0건이었다.
즉 50VU에서는 lock timeout이 소량 재발하긴 했지만, 전체 성능 악화의 주원인은 lock 재시도보다 훨씬 큰 내부 대기 증가로 보는 편이 맞다.

### 7. Zipkin 병목 분석

대표 성공 trace 3개 기준 `http post /internal/deliveries` root span은 약 `237~314ms` 범위였다.
같은 trace 안에서 가장 무거운 외부 호출은 여전히 `delivery-service -> UserClient -> /delivery-managers/search`였고, client span은 약 `65~71ms`, 대응하는 `user-service` server span은 약 `72~78ms` 수준이었다.
`hub-service /internal/hub-routes/path`는 약 `1.5ms`, `HubClient` client span은 약 `2.6ms` 수준으로 계속 가벼웠다.

trace 표본의 성공 요청은 여전히 수백 ms 수준인데 전체 p95는 8초대로 늘어났다.
즉 50VU에서 커진 tail latency는 외부 API 지연보다 delivery 내부 대기, 특히 DB connection pending과 요청 큐잉 구간의 영향이 더 지배적이다.

### 8. 결론

```text
WARN delivery create logic 50VU

- 총 요청 2750건
- checks 성공률 99.96%
- HTTP 실패율 0.03%
- 백엔드 성공 2749건 / 실패 1건
- lock timeout 1건 재발
- p95 8.56s, p99 9.00s
- Hikari pending 최대 37
```

이번 50VU run은 현재 배송 생성 로직의 ceiling 데이터를 보여줬다.
30VU에서 이미 처리량이 멈췄는데 50VU에서는 지연과 pending만 더 커졌으므로, 이제는 추가 부하 실험보다 같은 분산 조건에서 delivery 내부 DB 대기와 배정 로직을 최적화한 뒤 20VU·30VU·50VU를 다시 비교하는 편이 훨씬 가치 있다.
