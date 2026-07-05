# Delivery Assignment DB Pessimistic Lock Run 02 - 50VU 결과

## 1. 테스트 목적

20VU에서 Redis lock timeout이 제거된 DB 비관적 락 구조를 동일 조건의 50VU로 확장해 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- Redis lock timeout 제거 상태 유지 여부
- DB `for update` 대기 지점과 Hikari pending 증가 여부
- 배송, 배송 경로, outbox, 집계 테이블 반영 정합성
- Outbox publish backlog 발생과 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | DB Pessimistic Lock Run 02 - 50VU |
| 시작 시간 | 2026-07-05 00:48:28 KST |
| 종료 시간 | 2026-07-05 00:56:28 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | Redis 분산락 대신 집계 테이블 row `for update` 기반 배정 예약 |
| k6 로그 | `/tmp/hublink-k6-50vu-db-pessimistic-lock-run02-20260705T0050KST.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 12,608 |
| HTTP TPS | 26.27 req/s |
| 성공 요청 수 | 12,608 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 1.55s |
| median 응답 시간 | 1.64s |
| p90 응답 시간 | 1.96s |
| p95 응답 시간 | 2.16s |
| p99 응답 시간 | 3.53s |
| 최대 응답 시간 | 6.77s |

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
| `delivery_service.p_deliveries` | 33,600 | 46,208 | 12,608 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 92,416 | 25,216 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 46,208 | 12,608 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 12,608건과 일치한다. route history는 배송 1건당 2건씩 생성되어 25,216건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `PENDING 12,608`, `PUBLISHED 33,600` |
| 테스트 종료 약 1분 후 | `PENDING 6,108`, `PUBLISHED 40,100` |
| 테스트 종료 약 3분 후 | `PUBLISHED 46,208` |

50VU에서는 배송 생성 트랜잭션은 전부 성공했지만, outbox publisher가 테스트 직후에는 생성량을 즉시 따라잡지 못했다. 다만 약 3분 뒤에는 backlog가 모두 해소됐다.

## 5. Prometheus / Grafana 지표

대시보드 확인은 Prometheus API로 같은 지표를 조회했다.

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 32 |
| `company_delivery` read_for_update 평균 | 11.22ms |
| `hub_delivery` read_for_update 평균 | 550.94ms |
| mixed count increase 평균 | 1.33ms |
| outbox insert 평균 | 1.81ms |
| delivery total transaction 평균 | 2.31ms |
| Redis lock timeout | 0 |

50VU에서는 Hikari active가 max 20까지 도달했고 pending도 최대 32까지 증가했다. Redis lock timeout은 여전히 발생하지 않았지만, DB row lock 대기와 connection pool 대기가 함께 보이기 시작했다.

## 6. 20VU 대비 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending max | 주요 대기 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| DB pessimistic lock run01 - 20VU | 9,374 | 9,374 | 0 | 0.00% | 19.53 | 1.43s | 1.73s | 2 | hub read_for_update |
| DB pessimistic lock run02 - 50VU | 12,608 | 12,608 | 0 | 0.00% | 26.27 | 2.16s | 3.53s | 32 | hub read_for_update, Hikari pending |

50VU에서도 실패는 0건으로 유지됐다. TPS는 20VU 대비 증가했지만, p95와 p99는 상승했고 Hikari pending도 2에서 32로 증가했다.

## 7. 이전 Redis lock 50VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | 주요 실패 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning run03 | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | Redis company lock timeout |
| Redis lock order run02 hub-first | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | Redis hub lock timeout |
| DB pessimistic lock run02 | 12,608 | 12,608 | 0 | 0.00% | 26.27 | 2.16s | 3.53s | 없음 |

50VU 기준에서는 DB 비관적 락 전환 후 실패율, TPS, p95가 모두 이전 Redis lock 계열보다 좋아졌다. p99는 hub-first Redis lock run02보다 높지만 threshold 6초 안에는 들어왔다.

## 8. 결론

50VU에서도 DB 비관적 락 전환은 Redis lock timeout을 제거했고, 요청 실패 없이 threshold를 통과했다.

주요 관찰은 다음과 같다.

- k6 실패는 0건이다.
- Redis lock timeout은 0건이다.
- DB 반영량은 k6 성공 건수와 일치한다.
- Hikari pending은 32까지 증가했다.
- outbox backlog는 테스트 직후 발생했지만 약 3분 뒤 해소됐다.
- 주요 대기 지점은 `hub_delivery read_for_update`와 connection pool pending으로 이동했다.

다음 단계는 동일 조건에서 80VU를 측정해 p95/p99와 Hikari pending이 어디까지 증가하는지 확인하는 것이다.
