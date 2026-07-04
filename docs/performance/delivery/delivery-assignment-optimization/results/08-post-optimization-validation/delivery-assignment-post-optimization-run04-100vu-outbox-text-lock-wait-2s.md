# Delivery Assignment Post Optimization Run 04 - 100VU Outbox Text, Lock Wait 2s 결과

## 1. 테스트 목적

20VU, 50VU, 80VU까지 Outbox `text` 저장 방식과 직접 insert 경로를 검증했다. 이번 run은 동일한 코드와 동일한 seed 조건에서 100VU까지 부하를 올렸을 때 처리량, 응답 시간, Redis lock timeout, Hikari pending, Outbox backlog 해소 여부를 확인하기 위한 검증이다.

- 100VU에서 HTTP 500 없이 동작하는지 확인
- Redis lock wait 2초 조건에서 실패율이 허용 범위 안에 들어오는지 확인
- p95/p99 응답 시간이 어느 수준까지 증가하는지 확인
- Grafana 대시보드 원천 지표인 Prometheus에서 Hikari pending, lock wait, lock hold, Outbox insert 지표 확인
- 테스트 종료 후 Outbox pending이 최종적으로 모두 해소되는지 확인
- DB 반영 건수와 k6 성공 건수가 일치하는지 확인

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Post Optimization Run 04 - 100VU Outbox Text, Lock Wait 2s |
| 시작 시간 | 2026-07-04 22:39:11 KST |
| 종료 시간 | 2026-07-04 22:47:15 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Outbox payload, last_error `text` 저장 방식 적용 |
| k6 로그 | `/tmp/hublink-k6-100vu-outbox-text-20260704T223907Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 10,728 |
| HTTP TPS | 22.34 req/s |
| 성공 요청 수 | 10,725 |
| 실패 요청 수 | 3 |
| 실패율 | 0.02% |
| checks 성공률 | 99.97% |
| 평균 응답 시간 | 3.65s |
| median 응답 시간 | 4.21s |
| p90 응답 시간 | 4.43s |
| p95 응답 시간 | 4.52s |
| p99 응답 시간 | 4.71s |
| 최대 응답 시간 | 7.89s |

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

p95 응답 시간이 4.52초까지 증가해 latency threshold는 실패했다. 다만 실패율은 0.02%로 낮았고, 실패 3건은 모두 Redis lock wait 2초 초과에 따른 HTTP 409였다.

| 오류 | 건수 |
| --- | ---: |
| `DELIVERY_014` / HTTP 409 | 3 |
| HTTP 500 | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 4. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 44,325 | 10,725 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 88,650 | 21,450 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 44,325 | 10,725 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 10,725건과 일치한다. route history는 배송 1건당 2건씩 생성되어 21,450건 증가했다.

## 5. Outbox 처리 상태

테스트 종료 직후에는 Outbox publisher가 아직 모든 pending 이벤트를 처리하지 못했다.

| 확인 시점 | PUBLISHED | PENDING | FAILED |
| --- | ---: | ---: | ---: |
| 테스트 직후 | 35,500 | 8,825 | 0 |
| 최종 확인 | 44,325 | 0 | 0 |

최종적으로 Outbox backlog는 모두 해소됐다. 100VU에서는 실행 중과 종료 직후 pending이 크게 쌓였지만, publish 실패로 고착되지는 않았다.

Redis 배송 lock 잔여는 테스트 종료 후 0건이었다.

## 6. 서버 로그 결과

실패 로그는 모두 Redis lock timeout이며, HTTP 500은 발생하지 않았다.

```text
status=409
message=DELIVERY_014
```

실패 원인은 Outbox 저장 오류가 아니라 배송 기사 배정 lock wait 2초 초과다.

## 7. Prometheus / Grafana 계측 확인

Grafana 대시보드의 원천인 Prometheus 지표를 테스트 중간과 종료 후 직접 조회했다.

| 항목 | 값 |
| --- | ---: |
| delivery-service Hikari active max | 20 |
| delivery-service Hikari pending max | 82 |
| hub lock timeout 증가량 | 약 3.08 |
| hub lock acquired 평균 대기 | 742ms |
| Redis lock hold 평균 | 41.6ms |
| Outbox `insert_on_conflict` 평균 | 1.13ms |
| Delivery `total_transaction` 평균 | 1.67ms |

Prometheus `increase()` 값은 scrape 보정 때문에 k6/log의 정확한 실패 건수와 소수점 차이가 날 수 있다. 정확한 실패 건수는 k6 로그 기준 3건이다.

## 8. 이전 run과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | Hikari pending max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Post optimization 20VU | 6,681 | 6,505 | 176 | 2.63% | 13.92 | 2.07s | 2.17s | - |
| Post optimization 50VU | 9,731 | 9,704 | 27 | 0.27% | 20.27 | 2.49s | 3.13s | 32 |
| Post optimization 80VU | 10,343 | 10,328 | 15 | 0.14% | 21.54 | 3.82s | 4.23s | 62 |
| Post optimization 100VU | 10,728 | 10,725 | 3 | 0.02% | 22.34 | 4.52s | 4.71s | 82 |

100VU는 실패율만 보면 가장 낮았다. 그러나 p95는 4.52초까지 상승했고 Hikari pending도 82까지 증가했다. 즉 실패율은 안정적으로 보이지만, 응답 시간과 커넥션 대기 관점에서는 80VU보다 더 나빠졌다.

## 9. 분석

Outbox 최적화는 100VU에서도 기능적으로 안정적이다. payload `text` 전환 이후 HTTP 500은 발생하지 않았고, Outbox insert 평균도 약 1.13ms 수준으로 낮게 유지됐다. 종료 직후 pending 8,825건이 남았지만 최종적으로 모두 `PUBLISHED` 상태가 되었다.

현재 직접적인 병목 신호는 Outbox 저장보다 Hikari pending과 hub lock wait이다. Hikari active가 20까지 차고 pending이 82까지 증가했으며, hub lock acquired 평균 대기는 약 742ms로 유지됐다. 반면 lock hold 평균은 41.6ms 수준이므로, 락을 잡은 뒤 내부 로직이 길게 늘어진다기보다 공통 hub key에 요청이 몰리면서 대기열이 길어지는 형태로 보는 편이 맞다.

100VU에서 실패 건수는 3건으로 낮지만 p95가 4.52초까지 올라간 점이 더 중요하다. 처리량은 22.34 req/s까지 증가했지만, 응답 시간은 이미 SLA 관점의 기준을 넘었다.

## 10. 결론

100VU 검증은 기능 안정성 관점에서는 성공, latency threshold 관점에서는 실패다.

- HTTP 500 없음
- Outbox payload 타입 오류 없음
- DB 정합성 일치
- Redis lock 잔여 없음
- 최종 Outbox backlog 해소
- 실패율 0.02%로 낮음
- p95 4.52s로 latency threshold 실패
- Hikari pending max 82로 커넥션 대기 증가

Outbox 저장 최적화는 안정화됐고, 이후 병목은 Redis hub lock 대기와 DB 커넥션 대기 쪽으로 이동했다. 다음 최적화 후보는 Outbox insert가 아니라 공통 hub key에 집중되는 lock 대기 구조, 또는 요청 처리 중 DB 커넥션 점유 시간을 더 줄이는 방향이다.
