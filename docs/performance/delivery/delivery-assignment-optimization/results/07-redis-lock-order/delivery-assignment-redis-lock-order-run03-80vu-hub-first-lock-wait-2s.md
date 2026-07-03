# Delivery Assignment Redis Lock Order Run 03 - 80VU Hub First, Lock Wait 2s 결과

## 1. 테스트 목적

`run01` 20VU, `run02` 50VU에 이어 같은 hub-first Redis lock 순서에서 80VU까지 부하를 올렸을 때 처리량, tail latency, lock timeout, Hikari pending, outbox backlog가 어떻게 변하는지 확인했다.

확인 대상은 다음과 같다.

- 80VU에서도 실패가 `DELIVERY_014` lock timeout에 머무는지
- timeout failedKey가 계속 hub key로 관측되는지
- 50VU 대비 처리량 증가가 latency와 Hikari pending 증가로 흡수되는지
- outbox publisher backlog가 더 커지는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 03 - 80VU Hub First, Lock Wait 2s |
| 시작 시간 | 2026-07-02 12:33:42 KST |
| 종료 시간 | 2026-07-02 12:41:42 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Redis lock 획득 순서 `hub -> company -> unknown` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 10,228 |
| HTTP TPS | 21.31 req/s |
| 성공 요청 수 | 10,221 |
| 실패 요청 수 | 7 |
| 실패율 | 0.06% |
| checks 성공률 | 99.93% |
| 평균 응답 시간 | 3.06s |
| median 응답 시간 | 3.49s |
| p90 응답 시간 | 3.76s |
| p95 응답 시간 | 3.83s |
| p99 응답 시간 | 4.34s |
| 최대 응답 시간 | 6.76s |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000, actual p95=3.83s
✓ p(99)<6000, actual p99=4.34s

http_req_failed
✓ rate<0.10
```

실패 7건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
```

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 43,821 | 10,221 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 87,642 | 20,442 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 43,821 | 10,221 |

DB 반영량은 k6 성공 요청 수 10,221건과 일치한다. route history는 배송 1건당 2건씩 생성되어 20,442건 증가했다.

outbox 상태는 조회 시점 기준 다음과 같았다.

| status | count |
| --- | ---: |
| PENDING | 9,821 |
| PUBLISHED | 34,000 |

80VU에서는 테스트 종료 후에도 신규 outbox 대부분이 PENDING으로 남았다. 50VU에서도 backlog가 컸지만, 80VU에서는 publisher 처리 지연이 더 뚜렷하다.

Redis 배송 lock 잔여는 0건이었다.

## 5. 서버 로그 결과

delivery-service 로그 기준 lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:hub:10000000-0000-0000-0000-000000000001` | 7 |

7건 모두 hub key에서 timeout이 발생했다. company key는 lock 목록에는 포함됐지만, 실패 지점은 hub lock 획득 단계였다.

## 6. Prometheus 계측 결과

Prometheus `increase()` 결과는 scrape 간격 때문에 정수가 아닌 보간값이 포함될 수 있다. 실패 건수 자체는 k6와 로그 기준 7건을 사용한다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| hub acquired | 0.766s | 1.870s |
| hub timeout | 2.000s | 2.001s |
| company acquired | 0.000s | 0.010s |

hub lock 대기는 평균 0.766s로 50VU의 0.761s와 거의 비슷했고, timeout은 2s lock wait에 도달했다.

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.043s | 0.206s |

락을 실제로 점유한 시간은 평균 43ms 수준이다. 따라서 80VU의 tail latency는 lock 내부 로직 자체가 길어서라기보다, hub key 대기열과 DB connection pool 대기가 함께 만든 결과로 해석한다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.011s | 0.098s |
| `hub_delivery` / `read` | 0.015s | 0.110s |
| `mixed` / `increase` | 0.001s | 0.033s |

집계 write는 평균 1ms 미만으로 유지됐다. 80VU에서도 mixed bulk upsert 자체는 주요 병목으로 보이지 않는다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.021s |
| `route_history_save_all` | 0.000s | 0.040s |
| `outbox_enqueue` | 0.009s | 0.094s |
| `deadline_event_register` | 0.000s | 0.013s |
| `total_transaction` | 0.009s | 0.095s |

배송 저장 트랜잭션은 평균 9ms 수준으로 낮다. 다만 outbox publish backlog는 별도 병목으로 계속 커지고 있다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| delivery-service active 최대 | 20 |
| delivery-service pending 최대 | 62 |

80VU에서는 Hikari active가 max 20에 도달했고 pending 최대가 62까지 증가했다. 이는 50VU의 pending 최대 32보다 크게 증가한 값이다.

## 7. 20VU / 50VU / 80VU 비교

| 항목 | 20VU run01 | 50VU run02 | 80VU run03 |
| --- | ---: | ---: | ---: |
| 총 요청 | 6,916 | 9,960 | 10,228 |
| 성공 | 6,830 | 9,951 | 10,221 |
| 실패 | 86 | 9 | 7 |
| 실패율 | 1.24% | 0.09% | 0.06% |
| TPS | 14.40 | 20.75 | 21.31 |
| p95 | 1.79s | 2.49s | 3.83s |
| p99 | - | 3.01s | 4.34s |
| Hikari pending 최대 | 2 | 32 | 62 |
| timeout key | hub | hub | hub |

80VU는 50VU 대비 TPS가 20.75에서 21.31로 소폭만 증가했다. 반면 p95는 2.49s에서 3.83s로 크게 증가했고, Hikari pending은 32에서 62로 증가했다.

즉 50VU 이후 추가 동시성은 처리량 증가보다 대기 시간 증가로 더 많이 흡수된다. lock timeout 실패율은 낮지만, p95 threshold가 깨지고 connection pool 대기가 커진다.

## 8. 결론

80VU hub-first run은 실패율 0.06%로 HTTP 실패 기준은 통과했지만, p95 3.83s로 latency threshold를 통과하지 못했다.

주요 관찰은 다음과 같다.

- timeout failedKey는 계속 hub key 1개로 관측된다.
- lock hold 평균은 43ms 수준이라 락 내부 처리 자체는 길지 않다.
- Hikari pending 최대가 62까지 증가해 DB connection 대기가 50VU보다 악화됐다.
- outbox PENDING이 9,821건 남아 publisher backlog가 커졌다.
- 50VU 이후 TPS 증가는 작고 tail latency와 pending이 커진다.

따라서 hub-first 구조에서 80VU는 기능적으로는 대부분 성공하지만, 성능 기준으로는 이미 한계 구간에 들어간 것으로 본다. 다음 비교는 같은 조건을 company-first로 되돌린 뒤 20/50/80VU를 다시 측정해, lock 순서가 실제로 failure key만 바꾸는지 아니면 tail latency와 pending에도 영향을 주는지 확인하는 흐름이 적절하다.

## 9. 재측정 결과

같은 조건으로 80VU를 한 번 더 실행했다.

| 항목 | run03 | 재측정 |
| --- | ---: | ---: |
| 총 요청 | 10,228 | 10,576 |
| 성공 | 10,221 | 10,568 |
| 실패 | 7 | 8 |
| 실패율 | 0.06% | 0.07% |
| TPS | 21.31 req/s | 22.03 req/s |
| 평균 응답 시간 | 3.06s | 2.96s |
| median | 3.49s | 3.41s |
| p90 | 3.76s | 3.61s |
| p95 | 3.83s | 3.67s |
| p99 | 4.34s | 4.21s |
| max | 6.76s | 7.01s |
| Hikari pending 최대 | 62 | 62 |
| timeout key | hub | hub |

재측정에서도 p95가 3초 threshold를 넘어서 k6 실행은 실패 종료됐다. 다만 실패율은 0.07%로 낮았고, DB 반영량은 k6 성공 10,568건과 일치했다.

재측정 후 DB 상태:

| 항목 | 값 |
| --- | ---: |
| `delivery_service.p_deliveries` | 44,168 |
| `delivery_service.p_delivery_route_histories` | 88,336 |
| outbox `PUBLISHED` | 28,000 |
| outbox `FAILED` | 3,540 |
| outbox `PENDING` | 12,628 |

outbox `FAILED` 3,540건은 이번 publish 실패가 아니라 baseline seed가 의도적으로 넣는 상태값이다. `14-reset-delivery-perf-baseline.sql`에서 outbox status를 `PUBLISHED`, `FAILED`, `PENDING`으로 분산 적재한다. 따라서 이번 run에서 새로 생성된 배송 성공분은 주로 `PENDING` 증가분으로 봐야 한다.

Prometheus 기준 주요 계측:

| 항목 | 평균 | 최대 |
| --- | ---: | ---: |
| hub lock acquired wait | 0.738s | 1.824s |
| hub lock timeout wait | 2.000s | 2.001s |
| company lock acquired wait | 0.000s | 0.059s |
| lock hold mixed success | 0.042s | 0.184s |
| company count read | 0.010s | 0.061s |
| hub count read | 0.015s | 0.106s |
| mixed count increase | 0.001s | 0.074s |
| create total transaction | 0.008s | 0.066s |

재측정 결과도 첫 80VU와 같은 결론이다. lock timeout은 hub key에서만 낮은 비율로 발생하고, Hikari pending은 62까지 증가한다. 처리량은 50VU 대비 조금 늘지만 p95가 계속 3초를 넘기 때문에, 80VU는 현재 hub-first 구조의 latency 한계 구간으로 보는 것이 맞다.
