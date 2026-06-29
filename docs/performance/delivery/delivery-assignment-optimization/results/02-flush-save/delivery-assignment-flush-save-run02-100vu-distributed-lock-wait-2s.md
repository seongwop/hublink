# Delivery Assignment Flush Removal Run 02 - 100VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

배정 구간의 `saveAndFlush`, `saveAllAndFlush`를 `save`, `saveAll`로 바꾼 뒤, 100VU 분산 부하에서 락 경합과 처리량이 실제로 개선되는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Flush Removal Run 02 - 100VU Distributed No Sleep, Lock Wait 2s |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":100},{\"duration\":\"5m\",\"target\":100},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| lock wait | `2s` |
| 적용 변경 | `saveAndFlush -> save`, `saveAllAndFlush -> saveAll` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 7004 |
| HTTP TPS | 14.57 req/s |
| 실패 요청 수 | 5 |
| 실패율 | 0.07% |
| checks 성공률 | 99.92% |
| 평균 응답 시간 | 4.60s |
| p95 응답 시간 | 6.12s |
| p99 응답 시간 | 6.67s |
| 최대 응답 시간 | 11.50s |
| max VU | 100 |

Threshold 결과는 지연 시간 기준에서 실패했다.

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

시간 구간 `2026-06-21 23:16:00 ~ 23:24:40 KST` 기준으로 직접 집계했다.

| 항목 | 값 |
| --- | --- |
| `p_deliveries` 생성 수 | 6999 |
| `p_delivery_route_histories` 생성 수 | 13998 |
| `p_delivery_outboxes` 생성 수 | 6999 |
| `delivery.create.succeed` outbox | 6999 |
| `delivery.create.failed` outbox | 0 |
| `delivery.create.dlq` outbox | 0 |
| 요청-성공 차이 | 5 |

- k6 실패 5건과 DB 성공 수 차이 5건이 정확히 일치했다
- route history는 배송 1건당 2건 생성 패턴으로 유지됐다

### 5. 해석

- 100VU에서 총 7004건 중 6999건이 최종 생성됐고, 실패 5건은 백엔드 성공 수와 정확히 맞아떨어졌다
- flush 제거 후에도 처리량은 크게 늘지 않았고, p95/p99는 여전히 임계치를 넘겼다
- 즉 flush 제거만으로는 100VU 구간의 병목을 풀지 못했다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` RPS 최대 | 15.90 req/s |
| HTTP `409` RPS 최대 | 0.03 req/s |
| Assignment Lock Timeout Logs | 5건 |
| delivery process CPU 최대 / 평균 | 43.48% / 35.91% |
| delivery system CPU 최대 / 평균 | 65.82% / 52.14% |
| JVM heap 최대 | 362,510,760 bytes, 약 345.7 MiB |
| GC pause max 최대 | 86ms |
| GC avg 최대 | 26.96ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 / 평균 | 78 / 53.11 |
| `/internal/deliveries` RPS 최대 | 15.90 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 5796.88ms |
| data-vm host CPU 최대 / 평균 | 52.07% / 41.06% |

- CPU나 JVM heap은 아직 한계까지 몰린 상태는 아니었다
- 대신 Hikari pending이 크게 쌓였고, 요청 지연도 같은 구간에서 함께 상승했다
- 이번 100VU 병목은 CPU 부족보다는 락 대기와 DB 커넥션 대기가 더 직접적이었다

### 7. 로그 / 락 경합 분석

Loki에서 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 로그 5건을 직접 확인했다.

- `2026-06-21 23:16:23.227 KST`
- `2026-06-21 23:18:13.110 KST`
- `2026-06-21 23:19:47.437 KST`
- `2026-06-21 23:20:23.984 KST`
- `2026-06-21 23:20:38.512 KST`

모든 로그는 `waitMillis=2000`이었고, 실패 키는 모두 `lock:delivery:company:*`였다. 즉 이번 실패는 HTTP 계층 문제나 5xx가 아니라, 배정 락 획득 실패로 보는 것이 맞다.

### 8. baseline 비교

| 항목 | Baseline Run 08 | Flush Save Run 02 | 차이 |
| --- | --- | --- | --- |
| 총 요청 수 | 6915 | 7004 | +89 |
| 최종 성공 수 | 6910 | 6999 | +89 |
| 락 timeout | 5 | 5 | 동일 |
| 실패율 | 0.07% | 0.07% | 동일 |
| 평균 응답 시간 | 4.67s | 4.60s | 소폭 개선 |
| p95 | 6.06s | 6.12s | 소폭 악화 |
| p99 | 6.55s | 6.67s | 소폭 악화 |
| Hikari pending 최대 | 83 | 78 | 소폭 개선 |

flush 제거 후 100VU에서 실패 건수는 줄지 않았고, tail latency도 좋아졌다고 보기 어렵다. 결과적으로 이번 변경은 100VU 병목 개선책으로 채택하기 어렵다.

### 9. 결론

```text
WARN

- 총 요청 7004건, 최종 성공 6999건, 락 timeout 5건
- 실패율 0.07%, checks 99.92%
- p95 6.12s, p99 6.67s로 latency threshold 실패
- Assignment Lock Timeout 로그 5건과 DB 성공 차이 5건 일치
- flush 제거만으로는 100VU 구간 락 경합과 tail latency를 개선하지 못함
```
