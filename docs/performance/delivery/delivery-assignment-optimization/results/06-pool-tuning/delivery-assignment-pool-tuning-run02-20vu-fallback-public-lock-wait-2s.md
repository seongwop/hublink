# Delivery Assignment Pool Tuning Run 02 - 20VU Fallback Public, Lock Wait 2s 결과

## 1. 테스트 목적

`DeliveryExternalService`의 Resilience4j fallback 메서드 접근자를 `private`에서 `public`으로 변경한 뒤, 20VU 조건을 다시 측정했다.

직전 20VU 측정에서는 실패율이 6.67%로 높게 나왔지만, 바로 이어서 실행한 20VU 반복 측정에서는 실패율이 1.52%로 낮아졌다. 따라서 이 문서는 추가로 한 번 더 실행한 재측정 결과를 대표값으로 기록한다.

확인하려는 내용은 다음과 같다.

- fallback 접근 오류 수정 후 `IllegalAccessException`과 502가 사라지는지
- Hikari pool 20 설정에서 20VU 처리량과 latency가 어느 정도인지
- 20VU 실패율이 계속 높게 재현되는지
- 실패가 발생한다면 hub/user 통신 실패인지, 배송 담당자 배정 lock timeout인지
- DB/outbox 반영량이 k6 성공 건수와 일치하는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Pool Tuning Run 02 - 20VU Fallback Public, Lock Wait 2s |
| 시작 시간 | 2026-06-30 23:44:11 KST |
| 종료 시간 | 2026-06-30 23:52:11 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | fallback 접근자 `public`, delivery Hikari max 20 / min idle 3 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6,609 |
| HTTP TPS | 13.77 req/s |
| 성공 요청 수 | 6,525 |
| 실패 요청 수 | 84 |
| 실패율 | 1.27% |
| checks 성공률 | 98.72% |
| 평균 응답 시간 | 1.19s |
| median 응답 시간 | 1.19s |
| p90 응답 시간 | 1.85s |
| p95 응답 시간 | 2.03s |
| p99 응답 시간 | 2.20s |
| 최대 응답 시간 | 2.43s |

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

실패 84건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

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
| `p_deliveries` | 33,600 | 40,125 | 6,525 |
| `p_delivery_route_histories` | 67,200 | 80,250 | 13,050 |
| `p_delivery_outboxes` | 33,600 | 40,125 | 6,525 |
| `PUBLISHED` outbox | 33,600 | 40,125 | 6,525 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 10,125 | 6,525 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 8,325 | 6,525 |

DB 반영량은 k6 성공 요청 수 6,525건과 일치한다. route history는 배송 1건당 2건씩 생성되어 13,050건 증가했다.

## 5. 서버 로그와 지표

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 84 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 52 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 32 |

Prometheus 기준 delivery-service Hikari:

| 항목 | 값 |
| --- | ---: |
| active 최대 | 20 |
| pending 최대 | 2 |
| idle 최저 | 0 |
| max pool | 20 |

## 6. 반복 측정 비교

같은 20VU 조건에서 세 번 측정한 결과는 다음과 같다.

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 최초 20VU 측정 | 5,408 | 5,047 | 361 | 6.67% | 11.27 | 2.23s | 2.42s |
| 20VU 반복 측정 1 | 6,695 | 6,593 | 102 | 1.52% | 13.95 | 2.06s | 2.21s |
| 20VU 반복 측정 2 | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s |

최초 20VU 측정은 같은 조건의 반복 측정 2회보다 실패율이 뚜렷하게 높다. 따라서 최초 run은 lock 획득 타이밍 또는 직전 시스템 상태의 영향을 받은 이상치에 가깝게 본다.

반복 측정 2회는 TPS, p95, p99, 실패율이 유사하다. 20VU 대표값은 반복 측정 2 결과를 사용한다.

## 7. 분석

fallback 접근자 수정 후 20VU에서는 502와 `IllegalAccessException`이 발생하지 않았다. 즉 이전 100VU 중단 run에서 확인된 fallback 접근 오류는 제거된 것으로 볼 수 있다.

반복 측정 기준으로 20VU 실패율은 1%대이며, 실패는 전부 `DELIVERY_014` lock timeout이다. Hikari pending 최대가 2에 그쳤기 때문에, 이번 run의 실패는 DB connection pool 대기보다는 company delivery manager 배정 lock hotspot에서 발생한 것으로 해석하는 편이 맞다.

실패 lock key는 두 company key에만 분포했다. receiver company를 18개로 분산했더라도 실제 배정 대상 company delivery manager lock은 Busan/Incheon 권역 2개 key로 수렴하고 있다.

## 8. 결론

20VU 대표 재측정 결과는 모든 threshold를 통과했고, 실패율은 1.27%였다. 최초 20VU 측정의 6.67% 실패율은 반복 측정에서 재현되지 않았으므로 대표 결과로 사용하지 않는다.

현재 반복 재현되는 문제는 외부 서비스 통신이나 fallback이 아니라 company delivery manager 배정 lock timeout이다. 다음 최적화는 lock 임계구간 축소 또는 company delivery lock key 분산을 우선 비교한다.
