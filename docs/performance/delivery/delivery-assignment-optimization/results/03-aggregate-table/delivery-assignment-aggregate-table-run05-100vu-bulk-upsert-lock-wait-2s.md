# Delivery Assignment Aggregate Table + Bulk Upsert Run 05 - 100VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

bulk upsert 적용 후 80VU에서 HTTP 실패 없이 처리됐지만 p95/p99 threshold가 깨진 상태에서, 100VU까지 부하를 올렸을 때 현재 구조의 처리량 ceiling과 connection 대기 증가 폭을 확인한다.

이번 run의 핵심 관찰 포인트는 다음과 같다.

- 80VU 이후에도 처리량이 의미 있게 증가하는지
- Redis lock timeout이나 HTTP 409가 재발하는지
- Hikari pending과 tail latency가 어느 수준까지 증가하는지

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table + Bulk Upsert Run 05 - 100VU Distributed No Sleep, Lock Wait 2s |
| 테스트 시간 | 2026-06-29 12:27 KST |
| 조회 범위 | 2026-06-29 12:26~12:38 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |

실행 명령어:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 10701 |
| HTTP TPS | 22.29 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 3.66s |
| median 응답 시간 | 4.26s |
| p90 응답 시간 | 4.66s |
| p95 응답 시간 | 4.76s |
| p99 응답 시간 | 8.48s |
| 최대 응답 시간 | 16.27s |
| max VU | 100 |

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
| `p_deliveries` | 33600 | 44301 | 10701 |
| `p_delivery_route_histories` | 67200 | 88602 | 21402 |
| `p_delivery_outboxes` | 33600 | 44301 | 10701 |
| `delivery.create.succeed` outbox | 26880 | 37581 | 10701 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- k6 총 요청 10701건이 모두 DB/outbox에 반영됐다.
- HTTP 실패, failed outbox, DLQ 증가는 모두 0건이다.
- route history는 21402건 증가해 성공 배송 1건당 2개 경로가 생성됐다.

집계 테이블 상태:

| 항목 | 테스트 후 |
| --- | ---: |
| `COMPANY_DELIVERY` row 수 | 1800 |
| `COMPANY_DELIVERY` active count 합계 | 14301 |
| `HUB_DELIVERY` row 수 | 1500 |
| `HUB_DELIVERY` active count 합계 | 12501 |

### 5. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | ---: |
| delivery process CPU 최대 / 평균 | 48.79% / 33.70% |
| delivery system CPU 최대 / 평균 | 68.26% / 55.15% |
| JVM heap 최대 | 479609344 bytes, 약 457 MiB |
| GC pause 최대 | 45ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 92 |
| Hikari timeout 증가 | 0 |
| `/internal/deliveries` RPS 최대 | 23.20 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 4.47s |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 62.21ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 11.77ms |
| data-vm host CPU 최대 / 평균 | 50.42% / 38.43% |
| PostgreSQL active connection 최대 | 2 |
| PostgreSQL idle connection 최대 | 90 |
| PostgreSQL locks 최대 | 29 |
| PostgreSQL commit TPS 최대 | 349.40 tx/s |
| PostgreSQL rollback TPS 최대 | 0 |

- Hikari active는 최대 10으로 pool 상한에 도달했다.
- Hikari pending은 최대 92까지 증가했다.
- Hikari timeout은 0이므로 connection 대기가 실패로 이어지지는 않았지만, 응답 지연으로 흡수됐다.
- PostgreSQL active connection은 최대 2로 낮게 관측됐고 rollback TPS도 0이다.
- user-service와 hub-service 평균 지연은 낮아, downstream API 지연이 p95/p99 실패의 주 원인으로 보이지 않는다.

Prometheus 기준 `/internal/deliveries` status 분포에서는 `201`만 증가했고, `409`와 `502` 증가는 0이었다.

### 6. Loki 로그 수집 상태

이번 조회 범위에서 Loki의 `delivery-service` 로그 stream 카운트가 0으로 조회됐다. 따라서 이번 run에서는 Loki lock timeout 로그 수를 주요 근거로 사용하지 않는다.

대신 다음 지표로 실패 여부를 교차 확인했다.

- k6 실패 요청 0건
- Prometheus `/internal/deliveries` `409` 증가 0건
- DB/outbox 반영 10701건으로 k6 총 요청과 일치
- failed/dlq outbox 증가 0건

이 근거상 이번 100VU run에서는 lock timeout으로 인한 HTTP 실패는 발생하지 않은 것으로 본다. 다만 Loki 수집 공백이 있었으므로, 다음 run 전에는 domain-b VM의 promtail 수집 상태를 별도로 확인하는 편이 좋다.

### 7. 20VU / 50VU / 80VU / 100VU 비교

| 항목 | 20VU run02 | 50VU run03 | 80VU run04 | 100VU run05 |
| --- | ---: | ---: | ---: | ---: |
| 총 요청 수 | 7752 | 9994 | 10587 | 10701 |
| HTTP TPS | 16.15 | 20.82 | 22.06 | 22.29 |
| 실패 요청 수 | 2 | 0 | 0 | 0 |
| 실패율 | 0.02% | 0.00% | 0.00% | 0.00% |
| 평균 응답 시간 | 1.01s | 1.96s | 2.95s | 3.66s |
| p95 | 1.76s | 2.77s | 3.97s | 4.76s |
| p99 | 2.19s | 3.96s | 6.63s | 8.48s |
| 최대 응답 시간 | 2.94s | 6.47s | 10.22s | 16.27s |
| lock timeout | 2 | 0 | 0 | 0* |
| DB/outbox 반영 | 7750 | 9994 | 10587 | 10701 |
| Hikari pending 최대 | 12 | 42 | 72 | 92 |
| `/internal/deliveries` RPS 최대 | 20.77 | 21.87 | 22.70 | 23.20 |
| `/internal/deliveries` 서버 평균 지연 최대 | 1.40s | 2.55s | 3.59s | 4.47s |

`100VU run05`의 lock timeout은 Loki 로그가 아니라 k6 실패 0건, Prometheus 409 증가 0건, DB/outbox 전량 반영을 기준으로 판단했다.

80VU에서 100VU로 올렸지만 TPS는 `22.06 -> 22.29 req/s`로 거의 늘지 않았다. 반면 p95, p99, max latency, Hikari pending은 모두 증가했다.

즉 bulk upsert 이후 현재 구조는 80~100VU 구간에서 처리량은 약 22~23 req/s 부근에 수렴하고, 추가 동시성은 처리량 증가보다 대기 시간 증가로 흡수되는 흐름이다.

### 8. 기존 baseline 100VU와 비교

| 항목 | baseline run08 100VU | bulk upsert run05 100VU |
| --- | ---: | ---: |
| 총 요청 수 | 6915 | 10701 |
| HTTP TPS | 14.40 | 22.29 |
| 실패 요청 수 | 5 | 0 |
| 평균 응답 시간 | 4.67s | 3.66s |
| p95 | 6.06s | 4.76s |
| p99 | 6.55s | 8.48s |
| 최대 응답 시간 | 11.09s | 16.27s |
| lock timeout | 5 | 0* |
| Hikari pending 최대 | 83 | 92 |

같은 100VU, no-sleep, lock wait 2s 조건에서 bulk upsert 이후 처리량과 성공률은 명확히 개선됐다.

- TPS는 `14.40 -> 22.29 req/s`로 증가했다.
- 실패 요청은 `5건 -> 0건`으로 줄었다.
- 평균 응답 시간과 p95도 개선됐다.

다만 p99와 최대 응답 시간은 baseline보다 더 커졌다. 이는 bulk upsert로 lock 내부 반복 write 병목은 줄었지만, 100VU에서는 connection 대기와 transaction 체류 시간이 tail latency로 누적된 결과로 해석한다.

### 9. 결론

```text
FAIL_LATENCY_WITH_POOL_WAIT

- 총 요청 10701건
- HTTP 실패율 0.00%
- DB/outbox 최종 반영 10701건
- failed/dlq outbox 증가 0건
- Prometheus HTTP 409 증가 0건
- p95 4.76s, p99 8.48s
- p95/p99 threshold 실패
- Hikari pending 92로 connection 대기 증가
- 80VU 대비 TPS 증가는 거의 없고 tail latency만 증가
```

100VU에서도 bulk upsert 적용 후 HTTP 실패 없이 모든 요청이 DB/outbox에 반영됐다. 그러나 TPS는 80VU 대비 거의 증가하지 않았고, p95/p99와 Hikari pending은 더 악화됐다.

따라서 현재 구조의 실질 처리량 ceiling은 약 22~23 req/s 부근으로 보이며, 다음 병목은 Redis lock timeout보다 delivery-service 내부의 connection/transaction 점유 시간과 pool 대기 구간으로 보는 것이 타당하다.

다음 단계는 lock key 분산보다 먼저 transaction 범위 축소, DB connection 점유 시간 계측, Hikari pool 크기 조정 실험을 비교하는 방향이 더 적절하다. 추가로 다음 run 전에는 domain-b VM의 Loki 수집 상태를 확인해 lock timeout 로그 관측 신뢰도를 복구해야 한다.
