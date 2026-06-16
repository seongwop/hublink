# 배송 생성 로직 Run 06 50VU 누적오염 결과

### 1. 테스트 목적

기본 `.env.k6` 분산 조건에서 이전 테스트 누적으로 active assignment가 남아 있는 상태로 50VU까지 올렸을 때 실패 원인과 처리량 한계를 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 06 50VU 누적오염 |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

별도 환경변수를 덮어쓰지 않았으므로 실제 실행 조건은 [performance/k6/.env.k6](/d:/Spring%20Projects/hublink/performance/k6/.env.k6:1)의 `RECEIVER_COMPANY_IDS` 18개 분산 조건이다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 2689 |
| HTTP TPS | 5.59 req/s |
| 실패 요청 수 | 170 |
| 실패율 | 6.32% |
| checks 성공률 | 93.67% |
| 평균 응답 시간 | 6.30s |
| p95 응답 시간 | 9.07s |
| p99 응답 시간 | 9.43s |
| 최대 응답 시간 | 16.50s |
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
| 백엔드 성공 처리 수 | 2519 |
| 생성 delivery 수 | 2519 |
| 생성 route history 수 | 5038 |
| `delivery.create.succeed` outbox 수 | 2519 |
| `delivery.create.failed` outbox 수 | 0 |
| `delivery.create.dlq` outbox 수 | 0 |
| Spring 201 응답 카운터 증가 | 2519 |
| Spring 404 응답 카운터 증가 | 168 |
| Spring 409 응답 카운터 증가 | 2 |
| Spring 5xx 응답 카운터 증가 | 0 |
| Kafka lag / 회복 시간 | N/A |

k6 성공 2519건과 DB delivery 생성 2519건, success outbox 2519건은 일치한다.
추가로 종료 후 1분 tail 구간까지 포함해 다시 집계하면 Spring POST 카운터도 총 2689건으로 k6 총 요청 수와 일치한다.
즉 실패 170건은 애플리케이션 바깥 유실이 아니라 `404` 168건과 `409` 2건으로 설명된다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 32.23% |
| delivery-service system CPU 최대값 | 46.23% |
| delivery-service heap 최대값 | 458,682,880 bytes |
| delivery-service GC pause 최대값 | 76ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 37 |
| Tomcat busy ratio 최대값 | 23.00% |
| user-service `/internal/delivery-managers/search` 평균 | 76.10ms |
| user-service `/internal/delivery-managers/search` 최대 | 91.18ms |
| hub-service `/internal/hub-routes/path` 평균 | 1.42ms |
| hub-service `/internal/hub-routes/path` 최대 | 1.61ms |
| `/internal/deliveries` 평균 RPS | 4.94 req/s |
| `/internal/deliveries` 최대 RPS | 6.10 req/s |

CPU나 Tomcat thread는 포화가 아니었지만 Hikari pending은 37까지 증가했다.
즉 이번 구간의 지연 증가는 단순 CPU 부족보다 내부 대기와 연결 풀 적체 영향이 더 컸다.

### 6. 로그 및 원인 분석

Loki raw 로그 기준으로 `WARN` 2건, `ERROR` 0건이었고, 경고 2건은 모두 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`이었다.
첫 경고는 `lock:delivery:company:10000000-0000-0000-0000-000000000002`와 `lock:delivery:hub:10000000-0000-0000-0000-000000000001` 조합에서 확인됐다.

`DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_REQUEST_BODY_INVALID`, `DELIVERY_CONCURRENCY_CONFLICT`, `DELIVERY_DOWNSTREAM_CALL_FAILED`는 모두 0건이었다.
따라서 이번 run의 실패 원인은 lock timeout보다는 `404` 응답이 주도했다고 보는 편이 맞다.
코드상 `404` 후보는 `NO_DELIVERY_MANAGER`, `NO_HUB_ROUTE`, `NO_HUB_MANAGER`인데, 이번 조건에서는 [DeliveryService.java](/d:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryService.java:427) 의 `selectManagerWithinCapacity()`가 `maxActiveAssignmentsPerManager=30` 제한에 걸려 `NO_DELIVERY_MANAGER`를 반환했을 가능성이 가장 높다.
실제로 설정은 [config-repo/delivery-service.yml](/d:/Spring%20Projects/hublink/config-repo/delivery-service.yml:57)에 `delivery.assignment.max-active-per-manager: 30`으로 잡혀 있고, DB 조회 결과 active hub assignment가 이미 30에 찬 매니저가 다수 확인됐다.

### 7. Zipkin 병목 분석

성공 trace 표본에서는 `user-service /internal/delivery-managers/search`가 대체로 `67~74ms`, `hub-service /internal/hub-routes/path`는 `1.2~1.6ms` 수준이었다.
외부 호출 시간은 이전 run들과 비슷했고 p95 `9.07s`와는 큰 차이가 난다.

즉 이번 run의 주원인은 외부 API 자체보다 delivery 내부 대기, Hikari pending 증가, 그리고 이전 테스트 누적으로 인한 담당자 가용성 소진이다.

### 8. 결론

```text
WARN delivery create logic 50VU distributed with accumulated active assignments

- 총 요청 2689건
- checks 성공률 93.67%
- HTTP 실패율 6.32%
- 백엔드 성공 2519건 / 실패 170건
- 404 168건 / 409 2건
- lock timeout 로그 2건
- p95 9.07s, p99 9.43s
- Hikari pending 최대 37
```

이번 run은 락 경합 재현 실험이라기보다, 이전 테스트 누적으로 active assignment가 쌓인 상태에서 분산 50VU를 다시 실행한 결과에 가깝다.
따라서 실패의 주원인은 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`이 아니라 `NO_DELIVERY_MANAGER` 계열 404로 보는 해석이 맞고, 다음 테스트 전에는 active delivery와 route history 상태를 초기화하거나 완전히 새로운 테스트 데이터 구간에서 다시 측정해야 한다.
