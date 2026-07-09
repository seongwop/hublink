# Delivery Assignment Pool Size 30 Run 04 - 100VU 결과

## 1. 테스트 목적

DB 비관적 락 적용 상태에서 delivery-service Hikari maximum pool size를 30으로 늘린 뒤, 100VU 조건에서 Hikari pending 감소가 p95 응답 시간 개선으로 이어지는지 확인했다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- delivery-service Hikari active/pending/max
- DB `for update` 대기 시간
- DB 반영량과 outbox backlog 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Size 30 Run 04 - 100VU |
| 시작 시간 | 2026-07-05 21:42:37 KST |
| 종료 시간 | 2026-07-05 21:50:41 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | delivery-service Hikari maximum pool size `20 -> 30` |
| k6 로그 | `/tmp/hublink-k6-100vu-db-pessimistic-lock-pool30-run04-20260705T124237Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 13,733 |
| HTTP TPS | 28.60 req/s |
| 성공 요청 수 | 13,733 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.85s |
| median 응답 시간 | 3.12s |
| p90 응답 시간 | 3.34s |
| p95 응답 시간 | 3.49s |
| p99 응답 시간 | 4.01s |
| 최대 응답 시간 | 6.45s |

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
| `delivery_service.p_deliveries` | 33,600 | 47,333 | 13,733 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 94,666 | 27,466 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 47,333 | 13,733 |
| `delivery_service.p_delivery_assignment_counts` row | - | 3,245 | - |

DB 반영량은 k6 성공 요청 13,733건과 일치한다. route history는 배송 1건당 2건씩 생성되어 27,466건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 | `FAILED 1,290`, `PENDING 14,143`, `PUBLISHED 31,900` |
| 약 150초 후 | `PUBLISHED 47,333` |

테스트 직후 outbox backlog가 남았지만 약 150초 뒤 모두 publish 완료됐다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari max | 30 |
| delivery-service Hikari active max | 30 |
| delivery-service Hikari pending max | 72 |
| `company_delivery` read_for_update 평균 | 10.00ms |
| `hub_delivery` read_for_update 평균 | 758.59ms |
| mixed count increase 평균 | 1.38ms |
| outbox insert 평균 | 1.67ms |
| delivery total transaction 평균 | 2.21ms |
| Redis lock timeout | 0 |

Hikari pending은 이전 DB 비관적 락 100VU보다 낮아졌지만, p95는 여전히 threshold를 초과했다.

## 6. 이전 DB 비관적 락 100VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | TPS | p95 | p99 | Hikari max | Hikari pending max | hub read_for_update 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DB pessimistic lock run04 | 13,819 | 13,819 | 0 | 28.79 | 3.71s | 4.07s | 20 | 82 | 510.24ms |
| Pool size 30 run04 | 13,733 | 13,733 | 0 | 28.60 | 3.49s | 4.01s | 30 | 72 | 758.59ms |

pool size 30은 Hikari pending을 82에서 72로 줄였고 p95/p99도 소폭 낮췄다. 다만 TPS는 거의 동일하고 p95는 여전히 3초 threshold를 초과했다.

## 7. 결론

100VU 기준에서 pool size 30은 connection pool pending과 tail latency를 약간 줄였지만, 성능 기준을 통과할 정도의 개선은 만들지 못했다.

주요 관찰은 다음과 같다.

- HTTP 실패는 0건이었다.
- p95 threshold는 실패했다.
- Hikari pending max는 82에서 72로 감소했다.
- Hikari active max는 30까지 올라 pool을 모두 사용했다.
- DB 반영량은 k6 성공 건수와 일치했다.
- outbox backlog는 약 150초 뒤 해소됐다.
- 병목 지점은 여전히 `hub_delivery read_for_update` 대기다.

따라서 pool size 30은 일부 대기열 완화 효과는 있지만, 현재 구조의 핵심 병목인 hub 집계 row lock 경합을 제거하지 못한다. pool 확장만으로는 80~100VU 구간의 p95 기준을 안정적으로 통과하기 어렵다.
