# Delivery Assignment Pool Tuning Run 05 - 100VU Fallback Public, Lock Wait 2s 결과

## 1. 테스트 목적

20VU, 50VU, 80VU 재측정 이후 같은 fallback public / Hikari pool 설정에서 100VU를 측정했다.

확인하려는 내용은 다음과 같다.

- 80VU 이후에도 TPS가 의미 있게 증가하는지
- 실패가 계속 `DELIVERY_014` lock timeout으로만 발생하는지
- 100VU에서도 hub/user fallback 또는 `IllegalAccessException`이 재현되는지
- Hikari pending과 outbox backlog가 80VU보다 더 악화되는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Tuning Run 05 - 100VU Fallback Public, Lock Wait 2s |
| 시작 시간 | 2026-07-01 01:54:48 KST |
| 종료 시간 | 2026-07-01 02:02:53 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | fallback 접근자 `public`, delivery Hikari max 20 / min idle 3 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,626 |
| HTTP TPS | 20.05 req/s |
| 성공 요청 수 | 9,596 |
| 실패 요청 수 | 30 |
| 실패율 | 0.31% |
| checks 성공률 | 99.68% |
| 평균 응답 시간 | 4.07s |
| median 응답 시간 | 4.62s |
| p90 응답 시간 | 5.30s |
| p95 응답 시간 | 5.54s |
| p99 응답 시간 | 8.54s |
| 최대 응답 시간 | 9.98s |

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

실패 30건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
```

다음 오류는 관측되지 않았다.

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_011` user-service unavailable | 0 |
| `DELIVERY_013` hub-service unavailable | 0 |
| `IllegalAccessException` | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33600`, `p_delivery_route_histories 67200`, `p_delivery_outboxes 33600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 43,196 | 9,596 |
| `p_delivery_route_histories` | 67,200 | 86,392 | 19,192 |
| `p_delivery_outboxes` | 33,600 | 43,196 | 9,596 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 13,196 | 9,596 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 11,396 | 9,596 |

DB 반영량은 k6 성공 요청 수 9,596건과 일치한다. route history는 배송 1건당 2건씩 생성되어 19,192건 증가했다.

outbox publish는 테스트 직후 조회 시점에 `PENDING 4696건`이 남아 있었다. 약 1분 뒤 재조회 시점에는 전체 43,196건이 모두 `PUBLISHED` 상태로 따라잡았다.

## 5. 서버 로그와 지표

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 30 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 18 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 12 |

Prometheus 기준 delivery-service Hikari:

| 항목 | 값 |
| --- | ---: |
| active 최대 | 20 |
| pending 최대 | 82 |
| idle 최저 | 0 |
| max pool | 20 |

## 6. 20VU / 50VU / 80VU / 100VU 비교

| 항목 | 20VU 대표값 | 50VU 대표값 | 80VU | 100VU |
| --- | ---: | ---: | ---: | ---: |
| 총 요청 수 | 6,609 | 9,097 | 9,322 | 9,626 |
| 성공 요청 수 | 6,525 | 9,054 | 9,250 | 9,596 |
| 실패 요청 수 | 84 | 43 | 72 | 30 |
| 실패율 | 1.27% | 0.47% | 0.77% | 0.31% |
| TPS | 13.77 req/s | 18.95 req/s | 19.41 req/s | 20.05 req/s |
| 평균 응답 시간 | 1.19s | 2.15s | 3.36s | 4.07s |
| p95 | 2.03s | 3.01s | 4.61s | 5.54s |
| p99 | 2.20s | 3.86s | 7.12s | 8.54s |
| Hikari pending 최대 | 2 | 32 | 62 | 82 |
| 502 / fallback 오류 | 0 | 0 | 0 | 0 |

100VU는 80VU 대비 TPS가 `19.41 -> 20.05 req/s`로 소폭 증가했다. 반면 p95는 `4.61s -> 5.54s`, p99는 `7.12s -> 8.54s`, Hikari pending은 `62 -> 82`로 악화됐다.

## 7. 분석

fallback 접근자 수정 이후 100VU에서도 502와 `IllegalAccessException`은 재현되지 않았다. 20VU, 50VU, 80VU와 마찬가지로 hub/user 통신 실패는 이번 구간의 주된 문제가 아니다.

실패는 모두 `DELIVERY_014` lock timeout이었고, 실패 건수는 80VU보다 오히려 낮았다. 다만 이것을 100VU가 더 안정적이라고 해석하면 안 된다. 100VU에서는 요청이 더 많이 실패로 떨어지기보다 connection pending과 응답 지연으로 흡수됐다.

50VU 이후부터는 VU를 늘려도 처리량 증가가 작다. 50VU에서 100VU로 동시성을 2배로 늘렸지만 TPS는 `18.95 -> 20.05 req/s`로 약 5.8%만 증가했다. 대신 p95는 `3.01s -> 5.54s`, p99는 `3.86s -> 8.54s`, Hikari pending은 `32 -> 82`로 커졌다.

outbox는 최종적으로 모두 발행됐지만, 100VU 직후에는 backlog가 4,696건 남았다. 동기 트랜잭션 반영은 성공 수와 맞지만, 부하가 커질수록 비동기 발행이 뒤따라오는 시간이 길어진다.

## 8. 결론

100VU는 실패율 자체는 0.31%로 낮지만 p95, p99 threshold가 모두 깨졌다. TPS도 80VU 대비 0.64 req/s만 증가해 처리량이 거의 포화된 상태다.

따라서 현재 구조에서 다음 최적화는 단순히 pool을 더 키우는 방향보다 다음 두 축을 우선 비교하는 것이 좋다.

- company delivery manager 배정 lock 임계구간 축소
- Hikari pending과 outbox backlog를 줄이기 위한 트랜잭션/비동기 발행 구간 분리
