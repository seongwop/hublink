# Delivery Assignment Pool Tuning Run 04 - 80VU Fallback Public, Lock Wait 2s 결과

## 1. 테스트 목적

20VU, 50VU 재측정 이후 같은 fallback public / Hikari pool 설정에서 80VU를 측정했다.

확인하려는 내용은 다음과 같다.

- 50VU에서 3초를 근소하게 넘은 p95가 80VU에서 얼마나 악화되는지
- 실패가 계속 `DELIVERY_014` lock timeout으로만 발생하는지
- 80VU에서도 hub/user fallback 또는 `IllegalAccessException`이 재현되는지
- Hikari pending과 outbox backlog가 얼마나 증가하는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Tuning Run 04 - 80VU Fallback Public, Lock Wait 2s |
| 시작 시간 | 2026-07-01 01:32:25 KST |
| 종료 시간 | 2026-07-01 01:40:30 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | fallback 접근자 `public`, delivery Hikari max 20 / min idle 3 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,322 |
| HTTP TPS | 19.41 req/s |
| 성공 요청 수 | 9,250 |
| 실패 요청 수 | 72 |
| 실패율 | 0.77% |
| checks 성공률 | 99.22% |
| 평균 응답 시간 | 3.36s |
| median 응답 시간 | 3.70s |
| p90 응답 시간 | 4.36s |
| p95 응답 시간 | 4.61s |
| p99 응답 시간 | 7.12s |
| 최대 응답 시간 | 14.03s |

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

실패 72건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

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
| `p_deliveries` | 33,600 | 42,850 | 9,250 |
| `p_delivery_route_histories` | 67,200 | 85,700 | 18,500 |
| `p_delivery_outboxes` | 33,600 | 42,850 | 9,250 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 12,850 | 9,250 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 11,050 | 9,250 |

DB 반영량은 k6 성공 요청 수 9,250건과 일치한다. route history는 배송 1건당 2건씩 생성되어 18,500건 증가했다.

outbox publish는 테스트 직후 바로 따라잡지 못했다. 약 1분 뒤 조회 시점에는 `PENDING 4050건`이 남아 있었다. 50VU에서도 outbox backlog가 발생했지만, 80VU에서는 backlog가 더 크게 남았다.

## 5. 서버 로그와 지표

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 72 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 40 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 32 |

Prometheus 기준 delivery-service Hikari:

| 항목 | 값 |
| --- | ---: |
| active 최대 | 20 |
| pending 최대 | 62 |
| idle 최저 | 0 |
| max pool | 20 |

## 6. 20VU / 50VU / 80VU 비교

| 항목 | 20VU 대표값 | 50VU 대표값 | 80VU |
| --- | ---: | ---: | ---: |
| 총 요청 수 | 6,609 | 9,097 | 9,322 |
| 성공 요청 수 | 6,525 | 9,054 | 9,250 |
| 실패 요청 수 | 84 | 43 | 72 |
| 실패율 | 1.27% | 0.47% | 0.77% |
| TPS | 13.77 req/s | 18.95 req/s | 19.41 req/s |
| 평균 응답 시간 | 1.19s | 2.15s | 3.36s |
| p95 | 2.03s | 3.01s | 4.61s |
| p99 | 2.20s | 3.86s | 7.12s |
| Hikari pending 최대 | 2 | 32 | 62 |
| 502 / fallback 오류 | 0 | 0 | 0 |

80VU는 50VU 대비 TPS가 `18.95 -> 19.41 req/s`로 거의 늘지 않았다. 반면 p95는 `3.01s -> 4.61s`, p99는 `3.86s -> 7.12s`, Hikari pending은 `32 -> 62`로 악화됐다.

## 7. 분석

fallback 접근자 수정 이후 80VU에서도 502와 `IllegalAccessException`은 재현되지 않았다. 외부 hub/user 통신 실패는 이번 구간의 주된 문제가 아니다.

실패는 여전히 `DELIVERY_014` lock timeout으로만 나타났다. 다만 실패율은 0.77%로 낮고, 더 중요한 변화는 latency와 Hikari pending 증가다. 80VU에서는 더 많은 VU를 투입해도 TPS가 거의 늘지 않고, 대기 시간만 증가했다.

즉 현재 구조의 처리량 ceiling은 50VU 부근에서 이미 거의 드러난다. 80VU는 추가 동시성을 처리량으로 바꾸지 못하고 DB connection 대기와 tail latency로 흡수한다.

outbox publish도 80VU에서 더 많이 밀렸다. 동기 배송 생성 DB 반영은 성공 수와 일치하지만, 비동기 발행은 80VU 부하 이후 backlog가 남는다.

## 8. 결론

80VU에서는 실패율 자체는 낮지만 p95, p99 threshold가 모두 깨졌다. 특히 TPS가 50VU 대비 거의 증가하지 않았고 Hikari pending이 62까지 증가했으므로, 단순히 VU를 늘려도 처리량은 더 이상 의미 있게 증가하지 않는다.

다음 최적화는 외부 서비스 통신이나 fallback이 아니라 다음 두 축을 우선 비교해야 한다.

- company delivery manager 배정 lock 임계구간 축소
- Hikari pending과 outbox backlog를 줄이기 위한 트랜잭션/비동기 발행 구간 분리
