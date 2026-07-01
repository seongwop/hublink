# Delivery Assignment Aggregate Table + Bulk Upsert Run 02 - 20VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

배송 담당자 배정 집계 테이블 도입 이후, manager별 반복 upsert를 PostgreSQL bulk upsert로 변경한 상태에서 20VU 분산 입력 성능을 재측정한다.

이전 aggregate table run01은 집계 테이블 조회와 집계 증가가 Redis 분산락 내부에서 수행되면서 lock timeout 14건이 발생했다. 이번 run은 bulk upsert 적용으로 lock 내부 DB write 비용이 줄었는지 확인하는 것이 목적이다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table + Bulk Upsert Run 02 - 20VU Distributed No Sleep, Lock Wait 2s |
| 테스트 시간 | 2026-06-29 11:00 KST |
| 조회 범위 | 2026-06-29 10:59~11:10 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

실행 명령어:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

`PRODUCT_NAME`은 새 GCP load-test VM에 기존 `.env.k6`가 없는 상태에서 k6 `envList('PRODUCT_NAMES', 'PRODUCT_NAME')` 초기화 오류를 피하기 위해 명시했다. payload 기본값과 동일한 `k6-test-product`라 테스트 변수 의미는 바꾸지 않는다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 7752 |
| HTTP TPS | 16.15 req/s |
| 실패 요청 수 | 2 |
| 실패율 | 0.02% |
| checks 성공률 | 99.97% |
| 평균 응답 시간 | 1.01s |
| median 응답 시간 | 984.08ms |
| p90 응답 시간 | 1.54s |
| p95 응답 시간 | 1.76s |
| p99 응답 시간 | 2.19s |
| 최대 응답 시간 | 2.94s |
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
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33600 | 41350 | 7750 |
| `p_delivery_route_histories` | 67200 | 82700 | 15500 |
| `p_delivery_outboxes` | 33600 | 41350 | 7750 |
| `delivery.create.succeed` outbox | 26880 | 34630 | 7750 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- k6 총 요청 7752건 중 DB/outbox 반영은 7750건이다.
- 차이 2건은 HTTP 실패 2건과 정확히 일치한다.
- route history는 15500건 증가해 성공 배송 1건당 평균 2개 경로가 생성됐다.
- failed/dlq outbox 증가는 없었다.

집계 테이블 상태:

| 항목 | 테스트 후 |
| --- | ---: |
| `COMPANY_DELIVERY` row 수 | 1800 |
| `COMPANY_DELIVERY` active count 합계 | 11350 |
| `HUB_DELIVERY` row 수 | 1500 |
| `HUB_DELIVERY` active count 합계 | 9550 |

### 5. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | ---: |
| delivery process CPU 최대 / 평균 | 56.07% / 38.07% |
| delivery system CPU 최대 / 평균 | 97.42% / 67.82% |
| JVM heap 최대 | 412315648 bytes, 약 393 MiB |
| GC pause 최대 | 149ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 12 |
| `/internal/deliveries` RPS 최대 | 20.77 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 1.40s |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 74.00ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 85.11ms |
| data-vm host CPU 최대 / 평균 | 55.42% / 33.54% |
| PostgreSQL active connection 최대 | 3 |
| PostgreSQL idle connection 최대 | 90 |
| PostgreSQL locks 최대 | 30 |
| PostgreSQL commit TPS 최대 | 314.23 tx/s |
| PostgreSQL rollback TPS 최대 | 0 |

- delivery-service process CPU는 run01보다 낮아졌지만, system CPU는 여전히 높은 순간이 있다.
- Hikari pending 최대는 12로, run01의 3보다 높다. 다만 TPS가 8.94에서 16.15로 크게 증가했기 때문에 부하 처리량 증가에 따른 connection 대기로 해석해야 한다.
- PostgreSQL active connection 최대는 3으로 낮고, rollback TPS는 0이다. DB 오류나 rollback 폭증은 보이지 않았다.
- user-service 평균 지연은 안정적이었고, hub-service 평균 지연은 run01보다 높지만 전체 실패 원인은 hub-service가 아니라 lock timeout으로 확인된다.

### 6. Loki 로그 및 실패 원인

| 항목 | 값 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 2 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `DELIVERY_ASSIGNMENT_LOCK_FAILED` | 0 |
| delivery-service `error` 단어 로그 | 1 |
| delivery-service `warn` 단어 로그 | 4 |

대표 lock timeout 로그:

```text
event=DELIVERY_ASSIGNMENT_LOCK_TIMEOUT
keys=[lock:delivery:company:10000000-0000-0000-0000-000000000002, lock:delivery:hub:10000000-0000-0000-0000-000000000001]
failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002
waitMillis=2000
```

- k6 실패 2건과 Loki lock timeout 2건이 일치한다.
- 실패 lock key는 모두 Busan 도착 company lock이다.
- `error` 단어 로그 1건은 Zipkin span drop 경고이며 배송 생성 실패 원인은 아니다.
- 예상 밖 예외나 DLQ 증가는 없었다.

### 7. 이전 결과와 비교

| 항목 | baseline run03 | aggregate run01 | bulk upsert run02 |
| --- | ---: | ---: | ---: |
| 총 요청 수 | 4737 | 4298 | 7752 |
| HTTP TPS | 9.86 | 8.94 | 16.15 |
| 실패 요청 수 | 0 | 14 | 2 |
| 실패율 | 0% | 0.32% | 0.02% |
| 평균 응답 시간 | 660.67ms | 831.52ms | 1.01s |
| p95 | 1.33s | 1.68s | 1.76s |
| p99 | 1.61s | 2.02s | 2.19s |
| lock timeout | 0 | 14 | 2 |
| DB/outbox 반영 | 4737 | 4284 | 7750 |
| Hikari pending 최대 | 2 | 3 | 12 |

bulk upsert 적용 후 aggregate run01 대비 처리량은 크게 증가했다.

- 총 요청 수: 4298 -> 7752
- HTTP TPS: 8.94 -> 16.15
- DB/outbox 성공 반영: 4284 -> 7750
- lock timeout: 14 -> 2
- 실패율: 0.32% -> 0.02%

반면 p95/p99는 run01보다 소폭 증가했다.

- p95: 1.68s -> 1.76s
- p99: 2.02s -> 2.19s

이번 run은 같은 20VU 조건에서 더 많은 요청을 처리했기 때문에, 단순 latency만으로 악화라고 보기 어렵다. bulk upsert는 lock 내부 write 반복을 줄여 throughput과 timeout 수를 개선했지만, 20VU에서도 company lock hotspot은 완전히 사라지지 않았다.

### 8. 결론

```text
PASS_WITH_REMAINING_LOCK_HOTSPOT

- 총 요청 7752건
- HTTP 실패율 0.02%
- DB/outbox 최종 반영 7750건
- failed/dlq outbox 증가 0건
- lock timeout 2건
- p95 1.76s, p99 2.19s
- aggregate run01 대비 TPS와 성공 처리량은 크게 개선
- 단일 company lock 경합은 아직 남아 있음
```

집계 테이블 도입 직후 run01에서 보였던 "집계 upsert 비용이 lock 내부에서 반복 발생하는 문제"는 bulk upsert로 상당 부분 완화됐다. 특히 같은 20VU 조건에서 lock timeout이 14건에서 2건으로 줄고, 성공 처리량은 4284건에서 7750건으로 증가했다.

다만 실패 2건이 모두 `lock:delivery:company:10000000-0000-0000-0000-000000000002`에서 발생했기 때문에, 다음 최적화 후보는 집계 upsert 자체보다 company delivery manager 배정 lock의 hotspot 분산이다.

다음 단계는 50VU 재측정으로 bulk upsert 효과가 고부하에서도 유지되는지 확인하는 것이다. 50VU에서 lock timeout이나 Hikari pending이 다시 커지면 Redis lock split 또는 pool/bucket 기반 lock 분리를 비교한다.
