# Delivery Assignment Redis Lock Order Run 01 - 20VU Hub First, Lock Wait 2s 결과

## 1. 테스트 목적

Redis 분산락 범위 축소 이후에도 20VU에서 `DELIVERY_014` lock timeout이 계속 발생했다. 기존 계측에서는 timeout failedKey가 company lock으로 관측됐지만, 실제 병목이 company key 자체인지 또는 먼저 잡힌 company lock 뒤에서 hub lock 대기가 숨겨진 것인지 분리할 필요가 있었다.

이번 run은 Redis lock 획득 순서를 `hub -> company -> unknown`으로 변경한 뒤 20VU를 측정했다.

확인 대상은 다음과 같다.

- 서버 기동 문제 없이 정상 테스트가 가능한지
- lock timeout failedKey가 company에서 hub로 이동하는지
- lock 순서 변경이 20VU 실패율과 응답 시간에 영향을 주는지
- DB 반영량이 k6 성공 건수와 일치하는지
- outbox publish가 테스트 종료 후 따라잡는지

## 2. 테스트 전 상태

테스트 전 점검 결과는 다음과 같다.

| 항목 | 결과 |
| --- | --- |
| VM 상태 | 6대 모두 `RUNNING` |
| Config Server | 정상 |
| Eureka 등록 | `COMPANY-SERVICE`, `HUB-SERVICE`, `DELIVERY-SERVICE` 등록 확인 |
| 핵심 서비스 health | company, hub, delivery 모두 정상 |
| Redis 배송 lock 잔여 | 0 |
| delivery outbox backlog | 없음, `PUBLISHED 38,803` |
| 중복 k6 프로세스 | 없음 |

이번 테스트 직전에는 이전에 관측된 company-service Config Server 선부팅 문제가 재현되지 않았다.

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 01 - 20VU Hub First, Lock Wait 2s |
| 시작 시간 | 2026-07-02 11:10:24 KST |
| 종료 시간 | 2026-07-02 11:18:29 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Redis lock 획득 순서 `hub -> company -> unknown` |
| k6 로그 | `/tmp/hublink-k6-20vu-redis-lock-order-run03.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6,916 |
| HTTP TPS | 14.40 req/s |
| 성공 요청 수 | 6,830 |
| 실패 요청 수 | 86 |
| 실패율 | 1.24% |
| checks 성공률 | 98.75% |
| 평균 응답 시간 | 1.13s |
| median 응답 시간 | 1.19s |
| p90 응답 시간 | 1.62s |
| p95 응답 시간 | 1.79s |
| 최대 응답 시간 | 2.30s |

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

실패 86건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

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

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 40,430 | 6,830 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 80,860 | 13,660 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 40,430 | 6,830 |

DB 반영량은 k6 성공 요청 수 6,830건과 일치한다. route history는 배송 1건당 2건씩 생성되어 13,660건 증가했다.

outbox는 테스트 직후 `PENDING 730`건이 남았지만 40초 뒤 모두 publish됐다.

| 확인 시점 | PUBLISHED | PENDING |
| --- | ---: | ---: |
| 테스트 직후 | 39,700 | 730 |
| 40초 뒤 | 40,430 | 0 |

Redis 배송 lock 잔여는 테스트 종료 후에도 0건이었다.

## 6. 서버 로그 결과

delivery-service 로그 기준 lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:hub:10000000-0000-0000-0000-000000000001` | 86 |

기존 Redis lock scope run에서는 failedKey가 company key 2개로 관측됐다. 이번 run에서는 lock 획득 순서를 hub 우선으로 바꾸면서 timeout failedKey가 hub key 1개로 이동했다.

이 결과는 company key가 유일한 원인이라기보다, 공통 hub key도 강한 직렬화 지점이라는 점을 보여준다.

## 7. Prometheus 계측 결과

Prometheus `increase()` 결과는 scrape 간격 때문에 건수에 외삽이 들어갈 수 있다. 따라서 실패 건수는 k6와 로그 기준 86건을 사용하고, Prometheus는 지연 시간과 병목 위치 분석에 사용했다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| hub acquired | 0.952s | 2.003s |
| hub timeout | 2.001s | 2.014s |
| company acquired | 0.001s | 0.059s |

hub lock을 먼저 잡도록 변경하자, hub lock wait가 평균 0.95s까지 상승했다. 반대로 company lock acquired wait는 평균 1ms 수준으로 내려갔다.

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.064s | 0.300s |

락 내부 실제 점유 시간은 평균 64ms, 최대 300ms 수준이다. 평균 hold 자체는 길지 않지만, 모든 요청이 공통 hub key를 먼저 통과하면서 hub wait queue가 형성된다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.014s | 0.133s |
| `hub_delivery` / `read` | 0.023s | 0.166s |
| `mixed` / `increase` | 0.001s | 0.024s |

집계 write는 여전히 평균 1ms 수준이다. read 쪽도 최대 100ms대에 머물러 있어, 이번 실패를 집계 테이블 write 비용만으로 설명하기는 어렵다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.029s |
| `route_history_save_all` | 0.000s | 0.035s |
| `outbox_enqueue` | 0.012s | 0.080s |
| `deadline_event_register` | 0.000s | 0.021s |
| `total_transaction` | 0.013s | 0.082s |

배송 저장 트랜잭션은 평균 13ms 수준으로, Redis lock timeout의 직접 원인으로 보기는 어렵다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| delivery-service active 최대 | 20 |
| delivery-service pending 최대 | 2 |

delivery-service Hikari active는 max 20까지 사용됐고 pending 최대 2가 관측됐다. 다만 실패 원인은 connection timeout이 아니라 Redis lock wait timeout이다.

## 8. 이전 20VU 결과와 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | timeout failedKey |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning 20VU 대표값 | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | company |
| Redis lock scope run01 | 6,223 | 6,097 | 126 | 2.02% | 12.96 | 2.09s | company |
| Redis lock scope run02 | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | company |
| Redis lock order run01 | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | hub |

이번 run은 20VU 기준으로 TPS와 p95가 가장 좋고 실패율도 pool tuning 대표값과 거의 같은 수준이다. 다만 실패 위치가 company에서 hub로 이동했기 때문에, lock 순서 변경을 성능 개선으로 단정하기보다는 병목 위치를 확인한 진단 run으로 보는 편이 맞다.

## 9. 분석

이번 결과에서 가장 중요한 점은 timeout failedKey 이동이다.

기존 순서에서는 company lock을 먼저 잡았기 때문에 timeout이 company key에서 관측됐다. 하지만 hub lock을 먼저 잡도록 바꾸자 timeout이 hub key 1개로 이동했다. 즉 기존의 "company lock 병목" 해석은 관측 순서의 영향을 받은 면이 있다.

현재 seed와 입력 조건에서는 supplier가 Seoul hub에 고정되어 있고, 배송 경로가 공통 hub key를 포함한다. 따라서 요청이 분산 receiver 18개로 들어와도 Redis lock 관점에서는 공통 hub key를 통과해야 한다.

정리하면 다음과 같다.

- lock hold 평균은 64ms로 짧다.
- 집계 증가 write 평균은 1ms 수준이다.
- 배송 저장 트랜잭션 평균도 13ms 수준이다.
- 그러나 hub lock wait 평균은 0.95s이고 timeout은 2s에 도달한다.
- lock 순서를 바꾸면 timeout 위치가 company에서 hub로 이동한다.

따라서 지금 병목은 특정 DB write 비용보다 Redis lock key 직렬화 구조에 가깝다. 특히 hub key가 공통으로 묶이는 현재 테스트 입력에서는 hub lock이 강한 직렬화 지점으로 드러난다.

## 10. 결론

Redis lock 획득 순서를 hub 우선으로 변경한 20VU run은 모든 k6 threshold를 통과했고, DB 반영 정합성도 맞았다.

다만 실패 86건은 모두 `DELIVERY_014`였고, failedKey는 전부 `lock:delivery:hub:10000000-0000-0000-0000-000000000001`이었다. 이로써 기존 company lock timeout은 company key만의 문제가 아니라, lock 획득 순서에 따라 먼저 대기하는 key가 드러나는 구조적 병목일 가능성이 커졌다.

다음 run은 같은 구현으로 50VU를 측정해 hub-first 상태에서 부하 증가 시 timeout이 어떻게 증가하는지 확인한다.
