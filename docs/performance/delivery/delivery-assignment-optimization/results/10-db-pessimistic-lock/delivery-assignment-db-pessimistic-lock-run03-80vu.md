# Delivery Assignment DB Pessimistic Lock Run 03 - 80VU 결과

## 1. 테스트 목적

DB 비관적 락 구조가 20VU, 50VU에서 Redis lock timeout 없이 동작한 뒤, 동일 조건의 80VU에서도 배송 생성 요청을 안정적으로 처리하는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- Redis lock timeout 제거 상태 유지 여부
- DB `for update` 대기와 Hikari pending 증가 추세
- 배송, 배송 경로, outbox, 집계 테이블 반영 정합성
- Outbox publish backlog 발생과 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | DB Pessimistic Lock Run 03 - 80VU |
| 시작 시간 | 2026-07-05 01:22:01 KST |
| 종료 시간 | 2026-07-05 01:30:04 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | Redis 분산락 대신 집계 테이블 row `for update` 기반 배정 예약 |
| k6 로그 | 원격 파일 미생성, 세션 출력으로 최종 결과 회수 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 13,764 |
| HTTP TPS | 28.67 req/s |
| 성공 요청 수 | 13,764 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.27s |
| median 응답 시간 | 2.55s |
| p90 응답 시간 | 2.78s |
| p95 응답 시간 | 2.92s |
| p99 응답 시간 | 3.50s |
| 최대 응답 시간 | 5.16s |

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

80VU에서도 k6 실패는 0건이다. 다만 p95가 2.92초로 3초 threshold에 근접했다.

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 47,364 | 13,764 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 94,728 | 27,528 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 47,364 | 13,764 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 13,764건과 일치한다. route history는 배송 1건당 2건씩 생성되어 27,528건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `FAILED 1,340`, `PENDING 14,624`, `PUBLISHED 31,400` |
| 테스트 종료 약 1분 후 | `PENDING 9,164`, `PUBLISHED 38,200` |
| 테스트 종료 약 3분 후 | `PUBLISHED 47,364` |

80VU에서는 테스트 직후 outbox publish가 일시적으로 실패하거나 대기열에 쌓였다. 다만 재시도와 후속 처리로 약 3분 뒤에는 모두 `PUBLISHED` 상태가 됐다.

## 5. Prometheus / Grafana 지표

대시보드 확인은 Prometheus API로 같은 지표를 조회했다.

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 62 |
| `company_delivery` read_for_update 평균 | 10.40ms |
| `hub_delivery` read_for_update 평균 | 507.88ms |
| mixed count increase 평균 | 1.22ms |
| outbox insert 평균 | 1.55ms |
| delivery total transaction 평균 | 1.96ms |
| Redis lock timeout | 0 |

Redis lock timeout은 여전히 발생하지 않았다. 대신 부하가 커질수록 Hikari pending이 증가했고, 80VU에서는 최대 62까지 관측됐다.

## 6. 20VU / 50VU 대비 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending max | 주요 대기 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| DB pessimistic lock run01 - 20VU | 9,374 | 9,374 | 0 | 0.00% | 19.53 | 1.43s | 1.73s | 2 | hub read_for_update |
| DB pessimistic lock run02 - 50VU | 12,608 | 12,608 | 0 | 0.00% | 26.27 | 2.16s | 3.53s | 32 | hub read_for_update, Hikari pending |
| DB pessimistic lock run03 - 80VU | 13,764 | 13,764 | 0 | 0.00% | 28.67 | 2.92s | 3.50s | 62 | hub read_for_update, Hikari pending |

80VU에서도 실패는 0건으로 유지됐다. TPS는 50VU 대비 증가했지만 증가폭은 작아졌고, p95는 3초 threshold 바로 아래까지 상승했다.

## 7. 이전 Redis lock 80VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | 주요 실패 지점 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning run04 | 9,322 | 9,250 | 72 | 0.77% | 19.41 | 4.61s | 7.12s | Redis company lock timeout |
| Redis lock order run03 hub-first | 10,228 | 10,221 | 7 | 0.06% | 21.31 | 3.83s | 4.34s | Redis hub lock timeout |
| DB pessimistic lock run03 | 13,764 | 13,764 | 0 | 0.00% | 28.67 | 2.92s | 3.50s | 없음 |

80VU 기준에서도 DB 비관적 락 전환 후 실패율, TPS, p95, p99가 이전 Redis lock 계열보다 좋아졌다.

## 8. 결론

80VU에서도 DB 비관적 락 전환은 Redis lock timeout을 제거했고, k6 threshold를 모두 통과했다.

주요 관찰은 다음과 같다.

- k6 실패는 0건이다.
- Redis lock timeout은 0건이다.
- DB 반영량은 k6 성공 건수와 일치한다.
- p95는 2.92초로 3초 threshold에 근접했다.
- Hikari pending은 62까지 증가했다.
- outbox publish는 테스트 직후 밀렸지만 약 3분 뒤 해소됐다.

다음 단계는 100VU를 측정해 p95가 3초 threshold를 넘는지, Hikari pending과 outbox backlog가 어느 수준까지 증가하는지 확인하는 것이다.
