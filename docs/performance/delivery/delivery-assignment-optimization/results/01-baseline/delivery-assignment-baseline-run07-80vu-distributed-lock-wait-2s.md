# Delivery Assignment Baseline Run 07 - 80VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

`lock-wait 2s` 기준에서 분산 80VU까지 부하를 높였을 때, 락 경합과 내부 대기 구간이 얼마나 늘어나는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 07 - 80VU Distributed No Sleep, Lock Wait 2s |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":80},{\"duration\":\"5m\",\"target\":80},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 80 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `2s` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6864 |
| HTTP TPS | 14.28 req/s |
| 실패 요청 수 | 1 |
| 실패율 | 0.01% |
| checks 성공률 | 99.98% |
| 평균 응답 시간 | 3.57s |
| p95 응답 시간 | 4.69s |
| p99 응답 시간 | 5.08s |
| 최대 응답 시간 | 8.05s |
| max VU | 80 |

Threshold 결과는 `http_req_duration p(95)<3000` 1건이 실패했다.

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. DB 처리 결과

`14-reset-delivery-perf-baseline.sql` baseline은 `p_deliveries 33600`, `p_delivery_route_histories 67200`, `p_delivery_outboxes 33600`이다.

| 항목 | baseline | 테스트 후 총량 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40463 | 6863 |
| `p_delivery_route_histories` | 67200 | 80926 | 13726 |
| `p_delivery_outboxes` | 33600 | 40463 | 6863 |
| `delivery.create.succeed` outbox | 26880 | 33743 | 6863 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- 성공 생성 건수와 success outbox 증가는 `6863건`으로 일치했다
- k6 총 요청 `6864건`과 비교하면 `1건` 차이가 있고, 이 차이는 실제 lock timeout 1건과 정확히 맞는다
- `p_delivery_route_histories`는 배송 1건당 2건 생성 기준으로 `13726건` 증가했다

### 5. 해석

- 50VU 대비 TPS는 `13.55 -> 14.28 req/s`로 소폭 증가했지만, 평균 응답 시간은 `2.01s -> 3.57s`, p95는 `2.94s -> 4.69s`로 크게 악화됐다
- 즉 80VU에서는 락 실패가 폭발적으로 늘기보다, 내부 대기와 풀 대기가 먼저 커지면서 응답 시간이 급격히 나빠지는 패턴이 더 강했다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` 최대 | 4710 |
| HTTP `409` 최대 | 1.11 |
| HTTP `5xx` | 관측 없음 |
| Assignment Lock Timeout 로그 | 1건 |
| delivery process CPU 최대 / 평균 | 41.77% / 34.86% |
| delivery system CPU 최대 / 평균 | 67.65% / 51.42% |
| JVM heap 최대 | 292,914,176 bytes, 약 279.3 MiB |
| GC pause max panel 최대 | 46ms |
| GC avg panel 최대 | 22.95ms |
| GC count 최대 | 분당 58회 |
| GC sum 최대 | 분당 1.238s |
| Hikari active 최대 | 10 |
| Hikari pending 최대 / 평균 | 57 / 38.67 |
| Hikari timeout | 0 |
| `/internal/deliveries` RPS 최대 | 16.07 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 4130.95ms |
| Tomcat current connections 최대 | 82 |
| Tomcat busy ratio 최대 | 33.0% |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 126.17ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 5.48ms |
| data-vm host CPU 최대 | 52.17% |
| PostgreSQL connections 최대 | 92 |
| PostgreSQL locks 최대 | 10 |
| PostgreSQL TPS 최대 | 288.4 tx/s |
| PostgreSQL cache hit ratio | 약 99.87% |
| PostgreSQL deadlocks | 0 |

- Hikari active는 여전히 10에 고정됐고 pending은 50VU의 `29`에서 `57`까지 늘었다
- lock timeout 건수는 많지 않았지만, 서버 평균 지연 최대가 `4.13s`까지 올라서 내부 처리 대기가 훨씬 커졌음을 확인했다
- downstream 호출은 `user-service`가 가장 느렸지만, 여전히 전체 지연을 설명할 주병목은 아니었다

### 7. 로그 / 락 경합 분석

Loki `Assignment Lock Timeout Logs` 패널에서 동일 시간대 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 1건을 직접 확인했다.

- `2026-06-21 21:29:21.871 KST`
  - `failedKey=lock:delivery:company:10000000-0000-0000-0000-000000000002`
  - `waitMillis=2000`

즉 80VU에서도 락 획득 실패는 발생했지만, 실패 건수 자체는 낮았고 주된 악화는 timeout 폭증보다 대기 시간 증가였다.

### 8. 결론

```text
WARN

- 총 요청 6864건 중 성공 6863건, lock timeout 1건
- 실패율 0.01%, checks 99.98%
- p95 4.69s로 threshold 초과
- 50VU 대비 timeout 건수는 적었지만 Hikari pending과 내부 지연은 더 크게 증가
- 2s lock wait 기준 80VU는 락 실패 폭증 구간보다는 내부 대기 포화 구간에 더 가까움
```
