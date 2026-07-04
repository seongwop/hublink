# Delivery Assignment Post Optimization Run 02 - 50VU Outbox Text, Lock Wait 2s 결과

## 1. 테스트 목적

20VU 공식 run01에서 Outbox `text` 저장 방식이 정상 동작하고 threshold를 통과했다. 이번 run은 같은 코드와 seed 조건에서 50VU까지 부하를 올렸을 때 다음을 확인하기 위한 검증이다.

- Outbox 직접 insert + `text` 저장 방식이 50VU에서도 500 없이 동작하는지
- Redis lock timeout 실패율이 허용 범위 안에 들어오는지
- 성공 요청 수와 DB 반영 건수가 일치하는지
- Outbox publish backlog가 생기는지, 생긴다면 얼마나 늦게 해소되는지
- Hikari pending, lock wait, lock hold, Outbox insert 계측이 어느 수준인지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Post Optimization Run 02 - 50VU Outbox Text, Lock Wait 2s |
| 시작 시간 | 2026-07-04 22:09:17 KST |
| 종료 시간 | 2026-07-04 22:17:17 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Outbox payload, last_error `text` 저장 방식 적용 |
| k6 로그 | `/tmp/hublink-k6-50vu-outbox-text-20260704T130917Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9,731 |
| HTTP TPS | 20.27 req/s |
| 성공 요청 수 | 9,704 |
| 실패 요청 수 | 27 |
| 실패율 | 0.27% |
| checks 성공률 | 99.72% |
| 평균 응답 시간 | 2.01s |
| median 응답 시간 | 2.18s |
| p90 응답 시간 | 2.39s |
| p95 응답 시간 | 2.49s |
| p99 응답 시간 | 3.13s |
| 최대 응답 시간 | 5.01s |

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

실패 27건은 모두 배송 기사 배정 lock timeout으로 인한 HTTP 409이다.

| 오류 | 건수 |
| --- | ---: |
| `DELIVERY_014` / HTTP 409 | 27 |
| HTTP 500 | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 43,304 | 9,704 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 86,608 | 19,408 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 43,304 | 9,704 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 9,704건과 일치한다. route history는 배송 1건당 2건씩 생성되어 19,408건 증가했다.

## 5. Outbox 처리 상태

50VU에서는 테스트 직후 Outbox publisher가 생성량을 완전히 따라잡지 못했다.

| 확인 시점 | PUBLISHED | PENDING | FAILED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 33,000 | 9,944 | 360 |
| 약 70초 후 | 40,600 | 2,704 | 0 |
| 약 190초 후 | 43,304 | 0 | 0 |

최종적으로 backlog는 모두 해소됐다. 다만 50VU 종료 직후에는 약 1만 건 가까운 pending이 발생했으므로, Outbox publish 처리량은 이후 부하에서 별도 관찰이 필요하다.

Redis 배송 lock 잔여는 테스트 종료 후 0건이다.

## 6. 서버 로그 결과

Outbox `oid` 타입 불일치 오류와 HTTP 500은 재발하지 않았다.

이번 실패는 모두 Redis lock wait 2초 초과이며, failedKey는 hub-first 순서에서 먼저 획득하는 공통 hub key로 확인됐다.

```text
failedKey=lock:delivery:hub:10000000-0000-0000-0000-000000000001
waitMillis=2000
```

## 7. Prometheus / Grafana 계측 확인

Grafana에서 보는 Prometheus 원천 메트릭을 직접 조회했다. `increase()` 계열은 scrape 보정 때문에 k6/log의 정확한 실패 건수와 다를 수 있으므로, 정확한 요청/실패 건수는 k6와 애플리케이션 로그를 기준으로 판단했다.

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 32 |
| Redis lock hold avg | 44.6ms |
| Outbox `insert_on_conflict` avg | 1.22ms |
| Delivery `total_transaction` avg | 1.85ms |

계측상 Outbox insert 평균은 1.22ms 수준으로 낮다. 배송 생성 트랜잭션 평균도 2ms 미만으로 관찰되어, 이번 50VU 병목은 Outbox insert 자체보다는 Redis hub lock wait과 Hikari pending 쪽 영향이 더 크다.

## 8. 이전 50VU 결과와 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Pool tuning 50VU | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | company |
| Redis lock order 50VU - hub-first | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | hub |
| Post optimization 50VU - outbox text | 9,731 | 9,704 | 27 | 0.27% | 20.27 | 2.49s | 3.13s | hub |

이번 50VU는 pool tuning 단계보다 좋고, hub-first 최고 결과보다는 약간 나쁘다. p95는 hub-first 50VU와 같은 2.49s이고, TPS는 20.75에서 20.27로 소폭 낮아졌다. 실패 건수는 9건에서 27건으로 증가했다.

## 9. 분석

Outbox `text` 전환과 직접 insert 경로는 50VU에서도 기능적으로 안정적이다. HTTP 500과 PostgreSQL 타입 오류가 없고, DB 반영 건수도 k6 성공 건수와 정확히 일치한다.

성능 측면에서는 50VU threshold를 모두 통과했다. 20VU 공식 run01에서 실패율이 2.63%였던 것과 비교하면, 50VU에서는 실패율이 0.27%로 오히려 낮았다. 이는 20VU 예비 실행처럼 변동성이 있는 구간이 존재하며, 단일 run만으로 결론을 내리기 어렵다는 점을 다시 보여준다.

다만 Outbox publish backlog는 새로 주목해야 한다. 테스트 직후 pending 9,944건이 발생했고, 약 190초 후에야 모두 해소됐다. 배송 생성 트랜잭션 자체에는 큰 영향을 주지 않았지만, 부하가 더 올라가면 outbox publisher 지연이 운영 관점의 후속 병목이 될 수 있다.

## 10. 결론

50VU 검증은 성공이다.

- k6 threshold 전체 통과
- HTTP 500 없음
- Outbox payload 타입 오류 없음
- DB 정합성 일치
- Redis lock 잔여 없음
- 최종 outbox backlog 해소

다음 단계로 80VU를 진행할 수 있다. 다만 80VU부터는 p95/p99와 Hikari pending뿐 아니라 테스트 직후 outbox pending 규모와 해소 시간도 같이 기록해야 한다.
