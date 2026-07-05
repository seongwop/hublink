# Delivery Assignment DB Pessimistic Lock Run 01 - 20VU 결과

## 1. 테스트 목적

Redis 분산락 기반 배송 기사 배정 로직을 DB 비관적 락 기반으로 전환한 뒤, 동일한 20VU 조건에서 Redis lock timeout이 제거되는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- Redis lock timeout 제거 여부
- DB `for update` 대기 지점
- 배송, 배송 경로, outbox, 집계 테이블 반영 정합성
- Outbox publish backlog 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | DB Pessimistic Lock Run 01 - 20VU |
| 시작 시간 | 2026-07-05 00:29:10 KST |
| 종료 시간 | 2026-07-05 00:37:10 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | Redis 분산락 대신 집계 테이블 row `for update` 기반 배정 예약 |
| k6 로그 | `/tmp/hublink-k6-20vu-db-pessimistic-lock-rerun-20260705T002905KST.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,374 |
| HTTP TPS | 19.53 req/s |
| 성공 요청 수 | 9,374 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 838.60ms |
| median 응답 시간 | 847.80ms |
| p90 응답 시간 | 1.28s |
| p95 응답 시간 | 1.43s |
| p99 응답 시간 | 1.73s |
| 최대 응답 시간 | 2.84s |

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
| `delivery_service.p_deliveries` | 33,600 | 42,974 | 9,374 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 85,948 | 18,748 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 42,974 | 9,374 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 9,374건과 일치한다. route history는 배송 1건당 2건씩 생성되어 18,748건 증가했다.

Outbox 상태:

| status | 건수 |
| --- | ---: |
| `PUBLISHED` | 42,974 |

테스트 종료 후 outbox backlog는 남지 않았다.

## 5. Prometheus / Grafana 지표

대시보드 확인은 Prometheus API로 같은 지표를 조회했다.

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 2 |
| `company_delivery` read_for_update 평균 | 14.17ms |
| `hub_delivery` read_for_update 평균 | 669.56ms |
| mixed count increase 평균 | 1.88ms |
| outbox insert 평균 | 2.62ms |
| delivery total transaction 평균 | 3.76ms |
| Redis lock timeout | 0 |

Redis 분산락을 사용하지 않으므로 `delivery_assignment_lock_timeout_total`은 더 이상 증가하지 않았다.

대신 병목 지점은 `hub_delivery` 집계 row를 `for update`로 읽는 구간으로 이동했다. 현재 입력 조건에서는 출발 허브가 하나로 수렴하므로 hub 집계 row가 공통 경합 지점이 된다.

## 6. 이전 단계 20VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | 주요 실패 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning run02 | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s | Redis company lock timeout |
| Redis lock order run01 hub-first | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | - | Redis hub lock timeout |
| Redis lock scope run02 | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | 2.36s | Redis company lock timeout |
| Post optimization run01 | 6,681 | 6,505 | 176 | 2.63% | 13.92 | 2.07s | 2.17s | Redis hub lock timeout |
| DB pessimistic lock run01 | 9,374 | 9,374 | 0 | 0.00% | 19.53 | 1.43s | 1.73s | 없음 |

20VU 기준에서는 DB 비관적 락 전환 후 실패가 0건으로 떨어졌고, TPS와 p95도 이전 Redis lock 계열보다 개선됐다.

다만 이것은 20VU 결과만 기준으로 한 판단이다. 50VU 이상에서는 `for update` 대기가 Hikari pending, DB lock wait와 함께 커질 수 있으므로 같은 조건으로 50VU, 80VU, 100VU를 순서대로 확인해야 한다.

## 7. 결론

20VU에서는 DB 비관적 락 전환이 Redis lock timeout 문제를 제거했고, 요청 처리량과 응답 시간도 함께 개선됐다.

주요 관찰은 다음과 같다.

- Redis lock timeout은 0건으로 제거됐다.
- k6 실패는 0건이다.
- DB 반영량은 k6 성공 건수와 일치한다.
- Outbox backlog는 남지 않았다.
- 새 경합 지점은 `hub_delivery read_for_update`로 이동했다.

다음 단계는 동일 조건에서 50VU를 측정해 DB row lock 기반 구조가 중간 부하에서도 안정적인지 확인하는 것이다.
