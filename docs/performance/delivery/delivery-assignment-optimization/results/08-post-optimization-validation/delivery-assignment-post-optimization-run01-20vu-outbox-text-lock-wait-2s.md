# Delivery Assignment Post Optimization Run 01 - 20VU Outbox Text, Lock Wait 2s 결과

## 1. 테스트 목적

직전 예비 실행에서 같은 20VU 조건임에도 실패율이 비정상적으로 높게 튀었다. 서버 상태와 DB 정합성에는 문제가 없었지만, 이전 hub-first 20VU 결과와 차이가 너무 커서 동일 조건으로 다시 측정했다.

확인 대상은 다음과 같다.

- 예비 실행의 실패율 급증이 재현되는지
- Outbox `text` 저장 방식 이후에도 500 / SQL 타입 오류가 재발하지 않는지
- k6 성공 건수와 DB 반영 건수가 일치하는지
- Redis lock timeout이 여전히 hub key에서 발생하는지

## 2. 테스트 전 상태

| 항목 | 결과 |
| --- | --- |
| delivery-service health | `UP` |
| Redis 배송 lock 잔여 | 0 |
| Outbox backlog | 없음, `PUBLISHED 37285` |
| k6 중복 프로세스 | 없음 |

## 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Post Optimization Run 01 - 20VU Outbox Text, Lock Wait 2s |
| 시작 시간 | 2026-07-04 21:50:32 KST |
| 종료 시간 | 2026-07-04 21:58:32 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | Outbox payload, last_error `text` 저장 방식 적용 |
| k6 로그 | `/tmp/hublink-k6-20vu-outbox-text-rerun-20260704T125032Z.log` |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6,681 |
| HTTP TPS | 13.92 req/s |
| 성공 요청 수 | 6,505 |
| 실패 요청 수 | 176 |
| 실패율 | 2.63% |
| checks 성공률 | 97.36% |
| 평균 응답 시간 | 1.17s |
| median 응답 시간 | 1.24s |
| p90 응답 시간 | 1.97s |
| p95 응답 시간 | 2.07s |
| p99 응답 시간 | 2.17s |
| 최대 응답 시간 | 2.33s |

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

실패 176건은 모두 배송 기사 배정 lock timeout으로 인한 HTTP 409이다.

| 오류 | 건수 |
| --- | ---: |
| `DELIVERY_014` / HTTP 409 | 176 |
| HTTP 500 | 0 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |

## 5. DB 반영 결과

`14-reset-delivery-perf-baseline.sql` 기준 baseline은 `p_deliveries 33,600`, `p_delivery_route_histories 67,200`, `p_delivery_outboxes 33,600`이다.

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `delivery_service.p_deliveries` | 33,600 | 40,105 | 6,505 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 80,210 | 13,010 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 40,105 | 6,505 |
| `delivery_service.p_delivery_assignment_counts` row | 3,300 | 3,300 | 0 |

DB 반영량은 k6 성공 요청 6,505건과 일치한다. route history는 배송 1건당 2건씩 생성되어 13,010건 증가했다.

Outbox 상태:

| status | 건수 |
| --- | ---: |
| `PUBLISHED` | 40,105 |

테스트 종료 후 outbox backlog는 남지 않았다.

Redis 배송 lock 잔여도 0건이다.

## 6. 서버 로그 결과

Outbox `oid` 타입 불일치 오류와 HTTP 500은 재발하지 않았다.

이번 실패는 모두 Redis lock wait 2초 초과이며, failedKey는 hub-first 순서에서 먼저 획득하는 공통 hub key로 확인됐다.

```text
failedKey=lock:delivery:hub:10000000-0000-0000-0000-000000000001
waitMillis=2000
```

## 7. 20VU 반복 결과 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | threshold | timeout key |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Redis lock order run01 - hub-first | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | 통과 | hub |
| Post optimization run01 - outbox text | 6,681 | 6,505 | 176 | 2.63% | 13.92 | 2.07s | 통과 | hub |

공식 run01은 전체 threshold를 통과했다. 따라서 직전 예비 실행의 높은 실패율은 현재 코드의 안정적인 대표값으로 보기 어렵고, 테스트 직전 상태나 일시적인 runtime 변동이 크게 반영된 이상치로 판단한다.

다만 이번 run도 이전 hub-first 20VU보다 실패 건수가 86건에서 176건으로 증가했고, TPS는 14.40에서 13.92로 낮아졌다. Outbox text 전환 후 500 문제는 해결됐지만, 20VU 기준 성능이 이전 최고 결과를 완전히 회복했다고 보기는 어렵다.

## 8. 결론

Outbox `text` 저장 방식은 기능적으로 정상 동작한다.

- HTTP 500 없음
- PostgreSQL payload 타입 오류 없음
- k6 성공 건수와 DB 반영 건수 일치
- outbox backlog 없음
- Redis lock 잔여 없음

성능 관점에서는 예비 실행의 큰 악화는 재현되지 않았고, 공식 run01은 threshold를 통과했다. 따라서 다음 단계로 50VU를 진행할 수 있다.

다만 20VU 실패율이 이전 hub-first 대표값보다 높으므로, 50VU에서도 실패율과 timeout key가 어떻게 움직이는지 확인해야 한다.
