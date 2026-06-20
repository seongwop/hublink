# 배송 생성 로직 Run 04 30VU 결과

### 1. 테스트 목적

분산된 `RECEIVER_COMPANY_IDS` 조건을 유지한 채 부하를 30VU로 올렸을 때 배송 생성 로직의 실제 한계 지점과 tail latency 악화 양상을 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 04 30VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,...,20000000-0000-0000-0000-000000000027' STAGES='[{"duration":"1m","target":30},{"duration":"5m","target":30},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 30 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 2712 |
| HTTP TPS | 5.65 req/s |
| 실패 요청 수 | 5 |
| 실패율 | 0.18% |
| checks 성공률 | 99.81% |
| 평균 응답 시간 | 3.35s |
| p95 응답 시간 | 5.18s |
| p99 응답 시간 | 6.02s |
| 최대 응답 시간 | 7.08s |
| max VU | 30 |

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
| 백엔드 성공 처리 수 | 2707 |
| 생성 delivery 수 | 2707 |
| 생성 route history 수 | 5414 |
| `delivery.create.succeed` outbox 수 | 2707 |
| `delivery.create.failed` outbox 수 | 0 |
| `delivery.create.dlq` outbox 수 | 0 |
| Kafka lag / 회복 시간 | N/A |

HTTP 성공 2707건과 DB delivery 생성 2707건, success outbox 2707건이 일치했다.
실패 5건은 비동기 누락이 아니라 요청 단계에서 종료된 케이스로 보인다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 35.78% |
| delivery-service system CPU 최대값 | 50.95% |
| delivery-service heap 최대값 | 435,779,072 bytes |
| delivery-service GC pause 최대값 | 85ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 17 |
| user-service `/internal/delivery-managers/search` 평균 | 78.89ms |
| user-service `/internal/delivery-managers/search` 최대 | 80.55ms |
| hub-service `/internal/hub-routes/path` 평균 | 3.63ms |
| hub-service `/internal/hub-routes/path` 최대 | 4.33ms |
| `/internal/deliveries` 최대 RPS | 6.4 req/s |

30VU로 올렸지만 처리량은 이전 분산 20VU run의 `5.62 req/s`와 거의 같은 `5.65 req/s`에 머물렀다.
반면 p95는 `3.21s -> 5.18s`, Hikari pending은 `7 -> 17`로 악화돼, 현재 로직은 20VU 부근에서 이미 포화에 가까운 상태로 보인다.

### 6. 로그 및 원인 분석

Loki 기준으로 이번 구간에는 `WARN` 5건, `ERROR` 0건이었고, 경고는 모두 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`이었다.
첫 경고는 `lock:delivery:company:10000000-0000-0000-0000-000000000003`와 `lock:delivery:hub:10000000-0000-0000-0000-000000000001` 조합에서 발생했다.

`DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_REQUEST_BODY_INVALID`는 모두 0건이었다.
따라서 이번 실패 5건은 사실상 `409 DELIVERY_014` 계열 lock timeout으로 판단해도 무리가 없다.

### 7. Zipkin 병목 분석

대표 성공 trace 5개 기준 `http post /internal/deliveries` root span은 약 `231~305ms` 범위였다.
같은 trace 안에서 가장 무거운 외부 호출은 `delivery-service -> UserClient -> /delivery-managers/search`였고, client span은 약 `69.87ms`, 대응하는 `user-service` server span은 약 `77.09ms`였다.
`hub-service /internal/hub-routes/path`는 약 `1.34ms`, `HubClient` client span도 약 `2.45ms` 수준이라 이번 구간의 주된 외부 병목은 아니었다.

다만 trace 표본의 성공 요청 자체는 수백 ms 수준인데 전체 p95는 5초대까지 밀렸다.
즉 tail latency의 주원인은 외부 API 자체보다 delivery 내부 대기, 특히 connection pending과 lock 대기 구간에서 커진 것으로 보는 해석이 맞다.

### 8. 결론

```text
WARN delivery create logic 30VU

- 총 요청 2712건
- checks 성공률 99.81%
- HTTP 실패율 0.18%
- 백엔드 성공 2707건 / 실패 5건
- lock timeout 5건 재발
- p95 5.18s, p99 6.02s
- Hikari pending 최대 17
```

이번 30VU run은 분산 조건에서도 처리량이 더 늘지 않고 지연과 pending만 악화되는 구간을 보여줬다.
포트폴리오 관점에서는 현재 배송 생성 로직의 실질 한계가 `20~30VU` 사이에 있음을 확인한 셈이고, 다음 단계는 이 조건을 기준 시나리오로 삼아 delivery 내부 DB 대기와 배정 로직 최적화를 진행하는 편이 가장 좋다.
