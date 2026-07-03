# Delivery Assignment Redis Lock Order Run 04 - 100VU Hub First, Lock Wait 2s 결과

## 1. 테스트 목적

`run01` 20VU, `run02` 50VU, `run03` 80VU에 이어 같은 hub-first Redis lock 순서에서 100VU까지 부하를 올렸을 때 처리량 상한, tail latency, lock timeout, Hikari pending, outbox backlog가 어떻게 변하는지 확인했다.

확인 대상은 다음과 같다.

- 100VU에서도 실패가 `DELIVERY_014` lock timeout에 머무는지
- timeout failedKey가 계속 hub key로 관측되는지
- 80VU 대비 처리량이 증가하는지, 아니면 latency와 pending만 증가하는지
- DB 반영량이 k6 성공 건수와 일치하는지
- outbox publisher backlog가 테스트 종료 후 남는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 04 - 100VU Hub First, Lock Wait 2s |
| 시작 시간 | 2026-07-02 15:19:57 KST |
| 종료 시간 | 2026-07-02 15:27:57 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Redis lock 획득 순서 `hub -> company -> unknown` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 10,494 |
| HTTP TPS | 21.86 req/s |
| 성공 요청 수 | 10,478 |
| 실패 요청 수 | 16 |
| 실패율 | 0.15% |
| checks 성공률 | 99.84% |
| 평균 응답 시간 | 3.73s |
| median 응답 시간 | 4.30s |
| p90 응답 시간 | 4.59s |
| p95 응답 시간 | 4.99s |
| p99 응답 시간 | 5.91s |
| 최대 응답 시간 | 10.50s |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000, actual p95=4.99s
✓ p(99)<6000, actual p99=5.91s

http_req_failed
✓ rate<0.10
```

실패 16건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
```

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 44,078 | 10,478 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 88,156 | 20,956 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 44,078 | 10,478 |

DB 반영량은 k6 성공 요청 수 10,478건과 일치한다. route history는 배송 1건당 2건씩 생성되어 20,956건 증가했다.

outbox 상태는 조회 시점에 따라 다음과 같이 변했다.

| 확인 시점 | FAILED | PENDING | PUBLISHED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 1,290 | 11,088 | 31,700 |
| 재조회 시점 | 0 | 8,378 | 35,700 |
| 최종 재조회 시점 | 0 | 1,078 | 43,000 |

outbox는 멈춘 상태가 아니라 publish가 진행 중이었지만, 100VU 종료 후에도 backlog가 한동안 남았다. `FAILED`는 baseline seed가 섞어 넣는 상태값이며, 재조회 시점에는 남아 있지 않았다.

Redis 배송 lock 잔여는 테스트 종료 후 0건이었다.

## 5. 서버 로그 결과

delivery-service 로그 기준 lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:hub:10000000-0000-0000-0000-000000000001` | 16 |

16건 모두 hub key에서 timeout이 발생했다. company key는 lock 목록에는 포함됐지만, 실패 지점은 hub lock 획득 단계였다.

## 6. Prometheus 계측 결과

Prometheus `increase()` 결과는 scrape 간격 때문에 정수가 아닌 보간값이 포함될 수 있다. 실패 건수 자체는 k6와 로그 기준 16건을 사용한다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| hub acquired | 0.750s | 1.993s |
| hub timeout | 2.001s | 2.006s |
| company acquired | 0.000s | 0.011s |

100VU에서도 company lock wait는 거의 없고, timeout은 hub lock 획득 단계에서 발생했다.

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.042s | 0.388s |

락을 실제로 점유한 시간은 평균 42ms 수준이다. 100VU의 지연 증가는 lock 내부 처리 시간이 길어졌다기보다, hub key 대기열과 connection pool 대기가 누적된 결과로 해석한다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.010s | 0.073s |
| `hub_delivery` / `read` | 0.015s | 0.103s |
| `mixed` / `increase` | 0.001s | 0.032s |

집계 write는 평균 1ms 미만으로 유지됐다. 100VU에서도 mixed bulk upsert 자체는 주요 병목으로 보이지 않는다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.023s |
| `route_history_save_all` | 0.000s | 0.021s |
| `outbox_enqueue` | 0.009s | 0.075s |
| `deadline_event_register` | 0.000s | 0.009s |
| `total_transaction` | 0.009s | 0.075s |

배송 저장 트랜잭션은 평균 9ms 수준으로 낮다. 다만 outbox publisher는 100VU 종료 후 backlog를 즉시 해소하지 못했다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| delivery-service active 최대 | 20 |
| delivery-service pending 최대 | 82 |

100VU에서는 Hikari active가 max 20에 도달했고 pending 최대가 82까지 증가했다. 이는 80VU의 pending 최대 62보다 더 높은 값이다.

## 7. 20VU / 50VU / 80VU / 100VU 비교

| 항목 | 20VU run01 | 50VU run02 | 80VU run03 | 100VU run04 |
| --- | ---: | ---: | ---: | ---: |
| 총 요청 | 6,916 | 9,960 | 10,228 | 10,494 |
| 성공 | 6,830 | 9,951 | 10,221 | 10,478 |
| 실패 | 86 | 9 | 7 | 16 |
| 실패율 | 1.24% | 0.09% | 0.06% | 0.15% |
| TPS | 14.40 | 20.75 | 21.31 | 21.86 |
| p95 | 1.79s | 2.49s | 3.83s | 4.99s |
| p99 | - | 3.01s | 4.34s | 5.91s |
| Hikari pending 최대 | 2 | 32 | 62 | 82 |
| timeout key | hub | hub | hub | hub |

100VU는 80VU 대비 TPS가 21.31에서 21.86으로 0.55 req/s만 증가했다. 반면 p95는 3.83s에서 4.99s로, p99는 4.34s에서 5.91s로 증가했고 Hikari pending도 62에서 82로 증가했다.

즉 80VU 이후 추가 동시성은 처리량 증가보다 대기 시간 증가로 더 많이 흡수된다.

## 8. 결론

100VU hub-first run은 HTTP 실패율 기준은 통과했지만, p95 4.99s로 latency threshold를 통과하지 못했다. p99는 5.91s로 threshold 6초 바로 아래까지 올라왔다.

주요 관찰은 다음과 같다.

- timeout failedKey는 계속 hub key 1개로 관측된다.
- 실패 16건은 모두 `DELIVERY_014` lock timeout이다.
- lock hold 평균은 42ms 수준이라 락 내부 처리 자체는 길지 않다.
- Hikari pending 최대가 82까지 증가했다.
- 80VU 대비 TPS 증가는 작고 p95, p99, pending은 크게 증가했다.
- outbox는 publish가 진행 중이지만 테스트 종료 후 backlog가 남는다.

따라서 현재 hub-first 구조의 처리량 상한은 80~100VU 구간에서 이미 드러난 것으로 본다. 100VU는 요청을 더 밀어 넣어도 throughput은 크게 늘지 않고, hub lock wait와 DB connection 대기가 tail latency로 누적된다.
