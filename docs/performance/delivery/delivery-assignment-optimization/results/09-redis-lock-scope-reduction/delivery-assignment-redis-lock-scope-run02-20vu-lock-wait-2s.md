# Delivery Assignment Redis Lock Scope Run 02 - 20VU Lock Wait 2s 결과

## 1. 테스트 목적

`run01`에서 Redis lock hold는 짧아졌지만 실패율이 직전 20VU 대표값보다 높게 나왔다. 같은 조건의 20VU를 한 번 더 반복해 실패 증가가 일시적인 변동인지 확인했다.

확인 대상은 다음과 같다.

- 실패가 여전히 `DELIVERY_014` lock timeout인지
- lock hold 축소가 반복 측정에서도 end-to-end 개선으로 이어지지 않는지
- DB 반영 정합성이 유지되는지
- outbox publish가 테스트 종료 시점까지 따라잡는지

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Redis Lock Scope Run 02 - 20VU Lock Wait 2s |
| 시작 시간 | 2026-07-01 23:42:43 KST |
| 종료 시간 | 2026-07-01 23:50:48 KST |
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
| 총 요청 수 | 6,629 |
| HTTP TPS | 13.81 req/s |
| 성공 요청 수 | 6,424 |
| 실패 요청 수 | 205 |
| 실패율 | 3.09% |
| checks 성공률 | 96.90% |
| 평균 응답 시간 | 1.18s |
| median 응답 시간 | 1.18s |
| p90 응답 시간 | 1.95s |
| p95 응답 시간 | 2.12s |
| p99 응답 시간 | 2.36s |
| 최대 응답 시간 | 4.15s |

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

실패 205건은 모두 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

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
| `delivery_service.p_deliveries` | 33,600 | 40,024 | 6,424 |
| `delivery_service.p_delivery_route_histories` | 67,200 | 80,048 | 12,848 |
| `delivery_service.p_delivery_outboxes` | 33,600 | 40,024 | 6,424 |
| `COMPANY_DELIVERY` 집계 합 | 3,600 | 10,024 | 6,424 |
| `HUB_DELIVERY` 집계 합 | 1,800 | 8,224 | 6,424 |

DB 반영량은 k6 성공 요청 수 6,424건과 일치한다. route history는 배송 1건당 2건씩 생성되어 12,848건 증가했다.

outbox status는 테스트 직후 한 번에 모두 `PUBLISHED`까지 따라잡지는 못했다.

| outbox status | 건수 |
| --- | ---: |
| `PUBLISHED` | 37,900 |
| `PENDING` | 2,124 |
| 전체 | 40,024 |

배송 생성과 outbox 적재 정합성은 맞지만, 테스트 종료 시점의 outbox publish 처리량은 생성량을 모두 따라잡지 못했다.

## 5. 서버 로그 결과

delivery-service 로그 기준:

| 항목 | 건수 |
| --- | ---: |
| `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` | 205 |
| `DELIVERY_UNEXPECTED_EXCEPTION` | 0 |
| `IllegalAccessException` | 0 |
| `HUB_SERVICE_UNAVAILABLE` | 0 |
| `USER_SERVICE_UNAVAILABLE` | 0 |
| `DELIVERY_ASSIGNMENT_RESERVATION_COMPENSATED` | 0 |
| `DELIVERY_ASSIGNMENT_RESERVATION_COMPENSATION_FAILED` | 0 |

lock timeout failedKey 분포:

| failedKey | 건수 |
| --- | ---: |
| `lock:delivery:company:10000000-0000-0000-0000-000000000002` | 116 |
| `lock:delivery:company:10000000-0000-0000-0000-000000000003` | 89 |

실패는 모두 company delivery manager lock에서 발생했다. hub lock timeout은 관측되지 않았다.

## 6. Prometheus 계측 결과

Prometheus의 `increase()` 계열 카운트는 scrape 간격 때문에 외삽될 수 있으므로, 건수는 k6/로그/DB를 기준으로 판단했다. Prometheus 값은 지연 시간과 병목 위치 분석에 사용했다.

### Redis lock wait

| 구분 | 평균 | 최대 |
| --- | ---: | ---: |
| company acquired | 0.931s | 2.004s |
| company timeout | 2.005s | 2.153s |
| hub acquired | 0.066s | 1.234s |

### Redis lock hold

| lock_scope | 평균 | 최대 |
| --- | ---: | ---: |
| mixed / success | 0.069s | 1.246s |

평균 lock hold는 69ms로 짧다. 다만 최대값은 1.246s까지 튀었다. 평균은 낮지만 일부 구간에서 락 보유 시간이 길어지면 company key 대기열이 급격히 쌓일 수 있다.

### Assignment count operation

| assignment_type / operation | 평균 | 최대 |
| --- | ---: | ---: |
| `company_delivery` / `read` | 0.016s | 0.415s |
| `hub_delivery` / `read` | 0.025s | 0.599s |
| `mixed` / `increase` | 0.001s | 0.032s |

집계 write 자체는 여전히 평균 2ms 미만이다. 반면 read 쪽은 최대값이 run01보다 커졌다. 하지만 실패가 발생한 위치는 read failure가 아니라 company lock wait timeout이다.

### Delivery create transaction

| stage | 평균 | 최대 |
| --- | ---: | ---: |
| `delivery_save` | 0.000s | 0.028s |
| `route_history_save_all` | 0.000s | 0.020s |
| `outbox_enqueue` | 0.013s | 0.159s |
| `deadline_event_register` | 0.000s | 0.046s |
| `total_transaction` | 0.014s | 0.160s |

락 밖에서 수행되는 배송 저장 트랜잭션은 평균 14ms 수준이다. 이번 실패율 증가를 배송 저장 트랜잭션 비용으로 설명하기는 어렵다.

### Delivery Hikari

| 항목 | 값 |
| --- | ---: |
| max pool | 20 |
| min idle | 3 |
| active 최대 | 20 |
| pending 최대 | 2 |

delivery-service의 Hikari active는 이번에도 max 20까지 올라갔고 pending 최대 2가 관측됐다. 다만 실패 원인은 connection timeout이 아니라 `DELIVERY_014` lock timeout이다.

## 7. 반복 측정 비교

| 구분 | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | company lock timeout |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Pool tuning 20VU 대표값 | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s | 84 |
| Redis lock scope run01 | 6,223 | 6,097 | 126 | 2.02% | 12.96 | 2.09s | 2.24s | 126 |
| Redis lock scope run02 | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | 2.36s | 205 |

run02는 TPS만 보면 pool tuning 대표값과 비슷하지만 실패율은 더 나쁘다. 두 번의 반복 측정 모두 직전 20VU 대표값보다 company lock timeout이 많았다.

## 8. 분석

이번 반복 측정으로 "Redis 락 범위 축소가 20VU에서 즉시 실패율을 낮춘다"는 가설은 약해졌다.

중요한 관측은 다음과 같다.

- 실패는 모두 `DELIVERY_014` company lock timeout이다.
- 외부 서비스 통신 실패나 fallback 접근 오류는 재현되지 않았다.
- lock hold 평균은 69ms로 짧다.
- 집계 mixed increase 평균은 1ms 수준이다.
- 배송 저장 트랜잭션 평균도 14ms 수준이다.
- 그럼에도 company lock wait는 평균 0.93s이고, timeout은 2s를 넘는다.
- failedKey는 company key 2개에만 집중된다.

즉 현재 문제는 락 안의 평균 처리 시간이 아니라 company lock key 집중과 순간 대기열이다. 평균 lock hold가 짧아도 특정 순간에 hold가 1초 이상 튀거나, 같은 key로 요청이 몰리면 뒤쪽 요청은 2초 lock wait를 넘을 수 있다.

## 9. 결론

20VU 반복 측정 결과, Redis 락 범위 축소는 lock hold 평균을 낮추는 데는 성공했지만 20VU end-to-end 실패율 개선으로 이어지지는 않았다.

오히려 두 번의 측정에서 실패율은 `2.02%`, `3.09%`로 직전 20VU 대표값 `1.27%`보다 높았다. 따라서 현재 상태에서는 "락 범위 축소만으로 company lock timeout을 해소하기 어렵다"는 결론이 더 타당하다.

다음 판단은 두 갈래다.

- 같은 구현으로 50VU, 80VU까지 측정해 높은 부하에서 추세가 더 나빠지는지 확인한다.
- Redis 락을 유지한다면 company lock key 집중을 줄이는 설계를 검토한다.
- Redis 락을 제거하는 방향이라면 DB row lock 기반으로 `후보 선택 + count 증가`를 원자적으로 묶는 설계를 별도 실험 단계로 잡는다.
