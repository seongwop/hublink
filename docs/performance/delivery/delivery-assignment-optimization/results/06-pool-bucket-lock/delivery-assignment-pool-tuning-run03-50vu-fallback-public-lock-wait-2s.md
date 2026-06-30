# Delivery Assignment Pool Tuning Run 03 - 50VU Fallback Public, Lock Wait 2s 결과

## 1. 테스트 목적

Run 02와 같은 fallback public / Hikari pool 설정에서 VU를 50으로 올려 측정했다.

직전 50VU 측정은 실패 66건, p95 3.02s였다. 20VU에서 변동성이 확인됐기 때문에 50VU도 한 번 더 반복 측정했고, 이 문서는 반복 측정 결과를 대표값으로 기록한다.

확인하려는 내용은 다음과 같다.

- 50VU에서 `DELIVERY_014` lock timeout이 어느 정도 재현되는지
- 502, hub/user fallback, `IllegalAccessException`이 재현되는지
- Hikari pending과 p95 latency가 어느 정도까지 증가하는지
- outbox 발행이 부하 이후 정상적으로 따라잡는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Tuning Run 03 - 50VU Fallback Public, Lock Wait 2s |
| 시작 시간 | 2026-06-30 23:59:16 KST |
| 종료 시간 | 2026-07-01 00:07:20 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | fallback 접근자 `public`, delivery Hikari max 20 / min idle 3 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,097 |
| HTTP TPS | 18.95 req/s |
| 성공 요청 수 | 9,054 |
| 실패 요청 수 | 43 |
| 실패율 | 0.47% |
| checks 성공률 | 99.52% |
| 평균 응답 시간 | 2.15s |
| median 응답 시간 | 2.33s |
| p90 응답 시간 | 2.82s |
| p95 응답 시간 | 3.01s |
| p99 응답 시간 | 3.86s |
| 최대 응답 시간 | 5.88s |

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

실패 43건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

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
| `p_deliveries` | 33,600 | 42,654 | 9,054 |
| `p_delivery_route_histories` | 67,200 | 85,308 | 18,108 |
| `p_delivery_outboxes` | 33,600 | 42,654 | 9,054 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 12,654 | 9,054 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 10,854 | 9,054 |

DB 반영량은 k6 성공 요청 수 9,054건과 일치한다. route history는 배송 1건당 2건씩 생성되어 18,108건 증가했다.

테스트 직후에는 outbox publish가 바로 따라잡지 못했다. 조회 시점에는 `PENDING 1740건`이 남아 있었다. 따라서 동기 배송 생성과 DB 반영은 정상이나, 50VU 구간에서는 outbox 발행이 일시적으로 뒤처진다.

## 5. 서버 로그와 지표

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 43 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 14 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 29 |

Prometheus 기준 delivery-service Hikari:

| 항목 | 값 |
| --- | ---: |
| active 최대 | 20 |
| pending 최대 | 32 |
| idle 최저 | 0 |
| max pool | 20 |

## 6. 반복 측정 비교

같은 50VU 조건에서 두 번 측정한 결과는 다음과 같다.

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 최초 50VU 측정 | 9,230 | 9,164 | 66 | 0.71% | 19.23 | 3.02s | 3.83s | 32 |
| 50VU 반복 측정 | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | 32 |

50VU는 반복 측정에서도 거의 같은 결과가 나왔다. 실패율은 0.5~0.7% 수준이고, p95는 3초 threshold를 아주 살짝 넘는다. Hikari pending 최대도 두 번 모두 32로 같았다.

## 7. Run 02와 비교

| 항목 | 20VU Run 02 대표값 | 50VU Run 03 대표값 |
| --- | ---: | ---: |
| 총 요청 수 | 6,609 | 9,097 |
| 성공 요청 수 | 6,525 | 9,054 |
| 실패 요청 수 | 84 | 43 |
| 실패율 | 1.27% | 0.47% |
| TPS | 13.77 req/s | 18.95 req/s |
| p95 | 2.03s | 3.01s |
| p99 | 2.20s | 3.86s |
| Hikari pending 최대 | 2 | 32 |
| 502 / fallback 오류 | 0 | 0 |

50VU는 20VU보다 높은 TPS를 냈고 실패율은 낮았다. 대신 Hikari pending과 tail latency가 더 커졌다. 즉 실패가 latency와 connection 대기로 흡수되는 흐름이 있다.

## 8. 분석

fallback 접근자 수정 이후 50VU에서도 502와 `IllegalAccessException`은 재현되지 않았다. 이번 run에서 외부 hub/user 통신 실패는 주된 문제가 아니었다.

실패 원인은 여전히 `DELIVERY_014` lock timeout이다. 실패 key는 두 company delivery lock에만 집중됐다. 이는 현재 distributed 입력이 receiver company 기준으로는 18개로 나뉘지만, 실제 company delivery manager 배정 lock은 권역별 company delivery key 2개로 수렴한다는 점을 보여준다.

Hikari pool을 20으로 늘린 상태에서도 active는 상한까지 사용됐고 pending은 32까지 쌓였다. 따라서 50VU에서는 lock wait와 DB connection 대기가 함께 tail latency를 키운다.

## 9. 결론

50VU 대표 재측정 결과는 실패율 0.47%, TPS 18.95 req/s, p95 3.01s였다. 실패율은 낮지만 p95 threshold는 반복해서 근소하게 깨진다.

현재 반복 재현되는 문제는 외부 서비스 통신이나 fallback이 아니라 company delivery manager 배정 lock timeout과 Hikari pending이다. 다음 최적화는 lock 임계구간 축소 또는 company delivery lock key 분산을 우선 비교한다.
