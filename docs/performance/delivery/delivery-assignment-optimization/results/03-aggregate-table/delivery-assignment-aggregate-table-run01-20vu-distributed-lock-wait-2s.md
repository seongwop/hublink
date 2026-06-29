# Delivery Assignment Aggregate Table Only Run 01 - 20VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

집계 테이블 도입 후 20VU 분산 입력 기준 성능 변화를 확인하고, baseline 대비 실제 개선 여부를 검증한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table Only Run 01 - 20VU Distributed No Sleep, Lock Wait 2s |
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
| 총 요청 수 | 4298 |
| HTTP TPS | 8.94 req/s |
| 실패 요청 수 | 14 |
| 실패율 | 0.32% |
| checks 성공률 | 99.67% |
| 평균 응답 시간 | 831.52ms |
| p95 응답 시간 | 1.68s |
| p99 응답 시간 | 2.02s |
| 최대 응답 시간 | 2.63s |
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

| 항목 | baseline | 테스트 후 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 37884 | 4284 |
| `p_delivery_route_histories` | 67200 | 75768 | 8568 |
| `p_delivery_outboxes` | 33600 | 37884 | 4284 |
| `delivery.create.succeed` outbox | 26880 | 31164 | 4284 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- k6 총 요청 4298건 중 실제 DB/outbox 반영은 4284건으로 확인됐다
- 차이 14건은 HTTP 실패 14건과 정확히 일치했다
- route history 증가량 8568건으로 배송 1건당 평균 2건 경로 생성 패턴은 유지됐다

### 5. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| delivery process CPU 최대 / 평균 | 62.56% / 47.92% |
| delivery system CPU 최대 / 평균 | 97.25% / 83.79% |
| JVM heap 최대 | 370931200 bytes, 약 354 MiB |
| GC pause 최대 | 370ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 3 |
| `/internal/deliveries` RPS 최대 | 11.50 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 1080.74ms |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 86.21ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 5.26ms |
| data-vm host CPU 최대 | 60.13% |
| PostgreSQL active connection 최대 | 2 |
| PostgreSQL idle connection 최대 | 90 |
| PostgreSQL locks 최대 | 16 |
| PostgreSQL commit TPS 최대 | 164.83 tx/s |

- DB active connection 자체는 낮았고, pool pending도 최대 3 수준이었다
- 외부 호출 지연은 baseline 대비 오히려 낮았는데 전체 응답 시간은 더 느려졌다
- 병목이 `user-service`, `hub-service` 외부 호출이 아니라 delivery-service 내부 임계 구간으로 이동한 상태다

### 6. 로그 및 원인 분석

- Loki 기준 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 14건
- `DELIVERY_UNEXPECTED_EXCEPTION` 0건
- delivery-service `ERROR` 로그 0건
- HTTP 실패 14건은 모두 409 lock timeout 계열로 해석 가능하다

Baseline `Run 03 - 20VU Distributed` 대비 변화는 아래와 같다.

| 항목 | baseline run03 | aggregate-table run01 |
| --- | --- | --- |
| 총 요청 수 | 4737 | 4298 |
| 실패 요청 수 | 0 | 14 |
| 평균 응답 시간 | 660.67ms | 831.52ms |
| p95 | 1.33s | 1.68s |
| p99 | 1.61s | 2.02s |
| lock timeout | 0 | 14 |
| `user-service` 평균 지연 최대 | 142.26ms | 86.21ms |
| `hub-service` 평균 지연 최대 | 63.69ms | 5.26ms |

집계 테이블 도입으로 외부 API 구간은 빨라졌지만, 실제 lock 구간은 짧아지지 않았다.

원인은 현재 구조상 집계 테이블 조회와 집계 카운트 증가가 모두 Redis 분산락 내부에서 수행되기 때문이다.

- [DeliveryService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryService.java:252) 에서 `executeWithLocks(...)` 안에서 배정 로직이 수행된다
- 같은 lock 구간 안에서 [DeliveryService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryService.java:379) 의 회사 배정 카운트 조회, [DeliveryService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryService.java:401) 의 허브 배정 카운트 조회가 일어난다
- 이후 [DeliveryCreateService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryCreateService.java:87) 와 [DeliveryCreateService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryCreateService.java:90) 에서 집계 카운트 증가까지 추가된다
- [DeliveryAssignmentCountService.java](D:/Spring%20Projects/hublink/delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryAssignmentCountService.java:82) 는 manager별로 `increaseAssignmentCount` 를 반복 호출하므로 허브 경로 수만큼 추가 DB write가 lock 안에서 발생한다

즉 이번 변경은 "활성 배송 count 쿼리 제거" 자체는 맞았지만, 그 대신 "집계 조회 + 집계 upsert" 비용을 lock 안으로 끌어들여 2초 대기 한도 안에서 14건이 밀려난 상태다.

### 7. 후속 측정 범위 판단

집계 테이블만 적용한 상태에서 50VU, 80VU까지 바로 확장 측정하지 않은 이유는 20VU 단계에서 이미 baseline 대비 명확한 퇴행이 확인됐기 때문이다.

- baseline 20VU는 lock timeout이 0건이었지만, aggregate-table run01은 lock timeout이 14건 발생했다.
- 총 요청 수와 TPS도 baseline보다 낮아졌다.
- user-service, hub-service 평균 지연은 오히려 낮아졌으므로 외부 API 지연이 아니라 lock 내부 집계 write 경로가 새 병목으로 보였다.
- 이 상태로 VU를 높이면 집계 테이블 자체의 효과보다 manager별 반복 upsert 병목이 더 크게 드러날 가능성이 높았다.

따라서 다음 단계는 aggregate-only 구조를 80VU까지 밀어붙이는 것이 아니라, lock 내부 반복 write를 줄이는 bulk upsert 적용으로 잡았다. 즉 이 단계의 결론은 "집계 테이블 자체가 무효"가 아니라 "집계 테이블 증가 로직을 manager별 반복 upsert로 구현하면 lock 내부 체류 시간이 늘어 성능이 악화된다"로 보는 것이 맞다.

### 8. 결론

```text
WARN

- 총 요청 4298건
- HTTP 실패율 0.32%
- DB/outbox 최종 반영 4284건
- lock timeout 14건
- p95 1.68s, p99 2.02s
- 외부 API가 아니라 delivery-service lock 내부 write 경로가 새 병목으로 보임
```

집계 테이블 도입만으로는 현재 구조에서 성능 개선이 확인되지 않았다. 다음 단계는 집계 증가 로직의 batch 처리 또는 lock 내부 write 횟수 축소가 먼저 필요하다.
