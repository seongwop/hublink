# Delivery Assignment Redis Lock Order Run 07 - 20VU Company First, Lock Wait 2s 결과

## 1. 테스트 목적

`run05` 50VU, `run06` 80VU를 company-first lock 순서로 재측정한 뒤, 빠져 있던 20VU company-first 결과를 같은 구현과 같은 입력 조건에서 보완 측정했다.

확인 대상은 다음과 같다.

- company-first 20VU에서 timeout key가 company key로 유지되는지
- hub-first 20VU와 비교했을 때 실패율과 p95가 어떻게 달라지는지
- 50VU, 80VU와 달리 낮은 부하에서 Hikari pending 영향이 얼마나 작은지
- 외부 hub/user 통신 실패와 circuit breaker open이 재현되는지
- DB 반영량과 k6 성공 건수가 일치하는지

## 2. 테스트 전 상태

직전 80VU 테스트의 outbox backlog가 모두 해소된 뒤 실행했다.

| 항목 | 결과 |
| --- | --- |
| delivery-service health | `UP` |
| Redis lock 잔여 | 0 |
| outbox backlog | `PUBLISHED 42,606` |
| lock order | `company -> hub -> unknown` |

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 07 - 20VU Company First, Lock Wait 2s |
| 시작 시간 | 2026-07-03 22:58:22 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| lock order | `company -> hub -> unknown` |
| k6 로그 | `/tmp/hublink-k6-20vu-company-first-detached-20260703T135822Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6,249 |
| HTTP TPS | 13.02 req/s |
| 성공 요청 수 | 6,104 |
| 실패 요청 수 | 145 |
| 실패율 | 2.32% |
| checks 성공률 | 97.67% |
| 평균 응답 시간 | 1.25s |
| median 응답 시간 | 1.27s |
| p90 응답 시간 | 2.00s |
| p95 응답 시간 | 2.10s |
| p99 응답 시간 | 2.27s |
| 최대 응답 시간 | 2.54s |

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

실패는 모두 HTTP 409 `DELIVERY_014`였다. 로그 grep 기준 `DELIVERY_014`는 290회 잡혔지만, 한 실패 응답 안에 `message`와 `errorClassName`이 함께 포함되어 실제 실패 요청 수의 2배로 집계된 값이다. k6 실패 요청 수와 Prometheus lock timeout 증가는 모두 145건으로 일치한다.

이번 run에서도 `DELIVERY_011` 외부 통신 실패와 circuit breaker not permitted 증가는 관측되지 않았다.

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 39,704 | 6,104 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 79,408 | 12,208 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 39,704 | 6,104 |

DB 반영량은 k6 성공 요청 수 6,104건과 일치한다. route history는 배송 1건당 2건씩 생성되어 12,208건 증가했다.

outbox 상태:

| 조회 시점 | PUBLISHED | PENDING |
| --- | ---: | ---: |
| 테스트 직후 | 38,689 | 1,015 |

20VU에서는 outbox backlog가 50VU, 80VU보다 작게 남았다. 배송 생성과 outbox 적재 정합성은 맞다.

Redis lock 잔여는 테스트 종료 후 0건이었다.

## 6. Prometheus 계측 결과

### Redis lock wait

| 구분 | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| company acquired | 6,104 | 6,000.56s | 0.983s |
| company timeout | 145 | 290.33s | 2.002s |
| hub acquired | 6,104 | 415.51s | 0.068s |

20VU에서도 timeout key는 company로만 관측됐다. company lock wait는 평균 0.98s이고, timeout은 lock wait 설정과 같은 2초까지 도달했다. hub lock wait는 평균 0.07s 수준이다.

### Redis lock hold

| lock_scope | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| mixed / success | 6,104 | 452.46s | 0.074s |

락 내부 hold 시간은 평균 74ms 수준이다. 회사 lock wait 평균이 1초에 가깝기 때문에, 실제 병목은 개별 hold 시간이 길다기보다 같은 company key 앞에서 대기열이 형성되는 구조로 해석한다.

### Assignment count operation

| assignment_type / operation | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| `company_delivery` / `read` | 6,104 | 98.25s | 0.016s |
| `hub_delivery` / `read` | 6,104 | 154.84s | 0.025s |
| `mixed` / `increase` | 6,104 | 8.54s | 0.001s |

집계 write는 평균 1ms 수준이다. 20VU에서도 주요 병목은 mixed bulk upsert가 아니라 company lock wait다.

### Hikari / circuit breaker

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active 최대 | 20 |
| delivery-service Hikari pending 최대 | 2 |
| hub-service circuit breaker failed 증가 | 0 |
| user-service circuit breaker failed 증가 | 0 |
| circuit breaker not permitted 증가 | 0 |

20VU에서는 Hikari pending 최대가 2에 그쳤다. 50VU, 80VU와 달리 DB connection pool 대기보다는 company lock wait가 더 선명하게 드러난다.

## 7. Company-first 20/50/80VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run07 20VU company-first | 6,249 | 6,104 | 145 | 2.32% | 13.02 | 2.10s | 2.27s | company | 2 |
| run05 50VU company-first | 8,542 | 8,486 | 56 | 0.65% | 17.79 | 3.18s | 3.65s | company | 32 |
| run06 80VU company-first | 9,040 | 9,006 | 34 | 0.37% | 18.83 | 4.74s | 5.36s | company | 62 |

실패율만 보면 20VU가 가장 나쁘고, 50/80VU로 갈수록 실패율은 낮아진다. 대신 p95와 Hikari pending은 VU가 올라갈수록 악화된다.

이 패턴은 단순히 동시성이 높을수록 실패가 늘어난다는 형태가 아니다. 낮은 VU에서는 lock wait가 2초 timeout으로 직접 드러나고, 높은 VU에서는 더 많은 요청이 대기하면서 latency와 DB connection pending으로 흡수되는 경향이 함께 나타난다.

## 8. Hub-first 20VU와 비교

| 구분 | lock order | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run01 | hub-first | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | - | hub | 2 |
| run07 | company-first | 6,249 | 6,104 | 145 | 2.32% | 13.02 | 2.10s | 2.27s | company | 2 |

같은 20VU 조건에서 company-first는 hub-first보다 TPS가 낮고, 실패율과 p95가 더 나쁘다. Hikari pending 최대는 둘 다 2이므로, 20VU 비교에서는 connection pool보다 lock wait 차이가 더 직접적으로 드러난다.

## 9. 기존 company-first 대표값과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| pool-tuning run02 company-first | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s | company | 2 |
| redis-lock-scope run02 company-first | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | 2.36s | company | 2 |
| redis-lock-order run07 company-first | 6,249 | 6,104 | 145 | 2.32% | 13.02 | 2.10s | 2.27s | company | 2 |

이번 20VU company-first 결과는 기존 company-first 측정 범위 안에 있다. 실패율은 pool-tuning run02보다 나쁘고 redis-lock-scope run02보다는 낫다. p95와 Hikari pending은 거의 같은 수준이다.

따라서 20VU company-first는 변동성이 있지만, 공통적으로 company lock timeout이 주요 실패 원인이라는 결론은 유지된다.

## 10. 결론

company-first 20VU에서는 외부 통신 실패 없이 lock timeout만 발생했고, DB 반영량은 k6 성공 건수와 일치했다. timeout key는 company로 유지됐다.

이번 run은 50/80VU company-first보다 실패율이 높았지만, p95와 p99는 threshold를 통과했다. 즉 20VU에서는 응답 시간보다 lock timeout 실패가 더 먼저 드러나고, 50VU 이후부터는 실패율보다 tail latency와 Hikari pending이 더 크게 악화된다.

이 결과를 포함하면 company-first 비교 축은 20VU, 50VU, 80VU까지 채워졌다. 다음 단계는 100VU company-first를 측정해 hub-first 100VU와 비교하는 것이다.
