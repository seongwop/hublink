# Delivery Assignment Redis Lock Order Run 06 - 80VU Company First, Lock Wait 2s 결과

## 1. 테스트 목적

`run05`에서 company-first 50VU를 재측정한 뒤, 같은 구현과 같은 입력 조건에서 80VU까지 부하를 올려 hub-first 80VU 결과와 비교한다.

확인 대상은 다음과 같다.

- 80VU에서도 timeout key가 company key로 유지되는지
- 50VU 대비 TPS 증가가 latency와 Hikari pending으로 흡수되는지
- hub-first 80VU 대비 실패율, p95, p99가 어떻게 달라지는지
- 외부 hub/user 통신 실패와 circuit breaker open이 재현되는지
- DB 반영량과 k6 성공 건수가 일치하는지
- outbox publisher backlog가 얼마나 남는지

## 2. 테스트 전 상태

직전 50VU 테스트의 outbox backlog가 모두 해소된 뒤 실행했다.

| 항목 | 결과 |
| --- | --- |
| delivery-service health | `UP` |
| Redis lock 잔여 | 0 |
| outbox backlog | `PUBLISHED 42,086` |
| lock order | `company -> hub -> unknown` |

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 06 - 80VU Company First, Lock Wait 2s |
| 시작 시간 | 2026-07-03 22:24:37 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| lock order | `company -> hub -> unknown` |
| k6 로그 | `/tmp/hublink-k6-80vu-company-first-detached-20260703T132437Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,040 |
| HTTP TPS | 18.83 req/s |
| 성공 요청 수 | 9,006 |
| 실패 요청 수 | 34 |
| 실패율 | 0.37% |
| checks 성공률 | 99.62% |
| 평균 응답 시간 | 3.46s |
| median 응답 시간 | 3.90s |
| p90 응답 시간 | 4.49s |
| p95 응답 시간 | 4.74s |
| p99 응답 시간 | 5.36s |
| 최대 응답 시간 | 8.70s |

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

실패는 모두 HTTP 409 `DELIVERY_014`였다. 로그 grep 기준 `DELIVERY_014`는 68회 잡혔지만, 한 실패 응답 안에 `message`와 `errorClassName`이 함께 포함되어 실제 실패 요청 수의 2배로 집계된 값이다. k6 실패 요청 수와 Prometheus lock timeout 증가는 모두 34건으로 일치한다.

이번 정상 run에서도 `DELIVERY_011` 외부 통신 실패와 circuit breaker not permitted 증가는 관측되지 않았다.

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 42,606 | 9,006 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 85,212 | 18,012 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 42,606 | 9,006 |

DB 반영량은 k6 성공 요청 수 9,006건과 일치한다. route history는 배송 1건당 2건씩 생성되어 18,012건 증가했다.

outbox 상태:

| 조회 시점 | PUBLISHED | PENDING | FAILED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 31,350 | 9,866 | 1,390 |
| 약 70초 후 | 36,700 | 5,906 | 0 |

테스트 직후에는 outbox publish가 크게 밀렸고 일시적인 `FAILED` 상태도 있었다. 이후 재시도되면서 `FAILED`는 사라졌지만, 70초 뒤에도 `PENDING 5,906`이 남았다. 배송 생성과 outbox 적재 정합성은 맞지만, 80VU 생성량에서는 outbox publisher가 테스트 종료 후에도 한동안 따라잡지 못한다.

Redis lock 잔여는 테스트 종료 후 0건이었다.

## 6. Prometheus 계측 결과

### Redis lock wait

| 구분 | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| company acquired | 9,006 | 7,441.28s | 0.826s |
| company timeout | 34 | 68.05s | 2.001s |
| hub acquired | 9,006 | 426.73s | 0.047s |

80VU에서도 timeout key는 company로만 관측됐다. company lock wait는 평균 0.83s이고, timeout은 lock wait 설정과 같은 2초까지 도달했다. hub lock wait는 평균 0.05s 수준으로 짧다.

### Redis lock hold

| lock_scope | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| mixed / success | 9,006 | 456.49s | 0.051s |

락 내부 hold 시간은 평균 51ms 수준이다. 즉 timeout은 lock 내부 개별 작업이 매우 길어서라기보다, 같은 company lock key로 들어오는 요청들이 앞에서 대기하기 때문에 발생한 것으로 해석한다.

### Assignment count operation

| assignment_type / operation | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| `company_delivery` / `read` | 9,006 | 109.39s | 0.012s |
| `hub_delivery` / `read` | 9,006 | 156.25s | 0.017s |
| `mixed` / `increase` | 9,006 | 8.11s | 0.001s |

집계 write는 평균 1ms 수준이다. 80VU에서도 주요 병목은 mixed bulk upsert 자체가 아니라 lock wait와 DB connection 대기 쪽이다.

### Hikari / circuit breaker

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active 최대 | 20 |
| delivery-service Hikari pending 최대 | 62 |
| hub-service circuit breaker failed 증가 | 0 |
| user-service circuit breaker failed 증가 | 0 |
| circuit breaker not permitted 증가 | 0 |

Hikari active는 max 20에 도달했고 pending은 최대 62까지 증가했다. 외부 서비스 circuit breaker는 이번 정상 run에서도 열리지 않았다.

## 7. 50VU company-first와 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run05 50VU company-first | 8,542 | 8,486 | 56 | 0.65% | 17.79 | 3.18s | 3.65s | company | 32 |
| run06 80VU company-first | 9,040 | 9,006 | 34 | 0.37% | 18.83 | 4.74s | 5.36s | company | 62 |

80VU는 50VU보다 TPS가 17.79에서 18.83으로 조금 증가했지만, p95는 3.18s에서 4.74s로 크게 악화됐다. 실패 건수는 오히려 줄었지만, Hikari pending은 32에서 62로 증가했다.

즉 80VU에서 추가 동시성은 처리량 증가보다 connection pool 대기와 tail latency 증가로 더 많이 흡수된다.

## 8. Hub-first 80VU와 비교

| 구분 | lock order | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run03 | hub-first | 10,228 | 10,221 | 7 | 0.06% | 21.31 | 3.83s | 4.34s | hub | 62 |
| run06 | company-first | 9,040 | 9,006 | 34 | 0.37% | 18.83 | 4.74s | 5.36s | company | 62 |

같은 80VU 조건에서 company-first는 hub-first보다 TPS가 낮고, p95/p99와 실패율이 모두 나쁘다. Hikari pending 최대는 둘 다 62로 같다. 따라서 80VU에서도 차이는 connection pool 크기보다 lock 획득 순서와 대기 위치에서 나온 것으로 보는 편이 맞다.

## 9. 기존 company-first 대표값과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| pool-tuning run04 company-first | 9,322 | 9,250 | 72 | 0.77% | 19.41 | 4.61s | 7.12s | company | 62 |
| redis-lock-order run06 company-first | 9,040 | 9,006 | 34 | 0.37% | 18.83 | 4.74s | 5.36s | company | 62 |

기존 company-first 대표값과 비교하면 이번 run은 실패 건수와 p99는 좋아졌지만, TPS는 낮고 p95는 비슷하거나 약간 나쁘다. Hikari pending 최대와 timeout key는 같은 패턴이다.

따라서 company-first 80VU는 수치 변동은 있으나, 큰 흐름상 company lock wait와 Hikari pending이 함께 tail latency를 키우는 상태로 해석한다.

## 10. 결론

company-first 80VU에서는 외부 통신 실패 없이 lock timeout만 발생했고, DB 반영량은 k6 성공 건수와 일치했다. timeout key는 company로 유지됐다.

다만 hub-first 80VU보다 TPS, p95, p99, 실패율이 모두 나빴다. Hikari pending 최대는 동일하게 62이므로, 이번 비교에서는 connection pool 한계가 공통으로 존재하는 상태에서 lock 획득 순서가 latency와 실패율을 추가로 좌우한 것으로 볼 수 있다.

다음은 같은 company-first 상태로 100VU를 측정해 hub-first 100VU와 비교하면 된다. 100VU에서도 Hikari pending 최대가 82 수준까지 올라가고 p99가 크게 악화되는지 확인하면 lock order 비교는 마무리할 수 있다.
