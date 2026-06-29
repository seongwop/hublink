# Delivery Assignment Flush Removal Run 01 - 50VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

배정 락 내부의 `saveAndFlush`, `saveAllAndFlush`를 `save`, `saveAll`로 바꾼 뒤, 동일한 50VU 분산 조건에서 락 경합과 내부 대기 시간이 실제로 줄어드는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Flush Removal Run 01 - 50VU Distributed No Sleep, Lock Wait 2s |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":50},{\"duration\":\"5m\",\"target\":50},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `2s` |
| 적용 변경 | `Delivery.create` 저장 구간 `saveAndFlush -> save`, `saveAllAndFlush -> saveAll` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6363 |
| HTTP TPS | 13.25 req/s |
| 실패 요청 수 | 9 |
| 실패율 | 0.14% |
| checks 성공률 | 99.85% |
| 평균 응답 시간 | 2.07s |
| p95 응답 시간 | 2.94s |
| p99 응답 시간 | 3.56s |
| 최대 응답 시간 | 4.41s |
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
| `p_deliveries` | 33600 | 39954 | 6354 |
| `p_delivery_route_histories` | 67200 | 79908 | 12708 |
| `p_delivery_outboxes` | 33600 | 39954 | 6354 |
| `delivery.create.succeed` outbox | 26880 | 33234 | 6354 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- 성공 생성 건수와 success outbox 증가는 `6354건`으로 일치했다
- k6 총 요청 `6363건`과 비교하면 `9건` 차이가 있고, 이 차이는 실제 lock timeout 9건과 정확히 맞는다
- `p_delivery_route_histories`는 배송 1건당 2건 생성 기준으로 `12708건` 증가했다

### 5. 해석

- baseline `Run 06`과 같은 50VU 조건이지만, flush 제거 후에는 실패 건수가 `3건 -> 9건`으로 늘었다
- 평균 응답 시간과 p95는 거의 비슷했지만, lock timeout이 더 자주 발생해 락 경합 측면에서는 개선이 아니라 악화로 보는 편이 맞다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` 최대 | 4468.89 |
| HTTP `409` 최대 | 13.33 |
| HTTP `5xx` | 관측 없음 |
| Assignment Lock Timeout 로그 | 9건 |
| delivery process CPU 최대 / 평균 | 47.58% / 37.83% |
| delivery system CPU 최대 / 평균 | 72.95% / 58.90% |
| JVM heap 최대 | 304,638,944 bytes, 약 290.5 MiB |
| GC pause max panel 최대 | 79ms |
| GC avg panel 최대 | 25.25ms |
| GC count 최대 | 분당 52회 |
| GC sum 최대 | 분당 1.146s |
| Hikari active 최대 | 10 |
| Hikari pending 최대 / 평균 | 29 / 18.11 |
| Hikari timeout | 0 |
| `/internal/deliveries` RPS 최대 | 15.2 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 2831.06ms |
| Tomcat current connections 최대 | 52 |
| Tomcat busy ratio 최대 | 19.0% |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 49.42ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 1.28ms |
| data-vm host CPU 최대 | 54.78% |
| PostgreSQL connections 최대 | 92 |
| PostgreSQL locks 최대 | 12 |
| PostgreSQL TPS 최대 | 308.27 tx/s |
| PostgreSQL cache hit ratio | 약 99.87% |
| PostgreSQL deadlocks | 0 |

- Hikari pending은 baseline run06과 거의 같은 수준인데, `409`와 lock timeout 로그는 더 많이 관측됐다
- 즉 flush 제거만으로 락 보유 시간이 줄었다고 보기 어렵고, 남아 있는 commit 시점 flush 또는 outbox `saveAndFlush`가 병목 시점을 뒤로 몰았을 가능성이 있다

### 7. 로그 / 락 경합 분석

Loki `Assignment Lock Timeout Logs` 패널에서 동일 시간대 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 9건을 직접 확인했다.

대표 시각:

- `2026-06-21 22:56:04 KST`
- `2026-06-21 22:57:27 KST`
- `2026-06-21 22:57:41 KST`
- `2026-06-21 22:57:47 KST`
- `2026-06-21 22:59:00 KST`
- `2026-06-21 22:59:29 KST`
- `2026-06-21 23:00:40 KST`
- `2026-06-21 23:00:47 KST`
- `2026-06-21 23:01:42 KST`

모든 로그는 `waitMillis=2000`으로 기록됐고, timeout은 `company` 락에서 발생했다.

### 8. baseline 비교

| 항목 | Baseline Run 06 | Flush Save Run 01 | 차이 |
| --- | --- | --- | --- |
| 총 요청 수 | 6505 | 6363 | -142 |
| 성공 건수 | 6502 | 6354 | -148 |
| lock timeout | 3 | 9 | +6 |
| 실패율 | 0.04% | 0.14% | 악화 |
| 평균 응답 시간 | 2.01s | 2.07s | 소폭 악화 |
| p95 | 2.94s | 2.94s | 동일 |
| Hikari pending max | 29 | 29 | 동일 |
| 서버 평균 지연 최대 | 2381.41ms | 2831.06ms | 악화 |

### 9. 결론

```text
WARN

- 총 요청 6363건 중 성공 6354건, lock timeout 9건
- 실패율 0.14%, checks 99.85%
- p95 2.94s, p99 3.56s로 threshold는 통과
- 그러나 동일 50VU baseline 대비 lock timeout이 3건에서 9건으로 증가
- flush 제거만으로는 현재 구조에서 락 경합 완화 효과를 확인하지 못함
```
