# Delivery Assignment Baseline Run 02 - 20VU Distributed 결과

### 1. 테스트 목적

`14-reset-delivery-perf-baseline.sql` 기준으로 분산 입력 20VU baseline을 확보하고, 이후 배송 기사 배정 최적화 전후 성능 비교 기준선을 만든다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 02 - 20VU Distributed |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":20},{\"duration\":\"5m\",\"target\":20},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 7082 |
| HTTP TPS | 14.75 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 110.06ms |
| p95 응답 시간 | 279.04ms |
| p99 응답 시간 | 537.81ms |
| 최대 응답 시간 | 1.1s |
| max VU | 20 |

Threshold 결과는 모두 통과했다.

```text
checks
✓ rate>0.90

http_req_duration
✓ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. DB 처리 결과

baseline seed 기준 총량과 테스트 후 총량 비교다.

| 항목 | baseline | 테스트 후 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40682 | 7082 |
| `p_delivery_route_histories` | 67200 | 81364 | 14164 |
| `p_delivery_outboxes` | 33600 | 40682 | 7082 |
| `delivery.create.succeed` outbox | 26880 | 33962 | 7082 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- `p_deliveries` 증가량 7082건이 k6 총 성공 요청 수 7082건과 일치
- `p_delivery_outboxes` 증가량도 7082건으로 일치
- `p_delivery_route_histories`는 14164건 증가해 배송 1건당 평균 2건의 경로 이력이 생성
- failed, dlq outbox 증가는 없어서 테스트 구간 신규 실패 이벤트는 관측되지 않음

### 5. Grafana 관측 결과

| 항목 | 값 |
| --- | --- |
| delivery process CPU 최대 / 평균 | 33.23% / 15.08% |
| delivery system CPU 최대 / 평균 | 88.91% / 42.48% |
| JVM heap 최대 | 447709624 bytes, 약 427 MiB |
| GC pause 최대 | 51ms |
| Hikari active 최대 | 7 |
| Hikari idle 최대 | 10 |
| Hikari pending 최대 | 0 |
| Hikari timeout | 0 |
| `/internal/deliveries` 서버 평균 지연 피크 | 151.17ms |
| delivery RPS 피크 | 18.60 req/s |
| Tomcat busy ratio 최대 | 3.5% |
| Tomcat current connections 최대 | 22 |
| JVM live threads 최대 | 111 |
| process open files 최대 | 197 |
| data-vm host CPU 최대 / 평균 | 47.72% / 24.11% |
| data-vm host memory 최대 | 30.48% |
| PostgreSQL active connection 최대 | 3 |
| PostgreSQL idle connection 최대 | 91 |
| PostgreSQL locks 최대 | 15 |
| PostgreSQL commit TPS 최대 | 303.37 tx/s |
| PostgreSQL cache hit ratio | 99.88% |
| PostgreSQL deadlock | 0 |

delivery-service 자체 자원은 여유가 컸고, connection pool 대기나 GC 이상 징후는 없었다. 분산 입력으로 hotspot이 줄면서 같은 20VU 집중형 결과 대비 p95가 `596.42ms -> 279.04ms`, p99가 `1.58s -> 537.81ms`로 낮아졌다.

### 6. 로그 및 병목 분석

- Loki 기준 `delivery-service` WARN 0건, ERROR 0건
- `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 0건
- `DELIVERY_CREATE_DUPLICATE_SKIPPED` 0건
- `DELIVERY_CREATE_FAILED_ENQUEUED` 0건
- `DELIVERY_CREATE_DLQ_ENQUEUED` 0건
- `hub-service`의 `No timeLimiterConfig found ...` WARN은 72건 관측
- resilience4j circuit breaker `open` 상태는 관측되지 않았고 failure rate도 0%

Zipkin 샘플 trace에서는 root `POST /internal/deliveries`가 약 `55ms`, `user-service /internal/delivery-managers/search` 호출이 약 `14ms`, `hub-service /internal/hub-routes/path` 호출이 약 `1~2ms` 수준이었다. 외부 호출보다 내부 배정 로직과 repository 구간 비중이 더 컸다.

같은 시점 Prometheus repository 지표 상위는 아래와 같았다.

| 메서드 | 관측 평균 |
| --- | --- |
| `DeliveryRouteHistoryRepository.countActiveAssignmentsByManagerIds` | 11.17ms |
| `DeliveryRepository.countActiveAssignmentsByManagerIds` | 9.15ms |
| `DeliveryRouteHistoryRepository.saveAllAndFlush` | 2.25ms |
| `DeliveryRepository.flush` | 1.46ms |
| `DeliveryRepository.saveAndFlush` | 1.40ms |

분산형 20VU baseline에서는 lock 경쟁보다 active assignment count 집계 쿼리와 flush 계열 비용이 먼저 관측 포인트로 드러났다.

### 7. 결론

```text
PASS

- 총 요청 7082건, 실패 0건
- p95 279.04ms, p99 537.81ms
- DB/outbox 증가량이 k6 성공 요청 수와 일치
- lock timeout, duplicate skip, delivery warn/error 없음
- 현재 baseline 20VU에서는 자원 포화보다 배정 집계 쿼리와 flush 비용이 주요 관측 지점
```
