# Delivery Assignment DB Pessimistic Lock Run 04 - 100VU 결과

## 1. 테스트 목적

DB 비관적 락 구조가 80VU까지 요청 실패 없이 동작한 뒤, 동일 조건의 100VU에서 응답 시간 threshold를 유지할 수 있는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- 100VU에서 p95 3초 threshold 유지 여부
- Redis lock timeout 제거 상태 유지 여부
- DB `for update` 대기와 Hikari pending 증가 추세
- Outbox publish backlog 발생과 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | DB Pessimistic Lock Run 04 - 100VU |
| 시작 시간 | 2026-07-05 01:42:32 KST |
| 종료 시간 | 2026-07-05 01:50:36 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | Redis 분산락 대신 집계 테이블 row `for update` 기반 배정 예약 |
| k6 로그 | `/tmp/hublink-k6-100vu-db-pessimistic-lock-run04-20260704T164232Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 13,819 |
| HTTP TPS | 28.79 req/s |
| 성공 요청 수 | 13,819 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.83s |
| median 응답 시간 | 3.27s |
| p90 응답 시간 | 3.55s |
| p95 응답 시간 | 3.71s |
| p99 응답 시간 | 4.07s |
| 최대 응답 시간 | 6.90s |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

HTTP 요청 실패는 0건이지만, p95가 3.71초로 `p(95)<3000` threshold를 초과했다.

오류 카운트:

| 항목 | 건수 |
| --- | ---: |
| HTTP 409 | 0 |
| HTTP 500 | 0 |
| k6 threshold crossed | 1 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 47,419 | 13,819 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 94,838 | 27,638 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 47,419 | 13,819 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 13,819건과 일치한다. route history는 배송 1건당 2건씩 생성되어 27,638건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `FAILED 2,140`, `PENDING 15,179`, `PUBLISHED 30,100` |
| 테스트 종료 약 1분 후 | `PENDING 10,619`, `PUBLISHED 36,800` |
| 테스트 종료 약 3분 후 | `PUBLISHED 47,419` |

100VU에서도 테스트 직후 outbox publish가 일시적으로 실패하거나 대기열에 쌓였다. 다만 약 3분 뒤에는 모두 `PUBLISHED` 상태가 됐다.

## 5. Prometheus / Grafana 지표

대시보드 확인은 Prometheus API로 같은 지표를 조회했다.

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 82 |
| `company_delivery` read_for_update 평균 | 10.37ms |
| `hub_delivery` read_for_update 평균 | 510.24ms |
| mixed count increase 평균 | 1.20ms |
| outbox insert 평균 | 1.57ms |
| delivery total transaction 평균 | 1.97ms |
| Redis lock timeout | 0 |

Redis lock timeout은 여전히 발생하지 않았다. 100VU에서는 Hikari pending이 최대 82까지 증가했고, 요청 처리량은 80VU와 거의 같은 수준에서 머물렀다.

## 6. DB 비관적 락 단계 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending max | threshold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 20VU | 9,374 | 9,374 | 0 | 0.00% | 19.53 | 1.43s | 1.73s | 2 | 통과 |
| 50VU | 12,608 | 12,608 | 0 | 0.00% | 26.27 | 2.16s | 3.53s | 32 | 통과 |
| 80VU | 13,764 | 13,764 | 0 | 0.00% | 28.67 | 2.92s | 3.50s | 62 | 통과 |
| 100VU | 13,819 | 13,819 | 0 | 0.00% | 28.79 | 3.71s | 4.07s | 82 | p95 실패 |

100VU에서는 요청 실패가 없지만 p95 threshold를 넘었다. 80VU에서 100VU로 올려도 TPS는 28.67에서 28.79로 거의 증가하지 않았고, p95만 2.92초에서 3.71초로 증가했다.

## 7. 이전 Redis lock 100VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | 주요 실패 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning run05 | 9,650 | 9,620 | 30 | 0.31% | 20.05 | 5.54s | 8.54s | Redis company lock timeout |
| Redis lock order run04 hub-first | 10,494 | 10,478 | 16 | 0.15% | 21.86 | 4.99s | 5.91s | Redis hub lock timeout |
| Redis lock order run08 company-first | 9,423 | 9,385 | 38 | 0.40% | 19.63 | 5.34s | 5.72s | Redis company lock timeout |
| DB pessimistic lock run04 | 13,819 | 13,819 | 0 | 0.00% | 28.79 | 3.71s | 4.07s | 없음 |

100VU 기준에서도 DB 비관적 락은 이전 Redis lock 계열보다 성공률과 처리량이 좋다. 다만 p95 3초 목표는 달성하지 못했다.

## 8. 결론

100VU에서는 DB 비관적 락 전환 후에도 HTTP 실패와 Redis lock timeout은 발생하지 않았다. 그러나 p95가 3.71초로 상승해 k6 응답 시간 threshold를 넘었다.

주요 관찰은 다음과 같다.

- k6 HTTP 실패는 0건이다.
- Redis lock timeout은 0건이다.
- DB 반영량은 k6 성공 건수와 일치한다.
- 80VU 대비 TPS 증가는 거의 없고 p95만 크게 증가했다.
- Hikari pending은 82까지 증가했다.
- outbox publish는 테스트 직후 크게 밀렸지만 약 3분 뒤 해소됐다.

현재 구조에서 실질적인 안정 구간은 80VU까지로 보인다. 100VU에서는 요청 성공률은 유지되지만 p95 목표를 넘어서므로, 다음 최적화 후보는 connection pool 대기와 `hub_delivery for update` 경합을 줄이는 방향이다.
