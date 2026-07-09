# Delivery Assignment Pool Size 30 Run 02 - 50VU 결과

## 1. 테스트 목적

DB 비관적 락 적용 상태에서 delivery-service Hikari maximum pool size를 30으로 늘린 뒤, 50VU 조건에서 Hikari pending과 응답 시간이 개선되는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- delivery-service Hikari active/pending/max
- DB `for update` 대기 시간
- DB 반영량과 outbox backlog 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Size 30 Run 02 - 50VU |
| 시작 시간 | 2026-07-05 20:50:38 KST |
| 종료 시간 | 2026-07-05 20:58:38 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | delivery-service Hikari maximum pool size `20 -> 30` |
| k6 로그 | `/tmp/hublink-k6-50vu-db-pessimistic-lock-pool30-run02-20260705T115038Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 11,815 |
| HTTP TPS | 24.61 req/s |
| 성공 요청 수 | 11,815 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 1.65s |
| median 응답 시간 | 1.66s |
| p90 응답 시간 | 2.03s |
| p95 응답 시간 | 2.42s |
| p99 응답 시간 | 3.42s |
| 최대 응답 시간 | 5.24s |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✓ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

오류 카운트:

| 항목 | 건수 |
| --- | ---: |
| HTTP 409 | 0 |
| HTTP 500 | 0 |
| k6 log error | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 45,415 | 11,815 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 90,830 | 23,630 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 45,415 | 11,815 |
| `delivery_service.p_delivery_assignment_counts` row | - | 3,210 | - |

DB 반영량은 k6 성공 요청 11,815건과 일치한다. route history는 배송 1건당 2건씩 생성되어 23,630건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `PENDING 9,615`, `PUBLISHED 35,800` |
| 약 90초 후 | `PUBLISHED 45,415` |

테스트 직후 outbox backlog가 남았지만 약 90초 뒤 모두 publish 완료됐다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari max | 30 |
| delivery-service Hikari active max | 30 |
| delivery-service Hikari pending max | 22 |
| `company_delivery` read_for_update 평균 | 11.67ms |
| `hub_delivery` read_for_update 평균 | 914.01ms |
| mixed count increase 평균 | 1.57ms |
| outbox insert 평균 | 2.12ms |
| delivery total transaction 평균 | 2.81ms |
| Redis lock timeout | 0 |

Hikari pending은 이전 DB 비관적 락 50VU의 32보다 낮아졌다. 다만 `hub_delivery read_for_update` 평균은 914.01ms로 상승했고, p95와 TPS는 개선되지 않았다.

## 6. 이전 DB 비관적 락 50VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | TPS | p95 | p99 | Hikari max | Hikari pending max | hub read_for_update 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DB pessimistic lock run02 | 12,608 | 12,608 | 0 | 26.27 | 2.16s | 3.53s | 20 | 32 | 550.94ms |
| Pool size 30 run02 | 11,815 | 11,815 | 0 | 24.61 | 2.42s | 3.42s | 30 | 22 | 914.01ms |

pool size 30은 Hikari pending을 줄였지만, TPS와 p95를 개선하지 못했다. p99는 소폭 개선됐지만 p95와 처리량이 악화되어 전체적인 개선으로 보기는 어렵다.

## 7. 결론

50VU 기준에서 pool size 30은 connection pool pending을 줄이는 효과는 있었지만, API 응답 시간과 처리량 개선으로 이어지지는 않았다.

주요 관찰은 다음과 같다.

- 요청 실패는 0건이었다.
- Hikari pending max는 32에서 22로 감소했다.
- Hikari active max는 30까지 올라 pool을 모두 사용했다.
- DB 반영량은 k6 성공 건수와 일치했다.
- outbox backlog는 약 90초 뒤 해소됐다.
- 병목 지점은 connection pool 대기보다 `hub_delivery read_for_update` 대기로 유지됐다.

따라서 pool size 증가는 단독 개선안으로는 효과가 제한적이다. 80VU 이상에서 pending 감소가 tail latency에 영향을 주는지 추가 확인할 수는 있지만, 현재까지는 hub row lock 경합이 더 우선적인 병목으로 보인다.
