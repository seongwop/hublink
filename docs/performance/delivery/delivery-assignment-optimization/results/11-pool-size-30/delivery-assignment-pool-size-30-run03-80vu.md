# Delivery Assignment Pool Size 30 Run 03 - 80VU 결과

## 1. 테스트 목적

DB 비관적 락 적용 상태에서 delivery-service Hikari maximum pool size를 30으로 늘린 뒤, 80VU 조건에서 Hikari pending 감소가 p95 응답 시간 개선으로 이어지는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- delivery-service Hikari active/pending/max
- DB `for update` 대기 시간
- DB 반영량과 outbox backlog 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Size 30 Run 03 - 80VU |
| 시작 시간 | 2026-07-05 21:16:07 KST |
| 종료 시간 | 2026-07-05 21:24:11 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | delivery-service Hikari maximum pool size `20 -> 30` |
| k6 로그 | `/tmp/hublink-k6-80vu-db-pessimistic-lock-pool30-run03-20260705T121607Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 13,177 |
| HTTP TPS | 27.45 req/s |
| 성공 요청 수 | 13,177 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.37s |
| median 응답 시간 | 2.56s |
| p90 응답 시간 | 2.87s |
| p95 응답 시간 | 3.07s |
| p99 응답 시간 | 3.45s |
| 최대 응답 시간 | 4.80s |

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

오류 카운트:

| 항목 | 건수 |
| --- | ---: |
| HTTP 409 | 0 |
| HTTP 500 | 0 |
| k6 log error | 1 |

k6 log error 1건은 HTTP 실패가 아니라 `http_req_duration p95` threshold 초과로 인한 실행 종료 상태다.

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 46,777 | 13,177 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 93,554 | 26,354 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 46,777 | 13,177 |
| `delivery_service.p_delivery_assignment_counts` row | - | 3,230 | - |

DB 반영량은 k6 성공 요청 13,177건과 일치한다. route history는 배송 1건당 2건씩 생성되어 26,354건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `FAILED 360`, `PENDING 13,417`, `PUBLISHED 33,000` |
| 약 150초 후 | `PUBLISHED 46,777` |

테스트 직후 outbox backlog가 남았지만 약 150초 뒤 모두 publish 완료됐다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari max | 30 |
| delivery-service Hikari active max | 30 |
| delivery-service Hikari pending max | 52 |
| `company_delivery` read_for_update 평균 | 10.52ms |
| `hub_delivery` read_for_update 평균 | 806.57ms |
| mixed count increase 평균 | 1.40ms |
| outbox insert 평균 | 1.82ms |
| delivery total transaction 평균 | 2.35ms |
| Redis lock timeout | 0 |

Hikari pending은 이전 DB 비관적 락 80VU보다 낮아졌지만, `hub_delivery read_for_update` 평균은 더 높게 관측됐다.

## 6. 이전 DB 비관적 락 80VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | TPS | p95 | p99 | Hikari max | Hikari pending max | hub read_for_update 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DB pessimistic lock run03 | 13,764 | 13,764 | 0 | 28.67 | 2.92s | 3.50s | 20 | 62 | 507.88ms |
| Pool size 30 run03 | 13,177 | 13,177 | 0 | 27.45 | 3.07s | 3.45s | 30 | 52 | 806.57ms |

pool size 30은 Hikari pending을 62에서 52로 줄였지만, TPS와 p95는 개선하지 못했다. p99는 소폭 낮아졌으나 p95가 threshold를 초과했기 때문에 전체적으로는 개선으로 보기 어렵다.

## 7. 결론

80VU 기준에서 pool size 30은 connection pool 대기를 일부 줄였지만, API 응답 시간 개선으로 이어지지 않았다.

주요 관찰은 다음과 같다.

- HTTP 실패는 0건이었다.
- p95 threshold는 실패했다.
- Hikari pending max는 62에서 52로 감소했다.
- Hikari active max는 30까지 올라 pool을 모두 사용했다.
- DB 반영량은 k6 성공 건수와 일치했다.
- outbox backlog는 약 150초 뒤 해소됐다.
- 병목 지점은 여전히 `hub_delivery read_for_update` 대기다.

따라서 DB 커넥션 풀을 늘리더라도 같은 hub 집계 row를 `for update`로 직렬화하는 구조에서는 처리량과 p95가 자동으로 개선되지 않는다. pool 확장은 대기열 일부를 줄일 수 있지만, lock 경합이 더 안쪽 병목으로 남으면 성능 개선 폭은 제한된다.
