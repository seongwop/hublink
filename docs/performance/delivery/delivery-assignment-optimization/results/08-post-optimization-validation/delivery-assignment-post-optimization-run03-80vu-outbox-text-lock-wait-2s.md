# Delivery Assignment Post Optimization Run 03 - 80VU Outbox Text, Lock Wait 2s 결과

## 1. 테스트 목적

20VU, 50VU에서 Outbox `text` 저장 방식과 직접 insert 경로가 정상 동작하는 것을 확인했다. 이번 run은 동일한 코드와 동일한 seed 조건에서 80VU까지 부하를 올렸을 때 응답 시간, Redis lock timeout, Hikari pending, Outbox backlog가 어떻게 변하는지 확인하기 위한 검증이다.

- 80VU에서 HTTP 500 없이 동작하는지 확인
- Redis lock wait 2초 조건에서 실패율이 허용 범위 안에 들어오는지 확인
- Hikari pool active/pending 추이를 Grafana/Prometheus 지표로 확인
- Outbox insert 최적화 이후에도 publish backlog가 최종 해소되는지 확인
- DB 반영 건수와 k6 성공 건수가 일치하는지 확인

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Post Optimization Run 03 - 80VU Outbox Text, Lock Wait 2s |
| 시작 시간 | 2026-07-04 22:25:10 KST |
| 종료 시간 | 2026-07-04 22:33:10 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Outbox payload, last_error `text` 저장 방식 적용 |
| k6 로그 | `/tmp/hublink-k6-80vu-outbox-text-20260704T132510Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 10,343 |
| HTTP TPS | 21.54 req/s |
| 성공 요청 수 | 10,328 |
| 실패 요청 수 | 15 |
| 실패율 | 0.14% |
| checks 성공률 | 99.85% |
| 평균 응답 시간 | 3.02s |
| median 응답 시간 | 3.43s |
| p90 응답 시간 | 3.68s |
| p95 응답 시간 | 3.82s |
| p99 응답 시간 | 4.23s |
| 최대 응답 시간 | 6.70s |

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

p95 응답 시간이 3초를 넘으면서 latency threshold는 실패했다. 다만 요청 실패율은 0.14%로 낮고, 실패 15건은 모두 Redis lock wait 2초 초과에 따른 HTTP 409였다.

| 오류 | 건수 |
| --- | ---: |
| `DELIVERY_014` / HTTP 409 | 15 |
| HTTP 500 | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 43,928 | 10,328 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 87,856 | 20,656 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 43,928 | 10,328 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 10,328건과 일치한다. route history는 배송 1건당 2건씩 생성되어 20,656건 증가했다.

## 5. Outbox 처리 상태

테스트 직후에는 Outbox publisher가 아직 pending 이벤트를 모두 처리하지 못한 상태였다.

| 확인 시점 | PUBLISHED | PENDING | FAILED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 43,800 | 128 | 0 |
| 최종 확인 | 43,928 | 0 | 0 |

최종적으로 Outbox backlog는 모두 해소됐다. 50VU와 비교하면 테스트 직후 pending 규모가 크게 줄었고, Outbox 저장 자체도 HTTP 500을 만들지 않았다.

Redis 배송 lock 잔여는 테스트 종료 후 0건이었다.

## 6. 서버 로그 결과

실패 로그는 모두 Redis lock timeout이며, failedKey는 hub-first 순서에서 먼저 획득하는 공통 hub key였다.

```text
failedKey=lock:delivery:hub:10000000-0000-0000-0000-000000000001
waitMillis=2000
```

HTTP 500, Outbox payload 타입 오류, fallback 접근자 오류는 발생하지 않았다.

## 7. Prometheus / Grafana 계측 확인

Grafana 대시보드의 원천인 Prometheus 지표를 테스트 중간과 종료 후 직접 조회했다.

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 62 |
| hub lock timeout 증가량 | 약 15.65 |
| hub lock acquired 평균 대기 | 760ms |
| Redis lock hold 평균 | 43.0ms |
| Outbox `insert_on_conflict` 평균 | 1.21ms |
| Delivery `total_transaction` 평균 | 1.77ms |

`increase()` 계열 Prometheus 값은 scrape 보정 때문에 k6/log의 정확한 실패 건수와 소수점 차이가 날 수 있다. 정확한 실패 건수는 k6 로그와 애플리케이션 로그 기준으로 15건이다.

## 8. 이전 run과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Post optimization 20VU | 6,681 | 6,505 | 176 | 2.63% | 13.92 | 2.07s | 2.17s | - |
| Post optimization 50VU | 9,731 | 9,704 | 27 | 0.27% | 20.27 | 2.49s | 3.13s | 32 |
| Post optimization 80VU | 10,343 | 10,328 | 15 | 0.14% | 21.54 | 3.82s | 4.23s | 62 |

80VU는 실패율만 보면 20VU, 50VU보다 낮다. 그러나 p95가 3.82s로 상승해 latency threshold를 넘었고, Hikari pending도 62까지 증가했다. 즉 80VU부터는 성공률보다 응답 지연과 커넥션 대기가 더 중요한 신호가 된다.

## 9. 분석

Outbox 최적화는 기능적으로 안정화됐다. payload `text` 전환 이후 HTTP 500은 재발하지 않았고, Outbox insert 평균도 약 1.21ms 수준으로 낮게 유지됐다. 테스트 직후 pending도 128건만 남았고 최종적으로 모두 publish됐다.

이번 80VU의 직접적인 제한 요인은 Outbox insert가 아니라 Redis hub lock 대기와 Hikari pending이다. lock hold 평균은 약 43ms로 짧지만, 같은 hub key에 요청이 몰리면서 lock acquired 평균 대기가 760ms까지 올라갔다. 여기에 Hikari pending이 62까지 증가하면서 응답 시간이 밀렸고, 결과적으로 p95가 3초 기준을 넘었다.

다만 실패 건수는 15건으로 낮다. 이전 20VU 결과처럼 저부하에서도 실패율 변동이 크게 나타난 적이 있으므로, 단일 run만으로 80VU의 실패율이 실제로 더 안정적이라고 단정하기는 어렵다. 현재까지는 80VU에서 처리량은 증가했지만 latency 한계가 드러난 것으로 보는 편이 안전하다.

## 10. 결론

80VU 검증은 부분 성공이다.

- HTTP 500 없음
- Outbox payload 타입 오류 없음
- DB 정합성 일치
- Redis lock 잔여 없음
- 최종 Outbox backlog 해소
- 실패율 0.14%로 낮음
- p95 3.82s로 latency threshold 실패
- Hikari pending max 62로 커넥션 대기 증가

다음 단계에서는 100VU를 바로 올리기 전에 80VU 결과를 기준으로 Hikari pending과 hub lock wait이 어디까지 증가하는지 계속 추적해야 한다. Outbox 저장 경로는 더 이상 주요 실패 원인으로 보이지 않으며, 현재 병목은 lock 대기와 DB 커넥션 대기 쪽에 더 가깝다.
