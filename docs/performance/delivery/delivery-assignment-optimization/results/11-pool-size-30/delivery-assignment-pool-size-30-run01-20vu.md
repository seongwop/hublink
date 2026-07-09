# Delivery Assignment Pool Size 30 Run 01 - 20VU 결과

## 1. 테스트 목적

DB 비관적 락 적용 상태에서 delivery-service Hikari maximum pool size를 20에서 30으로 늘린 뒤, 동일한 20VU 조건에서 응답 시간과 Hikari pending이 개선되는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- delivery-service Hikari active/pending/max
- DB `for update` 대기 시간
- DB 반영량과 outbox 상태

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Size 30 Run 01 - 20VU |
| 시작 시간 | 2026-07-05 20:33:02 KST |
| 종료 시간 | 2026-07-05 20:41:02 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | delivery-service Hikari maximum pool size `20 -> 30` |
| k6 로그 | `/tmp/hublink-k6-20vu-db-pessimistic-lock-pool30-run02-20260705T113302Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 8,570 |
| HTTP TPS | 17.85 req/s |
| 성공 요청 수 | 8,570 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 917.12ms |
| median 응답 시간 | 986.58ms |
| p90 응답 시간 | 1.53s |
| p95 응답 시간 | 1.68s |
| p99 응답 시간 | 1.96s |
| 최대 응답 시간 | 2.51s |

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
| `delivery_service.p_deliveries` | 33,600 | 42,170 | 8,570 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 84,340 | 17,140 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 42,170 | 8,570 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 8,570건과 일치한다. route history는 배송 1건당 2건씩 생성되어 17,140건 증가했다.

Outbox 상태:

| status | 건수 |
| --- | ---: |
| `PUBLISHED` | 42,170 |

테스트 종료 후 outbox backlog는 남지 않았다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari max | 30 |
| delivery-service Hikari active max | 21 |
| delivery-service Hikari pending max | 0 |
| `company_delivery` read_for_update 평균 | 14.12ms |
| `hub_delivery` read_for_update 평균 | 739.86ms |
| mixed count increase 평균 | 2.21ms |
| outbox insert 평균 | 3.04ms |
| delivery total transaction 평균 | 4.51ms |
| Redis lock timeout | 0 |

Hikari pending은 0으로 유지됐다. `hub_delivery read_for_update` 평균은 이전 DB 비관적 락 20VU와 비슷한 수준이었다.

## 6. 이전 DB 비관적 락 20VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | TPS | p95 | p99 | Hikari max | Hikari pending max | hub read_for_update 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DB pessimistic lock run01 | 9,374 | 9,374 | 0 | 19.53 | 1.43s | 1.73s | 20 | 2 | 669.56ms |
| Pool size 30 run01 | 8,570 | 8,570 | 0 | 17.85 | 1.68s | 1.96s | 30 | 0 | 739.86ms |

pool size를 30으로 늘리면서 Hikari pending은 0으로 유지됐지만, 20VU에서는 원래 pending이 크지 않았기 때문에 성능 개선은 뚜렷하게 나타나지 않았다. TPS와 p95는 이전 DB 비관적 락 20VU보다 소폭 악화됐다.

## 7. 결론

20VU 기준에서 pool size 30은 요청 실패를 만들지 않았고 Hikari pending을 0으로 유지했다.

주요 관찰은 다음과 같다.

- Hikari max는 30으로 정상 적용됐다.
- Hikari pending은 0으로 유지됐다.
- DB 반영량은 k6 성공 건수와 일치했다.
- outbox backlog는 남지 않았다.
- 병목 지점은 여전히 `hub_delivery read_for_update` 대기다.

따라서 pool size 30의 효과는 20VU만으로 판단하기 어렵다. 20VU에서는 connection pool 대기가 핵심 병목이 아니므로, 다음 단계는 50VU 이상에서 Hikari pending 감소와 p95 개선 여부를 확인하는 것이다.
