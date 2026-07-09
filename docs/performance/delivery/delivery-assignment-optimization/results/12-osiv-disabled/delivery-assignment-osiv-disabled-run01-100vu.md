# Delivery Assignment OSIV Disabled Run 01 - 100VU 결과

## 1. 테스트 목적

delivery-service, hub-service, company-service의 OSIV를 비활성화한 뒤 기존 `pool-size-30` 조건과 동일한 100VU 부하에서 배송 생성 성능과 오류 여부를 확인한다.

확인 대상은 다음과 같다.

- k6 요청 성공률과 응답 시간
- OSIV 비활성화 이후 `LazyInitializationException` 발생 여부
- delivery-service Hikari active/pending/max
- DB `for update` 대기 시간
- DB 반영량과 outbox backlog 해소 여부

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | OSIV Disabled Run 01 - 100VU |
| 시작 시간 | 2026-07-09 23:44:18 KST |
| 종료 시간 | 2026-07-09 23:52:29 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | `spring.jpa.open-in-view=false` |
| k6 로그 | `/tmp/hublink-k6-100vu-osiv-disabled-run01-20260709T144418Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 12,938 |
| HTTP TPS | 26.95 req/s |
| 성공 요청 수 | 12,938 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 3.02s |
| median 응답 시간 | 3.05s |
| p90 응답 시간 | 4.27s |
| p95 응답 시간 | 4.75s |
| p99 응답 시간 | 6.35s |
| 최대 응답 시간 | 11.61s |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✗ p(99)<6000

http_req_failed
✓ rate<0.10
```

오류 카운트:

| 항목 | 건수 |
| --- | ---: |
| HTTP 4xx/5xx | 0 |
| k6 script error | 0 |
| k6 threshold error | 1 |
| `LazyInitializationException` | 0 |

k6 실행은 HTTP 실패 없이 완료됐지만, p95와 p99 threshold를 초과해 종료 상태는 실패로 기록됐다.

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 46,538 | 12,938 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 93,076 | 25,876 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 46,538 | 12,938 |
| `delivery_service.p_delivery_assignment_counts` row | - | 3,100 | - |

DB 반영량은 k6 성공 요청 12,938건과 일치한다. route history는 배송 1건당 2건씩 생성되어 25,876건 증가했다.

Outbox 상태:

| 시점 | 상태 |
| --- | --- |
| 테스트 직후 조회 | `PENDING 4,638`, `PUBLISHED 41,900` |
| 후속 조회 | `PENDING 2,757`, `PUBLISHED 43,781` |
| 추가 90초 후 | `PUBLISHED 46,538` |

테스트 직후 outbox backlog는 남았지만 추가 대기 후 모두 publish 완료됐다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service Hikari max | 30 |
| delivery-service Hikari active max | 30 |
| delivery-service Hikari pending max | 72 |
| `company_delivery` read_for_update 평균 | 10.68ms |
| `hub_delivery` read_for_update 평균 | 1,003.90ms |
| mixed count increase 평균 | 2.09ms |
| outbox insert 평균 | 2.22ms |
| delivery total transaction 평균 | 3.34ms |
| Redis lock timeout | 0 |

delivery-service 로그에서 OSIV 비활성화로 인한 lazy loading 예외는 관찰되지 않았다. 테스트 중 Zipkin span 전송 WARN은 있었지만 배송 생성 실패나 DB 반영 불일치로 이어지지는 않았다.

## 6. Pool Size 30 100VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | TPS | p95 | p99 | Hikari pending max | hub read_for_update 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Pool size 30 run04 | 13,733 | 13,733 | 0 | 28.60 | 3.49s | 4.01s | 72 | 758.59ms |
| OSIV disabled run01 | 12,938 | 12,938 | 0 | 26.95 | 4.75s | 6.35s | 72 | 1,003.90ms |

OSIV 비활성화 후에도 기능 오류는 없었지만 100VU 단일 측정에서는 처리량과 tail latency가 이전 pool-size-30 결과보다 나빠졌다. Hikari pending max는 동일하게 72까지 올라갔고, `hub_delivery read_for_update` 평균이 더 커졌다.

## 7. 결론

OSIV 비활성화는 배송 생성 경로에서 즉각적인 lazy loading 오류를 만들지 않았다. DB 반영량도 k6 성공 건수와 일치했고, outbox backlog도 후속 조회에서 모두 해소됐다.

다만 성능 개선 효과는 확인되지 않았다. 오히려 100VU 단일 측정 기준으로 TPS는 28.60에서 26.95로 낮아졌고 p95는 3.49s에서 4.75s로 악화됐다. Hikari pending max가 기존과 동일한 72였고, `hub_delivery read_for_update` 평균이 1초 수준까지 증가한 점을 보면 현재 100VU 병목은 OSIV보다 DB row lock 대기와 connection pending 쪽으로 유지된다고 판단한다.
