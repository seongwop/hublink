# Delivery Assignment Aggregate Table + Bulk Upsert Run 03 - 50VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

bulk upsert 적용 후 20VU에서 확인된 처리량 개선이 50VU에서도 유지되는지 확인한다.

Run 02에서는 20VU 조건에서 aggregate run01 대비 TPS와 성공 처리량이 크게 증가했고, lock timeout이 14건에서 2건으로 줄었다. 이번 run은 동일한 distributed 입력 조건에서 VU를 50으로 올려, lock timeout 재발 여부와 Hikari pending, tail latency 변화를 확인한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table + Bulk Upsert Run 03 - 50VU Distributed No Sleep, Lock Wait 2s |
| 테스트 시간 | 2026-06-29 11:25 KST |
| 조회 범위 | 2026-06-29 11:24~11:35 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

실행 명령어:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 9994 |
| HTTP TPS | 20.82 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 1.96s |
| median 응답 시간 | 2.12s |
| p90 응답 시간 | 2.62s |
| p95 응답 시간 | 2.77s |
| p99 응답 시간 | 3.96s |
| 최대 응답 시간 | 6.47s |
| max VU | 50 |

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
| `p_deliveries` | 33600 | 43594 | 9994 |
| `p_delivery_route_histories` | 67200 | 87188 | 19988 |
| `p_delivery_outboxes` | 33600 | 43594 | 9994 |
| `delivery.create.succeed` outbox | 26880 | 36874 | 9994 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- k6 총 요청 9994건이 모두 DB/outbox에 반영됐다.
- HTTP 실패, failed outbox, DLQ 증가는 모두 0건이다.
- route history는 19988건 증가해 성공 배송 1건당 평균 2개 경로가 생성됐다.

집계 테이블 상태:

| 항목 | 테스트 후 |
| --- | ---: |
| `COMPANY_DELIVERY` row 수 | 1800 |
| `COMPANY_DELIVERY` active count 합계 | 13594 |
| `HUB_DELIVERY` row 수 | 1500 |
| `HUB_DELIVERY` active count 합계 | 11794 |

### 5. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | ---: |
| delivery process CPU 최대 / 평균 | 50.78% / 35.89% |
| delivery system CPU 최대 / 평균 | 69.78% / 56.91% |
| JVM heap 최대 | 460618752 bytes, 약 439 MiB |
| GC pause 최대 | 48ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 42 |
| `/internal/deliveries` RPS 최대 | 21.87 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 2.55s |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 52.60ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 1.17ms |
| data-vm host CPU 최대 / 평균 | 56.68% / 35.91% |
| PostgreSQL active connection 최대 | 3 |
| PostgreSQL idle connection 최대 | 90 |
| PostgreSQL locks 최대 | 16 |
| PostgreSQL commit TPS 최대 | 439.77 tx/s |
| PostgreSQL rollback TPS 최대 | 0 |

- Hikari active는 최대 10으로 pool 상한까지 사용됐다.
- Hikari pending은 최대 42까지 증가했다. 50VU에서 요청 실패는 없었지만, connection 대기가 커지면서 latency가 증가한 것으로 보인다.
- PostgreSQL active connection은 최대 3으로 낮게 관측됐다. 이는 DB 서버 자체가 active query로 포화된 모습이라기보다, delivery-service의 Hikari pool 내부 대기와 lock/transaction 점유 시간이 latency를 키운 흐름에 가깝다.
- user-service와 hub-service 평균 지연은 낮다. 이번 run의 지연 증가는 downstream API 지연보다 delivery-service 내부 처리 구간과 connection 대기 쪽에 더 가깝다.

### 6. Loki 로그 및 실패 원인

| 항목 | 값 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `DELIVERY_ASSIGNMENT_LOCK_FAILED` | 0 |
| delivery-service `error` 단어 로그 | 0 |
| delivery-service `warn` 단어 로그 | 0 |

- 50VU 조건에서 lock timeout은 발생하지 않았다.
- HTTP 실패도 없었고, delivery-service warn/error 로그도 확인되지 않았다.
- run02의 Busan company lock timeout 2건은 이번 50VU에서는 재현되지 않았다.

### 7. 20VU Run 02와 비교

| 항목 | 20VU run02 | 50VU run03 |
| --- | ---: | ---: |
| 총 요청 수 | 7752 | 9994 |
| HTTP TPS | 16.15 | 20.82 |
| 실패 요청 수 | 2 | 0 |
| 실패율 | 0.02% | 0.00% |
| 평균 응답 시간 | 1.01s | 1.96s |
| p95 | 1.76s | 2.77s |
| p99 | 2.19s | 3.96s |
| 최대 응답 시간 | 2.94s | 6.47s |
| lock timeout | 2 | 0 |
| DB/outbox 반영 | 7750 | 9994 |
| Hikari pending 최대 | 12 | 42 |
| `/internal/deliveries` RPS 최대 | 20.77 | 21.87 |
| `/internal/deliveries` 서버 평균 지연 최대 | 1.40s | 2.55s |

50VU가 20VU보다 좋아진 것처럼 보이는 이유는 실패율과 총 처리량 때문이다. 실제로 50VU run03은 20VU run02보다 더 많은 요청을 성공 처리했고 lock timeout도 0건이다.

하지만 성능 곡선 전체로 보면 50VU는 더 좋아진 상태가 아니라 한계에 더 가까운 상태다.

- TPS는 16.15에서 20.82로 약 29% 증가했다.
- VU는 20에서 50으로 150% 증가했다.
- p95는 1.76s에서 2.77s로 증가했다.
- p99는 2.19s에서 3.96s로 증가했다.
- Hikari pending은 12에서 42로 증가했다.

즉 50VU에서는 더 많은 동시 요청을 받아 실패 없이 처리했지만, 추가 동시성의 상당 부분은 처리량 증가가 아니라 대기 시간 증가로 흡수됐다.

### 8. 이전 50VU baseline/flush 결과와 비교

| 항목 | baseline run06 50VU | flush-save run01 50VU | bulk upsert run03 50VU |
| --- | ---: | ---: | ---: |
| 총 요청 수 | 6505 | 6363 | 9994 |
| HTTP TPS | 13.55 | 13.25 | 20.82 |
| 실패 요청 수 | 3 | 9 | 0 |
| 실패율 | 0.04% | 0.14% | 0.00% |
| 평균 응답 시간 | 2.01s | 2.07s | 1.96s |
| p95 | 2.94s | 2.94s | 2.77s |
| p99 | 3.47s | 3.56s | 3.96s |
| lock timeout | 3 | 9 | 0 |
| Hikari pending 최대 | 29 | 29 | 42 |

같은 50VU 기준으로 보면 bulk upsert 적용 후 성능 개선은 명확하다.

- 처리량은 baseline run06 대비 6505건에서 9994건으로 증가했다.
- lock timeout은 3건에서 0건으로 줄었다.
- p95는 2.94s에서 2.77s로 소폭 개선됐다.
- 평균 응답 시간도 2.01s에서 1.96s로 소폭 개선됐다.

다만 p99는 3.47s에서 3.96s로 증가했고, Hikari pending 최대도 29에서 42로 증가했다. 즉 평균/p95와 성공 처리량은 좋아졌지만 tail latency와 pool 대기는 아직 남아 있다.

### 9. 결론

```text
PASS_WITH_CONNECTION_WAIT_RISK

- 총 요청 9994건
- HTTP 실패율 0.00%
- DB/outbox 최종 반영 9994건
- failed/dlq outbox 증가 0건
- lock timeout 0건
- p95 2.77s, p99 3.96s
- 기존 50VU baseline 대비 처리량과 lock timeout은 개선
- Hikari pending 42로 connection 대기 위험은 증가
```

bulk upsert 적용 후 50VU에서는 lock timeout이 사라지고 처리량이 크게 증가했다. 따라서 aggregate table 도입 이후의 반복 upsert 비용은 bulk upsert로 확실히 완화됐다고 볼 수 있다.

다만 VU를 20에서 50으로 올렸을 때 TPS 증가폭은 제한적이고, p95/p99 및 Hikari pending이 크게 증가했다. 다음 병목은 Redis lock timeout보다는 delivery-service 내부 transaction/connection 점유 시간, 또는 Hikari pool 대기 구간으로 이동한 것으로 보인다.

다음 단계는 80VU 또는 100VU로 올려 현재 구조의 ceiling을 확인하는 것이다. 80VU 이상에서 p95 threshold가 깨지거나 Hikari pending이 더 커지면, pool/bucket 단위 lock 분리 또는 transaction 범위 축소를 우선 비교한다.
