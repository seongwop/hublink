# Delivery Assignment Redis Lock Scope Run 01 - 20VU Lock Wait 2s 결과

## 1. 테스트 목적

Redis 분산락을 유지하되 락 안에서 수행하는 작업을 `배송 저장 + 경로 저장 + outbox 저장`까지 포함하던 구조에서 `담당자 선택 + 집계 증가 예약`까지만 수행하도록 줄인 뒤 20VU 조건을 다시 측정했다.

이번 테스트에서 확인하려는 내용은 다음과 같다.

- Redis lock hold 시간이 실제로 줄었는지
- lock hold 축소가 20VU 처리량과 실패율을 개선하는지
- 실패가 발생한다면 외부 서비스 통신 문제가 아니라 여전히 company lock wait인지
- 배송 저장, 경로 저장, outbox 저장이 락 밖으로 이동해도 DB 반영 정합성이 유지되는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Scope Run 01 - 20VU Lock Wait 2s |
| 시작 시간 | 2026-07-01 23:21:04 KST |
| 종료 시간 | 2026-07-01 23:29:09 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Redis 락 범위를 담당자 배정 예약 구간으로 축소 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6,223 |
| HTTP TPS | 12.96 req/s |
| 성공 요청 수 | 6,097 |
| 실패 요청 수 | 126 |
| 실패율 | 2.02% |
| checks 성공률 | 97.97% |
| 평균 응답 시간 | 1.26s |
| median 응답 시간 | 1.30s |
| p90 응답 시간 | 1.96s |
| p95 응답 시간 | 2.09s |
| p99 응답 시간 | 2.24s |
| 최대 응답 시간 | 2.49s |

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

실패 126건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
```

발생하지 않은 오류:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_011` user-service unavailable | 0 |
| `DELIVERY_013` hub-service unavailable | 0 |
| `IllegalAccessException` | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 39,697 | 6,097 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 79,394 | 12,194 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 39,697 | 6,097 |
| `PUBLISHED` outbox | 33,600 | 39,697 | 6,097 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 9,697 | 6,097 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 7,897 | 6,097 |

DB 반영량은 k6 성공 요청 수 6,097건과 일치한다. route history는 배송 1건당 2건씩 생성되어 12,194건 증가했다.

## 5. 서버 로그 결과

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 126 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |
| `DELIVERY_ASSIGNMENT_RESERVATION_COMPENSATED` | 0 |
| `DELIVERY_ASSIGNMENT_RESERVATION_COMPENSATION_FAILED` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 73 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 53 |

실패는 모두 company delivery manager lock에서 발생했다. hub lock timeout은 관측되지 않았다.

## 6. Prometheus 계측 결과

Prometheus의 `increase()` 계열 값은 scrape 간격 때문에 카운트가 약간 외삽될 수 있으므로, 건수는 k6/로그/DB를 기준으로 판단했다. Prometheus 값은 지연 시간과 병목 위치 분석에 사용했다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| company acquired | 1.028s | 2.003s |
| company timeout | 2.001s | 2.015s |
| hub acquired | 0.070s | 0.372s |

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.073s | 0.389s |

락 내부 작업을 배정 예약 구간으로 줄인 결과 lock hold는 평균 73ms 수준까지 짧아졌다. 하지만 company lock wait는 평균 1초, timeout은 2초에 도달했다. 즉 락을 잡고 있는 시간은 줄었지만 같은 company key로 몰리는 대기열은 아직 남아 있다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.016s | 0.125s |
| `hub_delivery` / `read` | 0.026s | 0.198s |
| `mixed` / `increase` | 0.002s | 0.065s |

집계 write 자체는 평균 2ms 미만으로 낮다. 이번 run의 주 병목은 mixed bulk upsert가 아니라 company lock wait 쪽으로 보는 것이 맞다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.027s |
| `route_history_save_all` | 0.000s | 0.027s |
| `outbox_enqueue` | 0.014s | 0.121s |
| `deadline_event_register` | 0.000s | 0.014s |
| `total_transaction` | 0.015s | 0.122s |

락 밖으로 빠진 배송 저장 트랜잭션은 평균 15ms 수준이다. 따라서 현재 20VU 실패를 배송 저장, 경로 저장, outbox 저장 비용으로 설명하기는 어렵다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| max pool | 20 |
| min idle | 3 |
| active 최대 | 20 |
| pending 최대 | 2 |

delivery-service의 Hikari active가 순간적으로 max 20까지 올라갔고 pending도 최대 2가 관측됐다. 다만 실패 원인은 connection timeout이 아니라 `DELIVERY_014` lock timeout으로 확인됐다.

## 7. 이전 20VU 결과와 비교

직전 안정 기준으로 사용하던 `pool-tuning run02` 20VU와 비교하면 다음과 같다.

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Pool tuning 20VU | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s |
| Redis lock scope 20VU | 6,223 | 6,097 | 126 | 2.02% | 12.96 | 2.09s | 2.24s |

Redis lock hold는 짧아졌지만 end-to-end 결과는 개선되지 않았다. 오히려 20VU 기준 TPS는 낮아지고 lock timeout은 증가했다.

이번 변화만 놓고 보면 "락 안쪽 작업을 줄이면 바로 성능이 오른다"는 결론은 아직 낼 수 없다. lock hold 평균은 73ms까지 줄었지만, lock wait 평균이 1초 수준으로 남아 있고 2초 timeout도 계속 발생했다.

## 8. 분석

이번 구현은 정합성 측면에서는 정상 동작했다. 성공한 6,097건은 배송, 경로, outbox, 집계 테이블에 모두 정확히 반영됐다. 또한 배송 저장이 실패했을 때 실행되는 집계 예약 보상 로그는 발생하지 않았다.

하지만 성능 측면에서는 20VU에서 기대한 개선이 나오지 않았다. 관측 결과상 문제는 다음과 같이 정리된다.

- Redis lock hold는 평균 73ms로 짧다.
- 배송 저장 트랜잭션도 평균 15ms로 짧다.
- 집계 mixed increase도 평균 2ms 미만이다.
- 그런데 company lock wait는 평균 1초, timeout은 2초까지 도달한다.
- 실패 lock key는 company key 2개에만 집중된다.

즉 현재 20VU 병목은 "락 안에서 오래 작업해서 생기는 문제"라기보다 "company delivery manager 배정 key가 2개로 집중되어 대기열이 쌓이는 문제"에 가깝다.

## 9. 결론

Redis 락 범위 축소는 코드 구조상 임계구간을 줄이는 방향으로는 맞다. Prometheus 계측상 lock hold도 평균 73ms로 낮게 관측됐다.

다만 이번 20VU run에서는 성능 개선으로 이어지지 않았다. 실패율은 1.27%에서 2.02%로 증가했고, TPS도 13.77 req/s에서 12.96 req/s로 낮아졌다. 따라서 이 변경만으로는 병목 해소 효과가 부족하다.

다음 단계에서는 같은 구현으로 50VU, 80VU를 이어서 측정해 부하가 커졌을 때의 추세를 확인해야 한다. 만약 동일하게 company lock wait가 지배적이면, 다음 최적화 후보는 Redis lock 범위 추가 축소보다 company lock key 분산 또는 DB row lock 기반 원자적 배정으로 보는 것이 자연스럽다.
