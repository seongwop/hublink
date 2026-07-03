# Delivery Assignment Redis Lock Order Run 05 - 50VU Company First, Lock Wait 2s 결과

## 1. 테스트 목적

Redis lock 획득 순서를 다시 `company -> hub -> unknown`으로 되돌린 뒤, hub-first 50VU 결과와 비교하기 위해 동일 조건으로 50VU를 재측정했다.

확인 대상은 다음과 같다.

- company-first 복원 후 timeout key가 다시 company key로 이동하는지
- hub-first 50VU 대비 TPS, p95, 실패율이 어떻게 달라지는지
- 외부 hub/user 통신 실패(`DELIVERY_011`, circuit breaker open)가 재현되는지
- DB 반영량과 k6 성공 건수가 일치하는지
- outbox publish backlog와 Hikari pending이 어느 정도 남는지

## 2. 테스트 전 상태

테스트 전 outbox backlog와 Redis lock 잔여를 정리한 뒤 실행했다.

| 항목 | 결과 |
| --- | --- |
| delivery-service health | `UP` |
| Config Server / Eureka | 정상 |
| Redis lock 잔여 | 0 |
| outbox backlog | `PUBLISHED`만 존재 |
| 배포 코드 | `origin/develop` 기준 company-first lock 순서 반영 |

SSH 스트림에 직접 묶어 실행한 이전 시도는 중간에 연결이 끊기면서 최종 k6 요약이 남지 않아 결과에서 제외했다. 이 문서는 detached 방식으로 다시 실행해 정상 종료된 결과만 기록한다.

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Order Run 05 - 50VU Company First, Lock Wait 2s |
| 시작 시간 | 2026-07-03 16:15:12 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| lock order | `company -> hub -> unknown` |
| k6 로그 | `/tmp/hublink-k6-50vu-company-first-detached-20260703T071512Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 8,542 |
| HTTP TPS | 17.79 req/s |
| 성공 요청 수 | 8,486 |
| 실패 요청 수 | 56 |
| 실패율 | 0.65% |
| checks 성공률 | 99.34% |
| 평균 응답 시간 | 2.29s |
| median 응답 시간 | 2.47s |
| p90 응답 시간 | 2.99s |
| p95 응답 시간 | 3.18s |
| p99 응답 시간 | 3.65s |
| 최대 응답 시간 | 6.03s |

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

실패는 모두 HTTP 409 `DELIVERY_014`였다. 이번 정상 run에서는 `DELIVERY_011` 외부 통신 실패와 circuit breaker not permitted 증가는 관측되지 않았다.

```text
status=409
message=DELIVERY_014
```

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 42,086 | 8,486 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 84,172 | 16,972 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 42,086 | 8,486 |

DB 반영량은 k6 성공 요청 수 8,486건과 일치한다. route history는 배송 1건당 2건씩 생성되어 16,972건 증가했다.

outbox 상태:

| 조회 시점 | PUBLISHED | PENDING |
| --- | ---: | ---: |
| 테스트 직후 | 33,700 | 8,386 |
| 추가 조회 시점 | 39,500 | 2,586 |

배송 생성과 outbox 적재 정합성은 맞지만, 50VU 종료 후 outbox publisher는 즉시 backlog를 모두 해소하지 못했다.

Redis lock 잔여는 테스트 종료 후 0건이었다.

## 6. Prometheus 계측 결과

카운터 계측은 scrape 시점에 따라 초반/후반 일부 샘플이 잘릴 수 있어, 실패 건수는 k6와 일치하는 `lock timeout 56건`을 기준으로 해석한다.

### Redis lock wait

| 구분 | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| company acquired | 8,406 | 7,172.42s | 0.853s |
| company timeout | 56 | 112.08s | 2.001s |
| hub acquired | 8,407 | 421.18s | 0.050s |

company-first로 되돌리자 timeout key도 다시 company로 이동했다. company lock wait는 평균 0.85s 수준이고, timeout은 lock wait 설정과 같은 2초까지 도달했다. hub lock wait는 평균 0.05s로 짧게 관측됐다.

### Redis lock hold

| lock_scope | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| mixed / success | 8,408 | 452.63s | 0.054s |

락 내부 실제 hold 시간은 평균 54ms 수준이다. 그럼에도 company lock wait가 길게 나타나는 이유는 요청들이 같은 company lock key 앞에서 줄을 서기 때문이다.

### Assignment count operation

| assignment_type / operation | 건수 | 합계 | 평균 |
| --- | ---: | ---: | ---: |
| `company_delivery` / `read` | 8,407 | 107.38s | 0.0128s |
| `hub_delivery` / `read` | 8,408 | 154.08s | 0.0183s |
| `mixed` / `increase` | 8,408 | 8.02s | 0.0010s |

집계 write는 평균 1ms 수준이라 이번 50VU에서도 주요 병목으로 보이지 않는다.

### Hikari / circuit breaker

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active 최대 | 20 |
| delivery-service Hikari pending 최대 | 32 |
| hub-service circuit breaker failed 증가 | 0 |
| user-service circuit breaker failed 증가 | 0 |
| circuit breaker not permitted 증가 | 0 |

Hikari active는 max 20에 도달했고 pending은 최대 32까지 증가했다. 외부 서비스 circuit breaker는 이번 정상 run에서는 열리지 않았다.

## 7. Hub-first 50VU와 비교

| 구분 | lock order | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| run02 | hub-first | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | hub | 32 |
| run05 | company-first | 8,542 | 8,486 | 56 | 0.65% | 17.79 | 3.18s | 3.65s | company | 32 |

같은 50VU 조건에서 company-first로 되돌리자 timeout key는 다시 company로 이동했고, 실패 건수는 9건에서 56건으로 늘었다. TPS는 20.75 req/s에서 17.79 req/s로 낮아졌고, p95는 2.49s에서 3.18s로 악화됐다.

Hikari pending 최대는 둘 다 32로 같다. 따라서 이번 차이는 connection pool 설정 차이라기보다 lock 획득 순서와 대기 위치 차이로 보는 편이 맞다.

## 8. 기존 company-first 대표값과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| pool-tuning run03 company-first | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | company | 32 |
| redis-lock-order run05 company-first | 8,542 | 8,486 | 56 | 0.65% | 17.79 | 3.18s | 3.65s | company | 32 |

company-first 재측정은 기존 company-first 대표값보다 약간 나쁘다. 다만 timeout key, 실패 유형, Hikari pending 최대값은 같은 패턴으로 유지된다.

이번 run은 hub-first가 무조건 모든 상황에서 빠르다는 결론보다는, 현재 입력 데이터에서 company-first는 company lock wait가 먼저 병목으로 드러나고 hub-first는 공통 hub key가 timeout key로 드러난다는 점을 확인한 비교 실험으로 보는 것이 맞다.

## 9. 결론

company-first 복원 50VU에서는 외부 통신 실패 없이 lock timeout만 발생했다. DB 반영량은 k6 성공 건수와 일치해 정합성 문제는 없었다.

다만 hub-first 50VU와 비교하면 company-first는 실패율, TPS, p95가 모두 나빠졌다. timeout key도 company로 다시 이동했다. 즉 이전에 company lock이 병목처럼 보였던 현상은 실제로도 company key 앞 대기가 컸지만, lock 순서를 바꾸면 관측되는 timeout key와 대기 분포가 달라진다는 점이 다시 확인됐다.

다음 비교는 같은 company-first 상태에서 80VU, 100VU를 이어서 측정해 hub-first 대비 차이가 부하 증가 구간에서도 유지되는지 확인하는 흐름이 적절하다.
