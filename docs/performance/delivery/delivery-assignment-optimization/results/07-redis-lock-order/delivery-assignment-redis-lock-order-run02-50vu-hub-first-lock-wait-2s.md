# Delivery Assignment Redis Lock Order Run 02 - 50VU Hub First, Lock Wait 2s 결과

## 1. 테스트 목적

`run01`에서 Redis lock 획득 순서를 `hub -> company -> unknown`으로 변경하자 timeout failedKey가 company key에서 hub key로 이동했다. 이번 run은 같은 구현으로 50VU까지 부하를 올려 hub-first 상태에서 실패율, 응답 시간, lock wait, Hikari pending이 어떻게 변하는지 확인했다.

확인 대상은 다음과 같다.

- 50VU에서도 실패가 `DELIVERY_014` lock timeout에 머무는지
- hub-first 이후 timeout failedKey가 계속 hub key로 관측되는지
- 20VU 대비 TPS, p95, 실패율이 어떻게 변하는지
- DB 반영량이 k6 성공 건수와 일치하는지
- outbox publish가 테스트 종료 시점에 따라잡는지

## 2. 테스트 전 상태

테스트 전 점검 결과는 다음과 같다.

| 항목 | 결과 |
| --- | --- |
| Config Server / Eureka | 정상 |
| Eureka 등록 | `COMPANY-SERVICE`, `HUB-SERVICE`, `DELIVERY-SERVICE` 등록 확인 |
| 핵심 서비스 health | company, hub, delivery 모두 정상 |
| Redis 배송 lock 잔여 | 0 |
| delivery outbox backlog | 없음, `PUBLISHED 40,430` |

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 02 - 50VU Hub First, Lock Wait 2s |
| 시작 시간 | 2026-07-02 11:45:58 KST |
| 종료 시간 | 2026-07-02 11:54:02 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Redis lock 획득 순서 `hub -> company -> unknown` |
| k6 로그 | `/tmp/hublink-k6-50vu-redis-lock-order-run02.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,960 |
| HTTP TPS | 20.75 req/s |
| 성공 요청 수 | 9,951 |
| 실패 요청 수 | 9 |
| 실패율 | 0.09% |
| checks 성공률 | 99.90% |
| 평균 응답 시간 | 1.96s |
| median 응답 시간 | 2.19s |
| p90 응답 시간 | 2.37s |
| p95 응답 시간 | 2.49s |
| p99 응답 시간 | 3.01s |
| 최대 응답 시간 | 3.96s |

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

실패 9건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
```

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 43,551 | 9,951 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 87,102 | 19,902 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 43,551 | 9,951 |

DB 반영량은 k6 성공 요청 수 9,951건과 일치한다. route history는 배송 1건당 2건씩 생성되어 19,902건 증가했다.

outbox는 테스트 직후 publish 처리량이 생성량을 따라잡지 못했다.

| 확인 시점 | PUBLISHED | PENDING |
| --- | ---: | ---: |
| 테스트 직후 | 29,600 | 11,311 |
| 60초 뒤 | 33,900 | 9,651 |

배송 생성과 outbox 적재 정합성은 맞지만, 50VU 생성량에서는 outbox publisher가 종료 직후까지 backlog를 해소하지 못했다.

Redis 배송 lock 잔여는 테스트 종료 후 0건이었다.

## 6. 서버 로그 결과

delivery-service 로그 기준 lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:hub:10000000-0000-0000-0000-000000000001` | 9 |

50VU에서도 timeout failedKey는 hub key 1개로 유지됐다. company key timeout은 관측되지 않았다.

## 7. Prometheus 계측 결과

Prometheus `increase()` 결과는 scrape 간격 때문에 건수에 외삽이 들어갈 수 있다. 따라서 실패 건수는 k6와 로그 기준 9건을 사용하고, Prometheus는 지연 시간과 병목 위치 분석에 사용했다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| hub acquired | 0.761s | 1.998s |
| hub timeout | 2.001s | 2.003s |
| company acquired | 0.000s | 0.013s |

hub lock을 먼저 잡는 구조에서 50VU timeout도 hub key에서 발생했다. 다만 20VU run01 대비 hub acquired 평균 wait는 0.952s에서 0.761s로 낮았고, timeout 건수도 86건에서 9건으로 줄었다.

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.044s | 0.316s |

락 내부 실제 점유 시간은 평균 44ms로 20VU보다 낮게 관측됐다. 평균 hold 자체는 길지 않다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.011s | 0.090s |
| `hub_delivery` / `read` | 0.016s | 0.125s |
| `mixed` / `increase` | 0.001s | 0.025s |

집계 write는 평균 1ms 미만이다. 50VU에서도 집계 write는 주요 병목으로 보이지 않는다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.029s |
| `route_history_save_all` | 0.000s | 0.027s |
| `outbox_enqueue` | 0.008s | 0.057s |
| `deadline_event_register` | 0.000s | 0.007s |
| `total_transaction` | 0.009s | 0.058s |

배송 저장 트랜잭션은 평균 9ms 수준으로 낮다. 다만 outbox publish는 종료 후 backlog가 크게 남아 별도 병목으로 분리해서 봐야 한다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| delivery-service active 최대 | 20 |
| delivery-service pending 최대 | 32 |

50VU에서는 Hikari active가 max 20에 도달했고 pending 최대가 32까지 증가했다. lock timeout은 줄었지만, connection pool 대기가 20VU보다 뚜렷해졌다.

## 8. 20VU와 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | failedKey |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Redis lock order run01 20VU | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | - | hub |
| Redis lock order run02 50VU | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | hub |

50VU는 20VU보다 TPS가 14.40에서 20.75로 증가했고 실패율은 1.24%에서 0.09%로 낮아졌다. 반면 p95는 1.79s에서 2.49s로 증가했고, Hikari pending 최대는 2에서 32로 증가했다.

이 결과는 단순히 VU가 높을수록 실패가 증가한다는 형태가 아니다. 20VU와 50VU 사이에는 lock 대기 타이밍, DB connection pool 대기, outbox publisher backlog가 함께 변한다.

## 9. 락 순서 변경 전후 비교

50VU는 Redis lock scope reduction 단계에서 같은 VU 결과가 없어서, lock 순서 변경 전 대표값은 `pool-tuning run03`으로 비교한다. 20VU는 직전 단계인 `redis-lock-scope run02`와 안정적으로 재측정된 `pool-tuning run02`를 함께 둔다.

| VU | 구분 | 비교 run | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| 20 | 변경 전 대표값 | `pool-tuning run02` | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s | company | 2 |
| 20 | 변경 전 직전값 | `redis-lock-scope run02` | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | 2.36s | company | 2 |
| 20 | 변경 후 | `redis-lock-order run01` | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | - | hub | 2 |
| 50 | 변경 전 대표값 | `pool-tuning run03` | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | company | 32 |
| 50 | 변경 후 | `redis-lock-order run02` | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | hub | 32 |

50VU 기준으로는 lock 순서 변경 후 TPS가 18.95에서 20.75로 증가했고, p95는 3.01s에서 2.49s로 감소했으며, 실패는 43건에서 9건으로 줄었다. Hikari pending 최대는 32로 동일해, 이 개선은 connection pool 여유가 생겼다기보다 lock 대기 위치와 타이밍이 바뀐 영향으로 보는 편이 맞다.

20VU 기준으로는 안정 재측정 대표값 대비 실패 건수는 84건에서 86건으로 거의 같고, p95와 TPS는 개선됐다. 직전 `redis-lock-scope run02`와 비교하면 실패는 205건에서 86건으로 줄었지만, 20VU 결과 자체가 변동성이 있었기 때문에 단일 run만으로 성능 개선을 단정하기는 어렵다.

따라서 이 단계의 가장 중요한 관찰은 성능 수치 자체보다 timeout key가 company에서 hub로 이동했다는 점이다. 기존 company 병목 판단은 lock 획득 순서의 영향을 받은 결과였고, 현재 입력에서는 공통 hub key가 먼저 잡히면서 대기 지점으로 드러났다.

## 10. 분석

이번 50VU 결과는 두 가지를 보여준다.

첫째, hub-first lock 순서에서는 timeout failedKey가 계속 hub key로 관측된다. 기존 company timeout은 lock 획득 순서의 영향을 받은 관측 결과였고, 현재 입력에서는 공통 hub key가 실제 직렬화 지점으로 드러난다.

둘째, 50VU에서는 Redis lock timeout보다 Hikari pending과 outbox backlog가 더 눈에 띈다. lock timeout은 9건으로 낮았지만 delivery-service Hikari pending은 최대 32까지 상승했고, outbox는 테스트 종료 후 60초가 지나도 9,651건이 PENDING으로 남았다.

따라서 50VU의 병목은 한 가지로 고정하기 어렵다. 요청 수가 늘면서 처리량은 올라갔지만, DB connection pool 대기와 비동기 outbox publish backlog가 함께 커졌다.

## 11. 결론

50VU hub-first run은 모든 k6 threshold를 통과했고, 실패율은 0.09%로 낮았다. DB 반영량도 k6 성공 건수와 정확히 일치했다.

다만 timeout failedKey는 여전히 hub key로 관측됐고, Hikari pending 최대 32와 outbox backlog가 새로 두드러졌다. 다음 80VU run에서는 Redis lock timeout 증가 여부뿐 아니라 Hikari pending과 outbox backlog가 처리량 한계로 이어지는지 같이 확인해야 한다.
