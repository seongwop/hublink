# Delivery Assignment Baseline Run 06 - 50VU Distributed Lock Wait 2s 결과

### 1. 테스트 목적

기존 `lock-wait 3s`가 너무 관대하고 `1s`는 너무 공격적이어서, `2s`로 낮춘 뒤 분산 50VU에서 락 경합 신호가 어느 정도부터 드러나는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 06 - 50VU Distributed Lock Wait 2s |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":50},{\"duration\":\"5m\",\"target\":50},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `2s` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6505 |
| HTTP TPS | 13.55 req/s |
| 실패 요청 수 | 3 |
| 실패율 | 0.04% |
| checks 성공률 | 99.95% |
| 평균 응답 시간 | 2.01s |
| p95 응답 시간 | 2.94s |
| p99 응답 시간 | 3.47s |
| 최대 응답 시간 | 4.99s |
| max VU | 50 |

Threshold 결과는 모두 통과했다.

```text
checks
✓ rate>0.90

http_req_duration
✓ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. DB 처리 결과

`14-reset-delivery-perf-baseline.sql` baseline은 `p_deliveries 33600`, `p_delivery_route_histories 67200`, `p_delivery_outboxes 33600`이다.

| 항목 | baseline | 테스트 후 총량 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40102 | 6502 |
| `p_delivery_route_histories` | 67200 | 80204 | 13004 |
| `p_delivery_outboxes` | 33600 | 40102 | 6502 |
| `delivery.create.succeed` outbox | 26880 | 33382 | 6502 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- 성공 생성 건수와 success outbox 증가는 `6502건`으로 일치했다
- k6 총 요청 `6505건`과 비교하면 `3건` 차이가 있고, 이 차이는 실제 lock timeout 3건과 정확히 맞는다
- `p_delivery_route_histories`는 배송 1건당 2건 생성 기준으로 `13004건` 증가했다

### 5. 해석

- `lock-wait 2s` 기준 50VU에서는 대부분 정상 처리되지만, 아주 소수의 lock timeout이 발생하기 시작했다
- 즉 `2s`는 50VU를 완전히 무너뜨리지는 않으면서도 락 경합 신호를 관측할 수 있는 경계값에 가깝다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` 최대 | 4445.56 |
| HTTP `409` 최대 | 3.33 |
| HTTP `5xx` | 관측 없음 |
| Assignment Lock Timeout 로그 | 3건 |
| delivery process CPU 최대 / 평균 | 51.84% / 37.65% |
| delivery system CPU 최대 / 평균 | 76.83% / 56.49% |
| JVM heap 최대 | 320,609,792 bytes, 약 305.8 MiB |
| GC pause max panel 최대 | 44ms |
| GC avg panel 최대 | 22.5ms |
| GC count 최대 | 분당 52회 |
| GC sum 최대 | 분당 1.086s |
| Hikari active 최대 | 10 |
| Hikari pending 최대 / 평균 | 29 / 18 |
| Hikari timeout | 0 |
| `/internal/deliveries` RPS 최대 | 15.2 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 2381.41ms |
| Tomcat current connections 최대 | 52 |
| Tomcat busy ratio 최대 | 19.0% |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 51.42ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 1.22ms |
| data-vm host CPU 최대 | 49.93% |
| PostgreSQL connections 최대 | 92 |
| PostgreSQL locks 최대 | 22 |
| PostgreSQL TPS 최대 | 253.93 tx/s |
| PostgreSQL cache hit ratio | 약 99.87% |
| PostgreSQL deadlocks | 0 |

- Hikari active는 계속 10에 붙고 pending이 최대 29까지 쌓여, 이번에도 락 자체보다 내부 대기와 DB pool 경쟁이 먼저 커지는 흐름이 유지됐다
- downstream API 지연은 여전히 낮아서, lock timeout은 외부 호출 지연보다 내부 임계 구간에서 발생한 것으로 보는 편이 맞다

### 7. 로그 / 락 경합 분석

Loki `Assignment Lock Timeout Logs` 패널에서 동일 시간대 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 3건을 직접 확인했다.

- `2026-06-21 20:47:18.067 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000003`
- `2026-06-21 20:48:11.862 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002`
- `2026-06-21 20:48:16.533 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002`

세 로그 모두 `waitMillis=2000`으로 기록됐고, `hub` 락보다 `company` 락에서 timeout이 발생했다.

### 8. 결론

```text
PASS

- 총 요청 6505건 중 성공 6502건, lock timeout 3건
- 실패율 0.04%, checks 99.95%
- p95 2.94s, p99 3.47s로 threshold 통과
- lock-wait 2s는 50VU에서 과도하게 무너지지 않으면서 락 경합 신호를 드러내는 값
- 다음 단계는 80VU 또는 100VU에서 timeout 증가 폭을 확인하는 것
```
