# Delivery Assignment Aggregate Table + Bulk Upsert Run 04 - 80VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

bulk upsert 적용 후 50VU에서 lock timeout 없이 통과한 상태에서, 80VU까지 부하를 올렸을 때 현재 구조의 ceiling을 확인한다.

이번 run의 핵심 관찰 포인트는 다음 두 가지다.

- Redis 분산락 2초 대기 조건에서 lock timeout이 재발하는지
- lock timeout이 없더라도 Hikari pending, p95/p99, max latency가 threshold를 깨는지

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table + Bulk Upsert Run 04 - 80VU Distributed No Sleep, Lock Wait 2s |
| 테스트 시간 | 2026-06-29 11:49 KST |
| 조회 범위 | 2026-06-29 11:48~11:59 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `delivery.assignment.lock-wait=2s` |

실행 명령어:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 10587 |
| HTTP TPS | 22.06 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.95s |
| median 응답 시간 | 3.33s |
| p90 응답 시간 | 3.82s |
| p95 응답 시간 | 3.97s |
| p99 응답 시간 | 6.63s |
| 최대 응답 시간 | 10.22s |
| max VU | 80 |

HTTP 실패율과 checks threshold는 통과했지만, latency threshold는 실패했다.

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✗ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. DB 처리 결과

| 항목 | baseline | 테스트 후 | 증가량 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33600 | 44187 | 10587 |
| `p_delivery_route_histories` | 67200 | 88374 | 21174 |
| `p_delivery_outboxes` | 33600 | 44187 | 10587 |
| `delivery.create.succeed` outbox | 26880 | 37467 | 10587 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- k6 총 요청 10587건이 모두 DB/outbox에 반영됐다.
- HTTP 실패, failed outbox, DLQ 증가는 모두 0건이다.
- route history는 21174건 증가해 성공 배송 1건당 평균 2개 경로가 생성됐다.

집계 테이블 상태:

| 항목 | 테스트 후 |
| --- | ---: |
| `COMPANY_DELIVERY` row 수 | 1800 |
| `COMPANY_DELIVERY` active count 합계 | 14187 |
| `HUB_DELIVERY` row 수 | 1500 |
| `HUB_DELIVERY` active count 합계 | 12387 |

### 5. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | ---: |
| delivery process CPU 최대 / 평균 | 49.68% / 36.48% |
| delivery system CPU 최대 / 평균 | 66.77% / 55.05% |
| JVM heap 최대 | 452824064 bytes, 약 432 MiB |
| GC pause 최대 | 41ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 72 |
| Hikari timeout 증가 | 0 |
| `/internal/deliveries` RPS 최대 | 22.70 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 3.59s |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 55.71ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 1.55ms |
| data-vm host CPU 최대 / 평균 | 50.82% / 35.15% |
| PostgreSQL active connection 최대 | 3 |
| PostgreSQL idle connection 최대 | 91 |
| PostgreSQL locks 최대 | 18 |
| PostgreSQL commit TPS 최대 | 397.23 tx/s |
| PostgreSQL rollback TPS 최대 | 0 |

- Hikari active는 최대 10으로 pool 상한에 도달했다.
- Hikari pending은 최대 72까지 증가했다.
- Hikari timeout은 0이므로 pool 대기가 실패로 이어지지는 않았지만, 응답 지연으로 흡수됐다.
- PostgreSQL active connection은 최대 3으로 낮게 관측됐고 rollback TPS도 0이다.
- user-service와 hub-service 평균 지연은 낮아, downstream API 지연이 p95/p99 실패의 주 원인으로 보이지 않는다.

### 6. Loki 로그 및 실패 원인

| 항목 | 값 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `DELIVERY_ASSIGNMENT_LOCK_FAILED` | 0 |
| delivery-service `error` 단어 로그 | 0 |
| delivery-service `warn` 단어 로그 | 0 |

- 80VU 조건에서도 lock timeout은 발생하지 않았다.
- HTTP 실패도 없었고, delivery-service warn/error 로그도 확인되지 않았다.
- 이번 run의 실패는 애플리케이션 오류가 아니라 p95/p99 latency threshold 실패다.

### 7. lock wait 설정 확인

현재 배송 담당자 배정 lock wait는 2초가 맞다.

```yaml
delivery:
  assignment:
    lock-wait: ${DELIVERY_ASSIGNMENT_LOCK_WAIT:2s}
```

실제 `DeliveryAssignmentLockService`도 동일 값을 사용한다.

```java
@Value("${delivery.assignment.lock-wait:2s}")
private Duration lockWait;

boolean locked = lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
```

delivery-service 컨테이너에는 `DELIVERY_ASSIGNMENT_LOCK_WAIT` 환경변수 override가 없었다. 따라서 이번 80VU 테스트도 기본값 2초 wait 조건에서 수행된 것으로 본다.

### 8. 20VU / 50VU / 80VU 비교

| 항목 | 20VU run02 | 50VU run03 | 80VU run04 |
| --- | ---: | ---: | ---: |
| 총 요청 수 | 7752 | 9994 | 10587 |
| HTTP TPS | 16.15 | 20.82 | 22.06 |
| 실패 요청 수 | 2 | 0 | 0 |
| 실패율 | 0.02% | 0.00% | 0.00% |
| 평균 응답 시간 | 1.01s | 1.96s | 2.95s |
| p95 | 1.76s | 2.77s | 3.97s |
| p99 | 2.19s | 3.96s | 6.63s |
| 최대 응답 시간 | 2.94s | 6.47s | 10.22s |
| lock timeout | 2 | 0 | 0 |
| DB/outbox 반영 | 7750 | 9994 | 10587 |
| Hikari pending 최대 | 12 | 42 | 72 |
| `/internal/deliveries` RPS 최대 | 20.77 | 21.87 | 22.70 |
| `/internal/deliveries` 서버 평균 지연 최대 | 1.40s | 2.55s | 3.59s |

VU를 20에서 80까지 올렸지만 TPS는 16.15에서 22.06으로만 증가했다. 반면 p95, p99, Hikari pending은 크게 증가했다.

즉 bulk upsert 이후 병목은 다음 흐름으로 이동했다.

1. aggregate run01: lock timeout이 직접 발생
2. bulk upsert run02~run04: lock timeout은 거의 사라짐
3. 50VU 이후: Hikari pending과 tail latency가 증가
4. 80VU: HTTP 실패는 없지만 p95/p99 threshold 실패

### 9. 비교 기준 주의

이번 80VU 결과를 해석할 때 비교 기준을 구분해야 한다.

- `baseline 80VU`는 집계 테이블과 bulk upsert가 모두 적용되기 전의 기존 배정 로직 결과다.
- `aggregate run01`은 집계 테이블은 적용했지만 bulk upsert는 적용하지 않은 결과이며, 현재 문서상 20VU 결과만 있다.
- `bulk upsert run04`는 집계 테이블과 bulk upsert가 모두 적용된 80VU 결과다.

따라서 "baseline 80VU 대비 bulk upsert 80VU 개선"은 말할 수 있지만, "aggregate-only 80VU 대비 bulk upsert 80VU 개선"은 직접 비교할 수 없다. aggregate-only 상태는 20VU에서 이미 baseline보다 낮은 TPS와 lock timeout 14건이 확인됐기 때문에, 고부하 측정보다 lock 내부 반복 upsert 제거가 먼저 필요하다고 판단했다.

정리하면 다음과 같다.

- 집계 테이블만 적용한 20VU 결과는 baseline보다 나빴다.
- 집계 테이블 증가 로직을 bulk upsert로 바꾼 뒤에는 같은 20VU에서 처리량과 lock timeout이 개선됐다.
- 50VU, 80VU의 bulk upsert 결과는 기존 baseline과의 간접 비교로 해석해야 한다.

### 10. bulk upsert와 lock timeout 해석

bulk upsert 하나만으로 "락 경합이 완전히 사라졌다"고 보기는 어렵다. 더 정확한 해석은 다음과 같다.

- lock wait는 여전히 2초다.
- Redis lock 구조도 그대로다.
- lock key도 여전히 company hub와 hub departure 기준으로 잡힌다.
- 달라진 것은 lock을 잡은 뒤 수행하는 집계 증가 DB write 횟수다.

기존 aggregate run01에서는 집계 증가가 manager별 반복 upsert로 수행되어 lock 내부 체류 시간이 길어졌다. bulk upsert는 이 반복 write를 단일 native SQL로 줄여 lock 보유 시간을 줄인다.

따라서 lock 경합 자체가 사라진 것이 아니라, lock을 들고 있는 시간이 줄어들면서 2초 wait 안에 다음 요청들이 lock을 획득할 가능성이 커진 것이다.

다만 20VU run02에서 lock timeout 2건이 있었고 50/80VU에서 0건이 된 것은 단일 원인으로 단정하면 안 된다. 가능한 설명은 다음과 같다.

- 20VU run02의 2건은 전체 7752건 중 0.02%라 일시적인 hotspot spike일 수 있다.
- 50/80VU에서는 TPS 증가폭보다 latency 증가폭이 커져 closed-loop k6 특성상 요청 주입이 자연스럽게 눌렸다.
- 이전 run으로 JVM JIT, DB buffer cache, Hikari pool, Eureka/LB cache가 warm-up된 영향이 있었을 수 있다.
- 50/80VU에서는 lock timeout으로 실패하기 전에 connection/pool 대기와 transaction 체류 시간이 latency로 흡수된 것으로 보인다.

### 11. 결론

```text
FAIL_LATENCY_WITH_CONNECTION_WAIT

- 총 요청 10587건
- HTTP 실패율 0.00%
- DB/outbox 최종 반영 10587건
- failed/dlq outbox 증가 0건
- lock timeout 0건
- p95 3.97s, p99 6.63s
- p95/p99 threshold 실패
- Hikari pending 72로 connection 대기 증가
```

80VU에서는 bulk upsert 적용 후 lock timeout이 재발하지 않았고, 모든 요청이 DB/outbox에 반영됐다. 하지만 p95와 p99 threshold가 모두 깨졌고, Hikari pending이 72까지 증가했다.

따라서 현재 병목은 Redis lock timeout보다 delivery-service 내부의 connection/transaction 점유 시간과 pool 대기 구간으로 이동한 것으로 판단한다. 다음 최적화는 lock key 분산보다 먼저 transaction 범위 축소, DB connection 점유 시간 확인, Hikari pool 조정 실험을 비교하는 방향이 더 타당하다.
