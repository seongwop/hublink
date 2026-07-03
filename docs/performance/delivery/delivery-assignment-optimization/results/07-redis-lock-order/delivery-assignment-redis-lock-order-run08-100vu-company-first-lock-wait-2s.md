# Delivery Assignment Redis Lock Order Run 08 - 100VU Company First, Lock Wait 2s 결과

## 1. 테스트 목적

company-first lock 순서 비교를 20VU, 50VU, 80VU에 이어 100VU까지 확장 측정했다. 같은 입력 조건에서 hub-first 100VU와 비교해 lock order가 처리량, tail latency, timeout key, Hikari pending에 어떤 차이를 만드는지 확인한다.

확인 대상은 다음과 같다.

- 100VU에서도 timeout key가 company key로 유지되는지
- 80VU 대비 TPS 증가가 latency와 Hikari pending으로 흡수되는지
- hub-first 100VU 대비 실패율, p95, p99가 어떻게 달라지는지
- 외부 hub/user 통신 실패와 circuit breaker open이 재현되는지
- DB 반영량과 k6 성공 건수가 일치하는지
- outbox publisher backlog가 얼마나 남는지

## 2. 테스트 전 상태

직전 20VU 테스트의 outbox backlog가 모두 해소된 뒤 실행했다.

| 항목 | 결과 |
| --- | --- |
| delivery-service health | `UP` |
| Redis lock 잔여 | 0 |
| outbox backlog | `PUBLISHED 39,704` |
| lock order | `company -> hub -> unknown` |

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 08 - 100VU Company First, Lock Wait 2s |
| 시작 시간 | 2026-07-03 23:31:58 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| lock order | `company -> hub -> unknown` |
| k6 로그 | `/tmp/hublink-k6-100vu-company-first-detached-20260703T143158Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,423 |
| HTTP TPS | 19.63 req/s |
| 성공 요청 수 | 9,385 |
| 실패 요청 수 | 38 |
| 실패율 | 0.40% |
| checks 성공률 | 99.59% |
| 평균 응답 시간 | 4.15s |
| median 응답 시간 | 4.77s |
| p90 응답 시간 | 5.21s |
| p95 응답 시간 | 5.34s |
| p99 응답 시간 | 5.72s |
| 최대 응답 시간 | 9.57s |

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

실패는 모두 HTTP 409 `DELIVERY_014`였다. 로그 grep 기준 `DELIVERY_014`는 76회 잡혔지만, 한 실패 응답 안에 `message`와 `errorClassName`이 함께 포함되어 실제 실패 요청 수의 2배로 집계된 값이다.

이번 run에서도 `DELIVERY_011` 외부 통신 실패와 circuit breaker not permitted 증가는 관측되지 않았다.

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 42,985 | 9,385 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 85,970 | 18,770 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 42,985 | 9,385 |

DB 반영량은 k6 성공 요청 수 9,385건과 일치한다. route history는 배송 1건당 2건씩 생성되어 18,770건 증가했다.

outbox 상태:

| 조회 시점 | PUBLISHED | PENDING | FAILED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 31,000 | 10,245 | 1,740 |
| 약 70초 후 | 36,300 | 6,685 | 0 |

테스트 직후에는 outbox publish가 크게 밀렸고 일시적인 `FAILED` 상태도 있었다. 이후 재시도되면서 `FAILED`는 사라졌지만, 70초 뒤에도 `PENDING 6,685`가 남았다. 배송 생성과 outbox 적재 정합성은 맞지만, 100VU 생성량에서는 outbox publisher가 테스트 종료 후에도 한동안 따라잡지 못한다.

Redis lock 잔여는 테스트 종료 후 0건이었다.

## 6. Prometheus 계측 결과

Prometheus lock timeout counter는 scrape 경계 때문에 `37건`으로 잡혔지만, k6 실패 요청 수와 로그 기준 실패 수는 `38건`이다. 실패 건수는 k6 요약을 기준으로 해석한다.

### Redis lock wait

| 구분 | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| company acquired | 9,386 | 7,489.47s | 0.798s |
| company timeout | 37 | 74.02s | 2.001s |
| hub acquired | 9,385 | 429.47s | 0.046s |
| hub timeout | 0 | 0s | - |

100VU에서도 timeout key는 company로만 관측됐다. company lock wait는 평균 0.80s이고, timeout은 lock wait 설정과 같은 2초까지 도달했다. hub lock wait는 평균 0.05s 수준으로 짧다.

### Redis lock hold

| lock_scope | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| mixed / success | 9,385 | 456.92s | 0.049s |

락 내부 hold 시간은 평균 49ms 수준이다. 즉 100VU에서도 timeout의 원인은 단일 요청의 hold가 길어서라기보다, 같은 company lock key 앞 대기가 누적되는 구조로 해석한다.

### Assignment count operation

| assignment_type / operation | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| `company_delivery` / `read` | 9,385 | 109.20s | 0.012s |
| `hub_delivery` / `read` | 9,385 | 156.83s | 0.017s |
| `mixed` / `increase` | 9,385 | 7.93s | 0.001s |

집계 write는 평균 1ms 수준이다. 100VU에서도 주요 병목은 mixed bulk upsert가 아니라 lock wait와 DB connection 대기 쪽이다.

### Hikari / circuit breaker

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active 최대 | 20 |
| delivery-service Hikari pending 최대 | 82 |
| hub-service circuit breaker failed 증가 | 0 |
| user-service circuit breaker failed 증가 | 0 |
| circuit breaker not permitted 증가 | 0 |

Hikari active는 max 20에 도달했고 pending은 최대 82까지 증가했다. 외부 서비스 circuit breaker는 이번 정상 run에서도 열리지 않았다.

## 7. Company-first 20/50/80/100VU 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run07 20VU company-first | 6,249 | 6,104 | 145 | 2.32% | 13.02 | 2.10s | 2.27s | company | 2 |
| run05 50VU company-first | 8,542 | 8,486 | 56 | 0.65% | 17.79 | 3.18s | 3.65s | company | 32 |
| run06 80VU company-first | 9,040 | 9,006 | 34 | 0.37% | 18.83 | 4.74s | 5.36s | company | 62 |
| run08 100VU company-first | 9,423 | 9,385 | 38 | 0.40% | 19.63 | 5.34s | 5.72s | company | 82 |

100VU는 80VU 대비 TPS가 18.83에서 19.63으로 소폭 증가했지만, p95는 4.74s에서 5.34s로 악화됐고 Hikari pending은 62에서 82로 증가했다. 실패율은 0.37%에서 0.40%로 거의 비슷하다.

즉 50VU 이후부터 추가 동시성은 처리량 증가보다 tail latency와 DB connection pending 증가로 더 많이 흡수된다.

## 8. Hub-first 100VU와 비교

| 구분 | lock order | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run04 | hub-first | 10,494 | 10,478 | 16 | 0.15% | 21.86 | 4.99s | 5.91s | hub | 82 |
| run08 | company-first | 9,423 | 9,385 | 38 | 0.40% | 19.63 | 5.34s | 5.72s | company | 82 |

같은 100VU 조건에서 company-first는 hub-first보다 TPS가 낮고 실패율과 p95가 나쁘다. p99는 company-first가 약간 낮지만, 둘 다 6초 threshold 근처다. Hikari pending 최대는 둘 다 82로 같다.

따라서 100VU에서도 connection pool 한계는 공통으로 존재하지만, lock 획득 순서 차이가 timeout key, 실패율, 처리량에 영향을 준 것으로 본다.

## 9. Hub-first가 더 좋았던 이유

이번 입력 조건에서는 출고 회사가 1개로 고정되어 있고, 이 출고 회사는 Seoul Hub에 속한다. 반면 수령 회사는 18개로 분산되어 있지만, 최종 도착 hub 기준으로는 Busan Hub와 Incheon Hub 쪽으로 수렴한다.

따라서 Redis lock key 관점에서는 다음 구조가 된다.

| 구분 | lock key 수렴 |
| --- | --- |
| hub lock | `lock:delivery:hub:{Seoul Hub}` 1개로 집중 |
| company lock | `lock:delivery:company:{Busan Hub}`, `lock:delivery:company:{Incheon Hub}` 2개로 분산 |

처음에는 company key가 2개이고 hub key가 1개이므로 hub-first가 더 불리할 것처럼 보일 수 있다. 하지만 실제로는 모든 요청이 공통 Seoul Hub lock을 통과해야 한다는 점이 더 중요하다.

company-first에서는 요청이 Busan/Incheon company lock을 먼저 잡은 뒤, 공통 Seoul Hub lock을 기다릴 수 있다. 이 경우 어떤 요청이 company lock을 잡은 채 hub lock 앞에서 대기하면, 같은 company key를 필요로 하는 뒤 요청도 같이 막힌다. 즉 공통 hub lock 대기가 company lock 점유 시간 안으로 들어와서 lock 대기가 중첩된다.

반대로 hub-first에서는 요청이 먼저 공통 Seoul Hub lock 앞에서 줄을 선다. 이때는 아직 company lock을 잡지 않았기 때문에, hub lock을 기다리는 동안 Busan/Incheon company lock을 불필요하게 점유하지 않는다. hub lock을 통과한 뒤 company lock은 비교적 짧게 잡고 지나간다.

정리하면 hub-first가 더 좋았던 이유는 hub lock 자체가 병목이 아니어서가 아니다. 공통 병목인 hub lock을 먼저 통과하게 만들면서, company lock을 잡은 채 hub lock을 기다리는 중첩 대기를 줄였기 때문이다. 이번 결과에서 Hikari pending 최대가 같은 VU에서 동일하게 유지됐는데도 hub-first의 TPS와 실패율이 더 좋았던 점은 이 해석을 뒷받침한다.

다만 이 결론은 현재 seed와 입력 조건에 대한 것이다. supplier가 여러 출발 hub로 분산되거나, hub-to-hub 경로가 여러 단계로 늘어나면 hub key 분포가 달라질 수 있으므로 별도 입력으로 다시 측정해야 한다.

## 10. 기존 company-first 대표값과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| pool-tuning run05 company-first | 9,650 | 9,620 | 30 | 0.31% | 20.05 | 5.54s | 8.54s | company | 82 |
| redis-lock-order run08 company-first | 9,423 | 9,385 | 38 | 0.40% | 19.63 | 5.34s | 5.72s | company | 82 |

기존 company-first 대표값과 비교하면 이번 run은 TPS와 실패율은 약간 나쁘지만, p95와 p99는 좋아졌다. Hikari pending 최대와 timeout key는 같은 패턴이다.

따라서 company-first 100VU는 수치 변동은 있지만, 큰 흐름상 company lock wait와 Hikari pending이 함께 tail latency를 키우는 상태로 해석한다.

## 11. 결론

company-first 100VU에서는 외부 통신 실패 없이 lock timeout만 발생했고, DB 반영량은 k6 성공 건수와 일치했다. timeout key는 company로 유지됐다.

hub-first 100VU와 비교하면 company-first는 TPS, 실패율, p95가 나빴고, Hikari pending 최대는 동일하게 82였다. 즉 100VU에서는 connection pool saturation이 공통 병목으로 강하게 나타나지만, lock order도 처리량과 실패율에 영향을 준다.

이 run으로 company-first 비교 축은 20VU, 50VU, 80VU, 100VU까지 채워졌다. 전체 비교 기준으로는 hub-first가 같은 VU에서 대체로 더 높은 TPS와 낮은 실패율을 보였지만, 둘 다 80VU 이후부터는 Hikari pending과 outbox backlog가 커져 end-to-end 한계가 드러난다.
