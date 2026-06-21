# Delivery Assignment Baseline Run 08 - 100VU Distributed Lock Wait 2s 결과

### 1. 테스트 목적

`lock-wait 2s` 기준에서 분산 100VU까지 부하를 높였을 때, 락 경합과 내부 대기 포화가 얼마나 더 뚜렷하게 나타나는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 08 - 100VU Distributed Lock Wait 2s |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":100},{\"duration\":\"5m\",\"target\":100},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `2s` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6915 |
| HTTP TPS | 14.40 req/s |
| 실패 요청 수 | 5 |
| 실패율 | 0.07% |
| checks 성공률 | 99.92% |
| 평균 응답 시간 | 4.67s |
| p95 응답 시간 | 6.06s |
| p99 응답 시간 | 6.55s |
| 최대 응답 시간 | 11.09s |
| max VU | 100 |

Threshold 결과는 `http_req_duration` 2건이 실패했다.

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✗ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. DB 처리 결과

`14-reset-delivery-perf-baseline.sql` baseline은 `p_deliveries 33600`, `p_delivery_route_histories 67200`, `p_delivery_outboxes 33600`이다.

| 항목 | baseline | 테스트 후 총량 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40510 | 6910 |
| `p_delivery_route_histories` | 67200 | 81020 | 13820 |
| `p_delivery_outboxes` | 33600 | 40510 | 6910 |
| `delivery.create.succeed` outbox | 26880 | 33790 | 6910 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- 성공 생성 건수와 success outbox 증가는 `6910건`으로 일치했다
- k6 총 요청 `6915건`과 비교하면 `5건` 차이가 있고, 이 차이는 실제 lock timeout 5건과 정확히 맞는다
- `p_delivery_route_histories`는 배송 1건당 2건 생성 기준으로 `13820건` 증가했다

### 5. 해석

- 80VU 대비 TPS는 `14.28 -> 14.40 req/s`로 거의 늘지 않았지만, 평균 응답 시간은 `3.57s -> 4.67s`, p95는 `4.69s -> 6.06s`, p99는 `5.08s -> 6.55s`로 더 악화됐다
- 100VU에서는 락 획득 실패도 `1건 -> 5건`으로 늘었고, 내부 대기와 풀 포화가 함께 더 강해진 상태다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` 최대 | 4636.67 |
| HTTP `409` 최대 | 4.44 |
| HTTP `5xx` | 관측 없음 |
| Assignment Lock Timeout 로그 | 5건 |
| delivery process CPU 최대 / 평균 | 42.54% / 34.83% |
| delivery system CPU 최대 / 평균 | 63.68% / 50.04% |
| JVM heap 최대 | 319,676,928 bytes, 약 304.9 MiB |
| GC pause max panel 최대 | 55ms |
| GC avg panel 최대 | 26.96ms |
| GC count 최대 | 분당 60회 |
| GC sum 최대 | 분당 1.51s |
| Hikari active 최대 | 10 |
| Hikari pending 최대 / 평균 | 83 / 53.83 |
| Hikari timeout | 0 |
| `/internal/deliveries` RPS 최대 | 15.87 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 5497.63ms |
| Tomcat current connections 최대 | 102 |
| Tomcat busy ratio 최대 | 46.0% |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 51.11ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 3.41ms |
| data-vm host CPU 최대 | 51.75% |
| PostgreSQL connections 최대 | 92 |
| PostgreSQL locks 최대 | 12 |
| PostgreSQL TPS 최대 | 312.73 tx/s |
| PostgreSQL cache hit ratio | 약 99.87% |
| PostgreSQL deadlocks | 0 |

- Hikari active는 계속 10에 고정됐고 pending은 80VU의 `57`에서 `83`까지 증가했다
- `/internal/deliveries` 서버 평균 지연 최대도 `4.13s -> 5.50s`로 악화돼, lock timeout 증가와 내부 대기 포화가 동시에 진행된 구간으로 볼 수 있다
- downstream API 지연은 여전히 낮아서, 이번 악화의 중심도 외부 호출보다 delivery-service 내부 임계 구간에 더 가깝다

### 7. 로그 / 락 경합 분석

Loki `Assignment Lock Timeout Logs` 패널에서 동일 시간대 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 5건을 직접 확인했다.

- `2026-06-21 21:37:42.113 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002`
- `2026-06-21 21:41:19.500 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000003`
- `2026-06-21 21:41:44.828 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000003`
- `2026-06-21 21:41:50.592 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000003`
- `2026-06-21 21:42:32.159 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002`

모든 로그는 `waitMillis=2000`으로 기록됐고, timeout은 모두 `company` 락에서 발생했다.

### 8. 결론

```text
WARN

- 총 요청 6915건 중 성공 6910건, lock timeout 5건
- 실패율 0.07%, checks 99.92%
- p95 6.06s, p99 6.55s로 둘 다 threshold 초과
- 2s lock wait 기준 100VU에서는 락 획득 실패와 내부 대기 포화가 함께 더 뚜렷해짐
- 다음 단계는 이 baseline을 기준으로 flush 제거, 집계 테이블, 락 구조 변경 효과를 비교하는 것
```
