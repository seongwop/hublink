# Delivery Assignment Baseline Run 04 - 50VU Distributed No Sleep 결과

### 1. 테스트 목적

`14-reset-delivery-perf-baseline.sql` 기준 분산 입력 50VU에서 배송 생성 배정 로직의 처리 한계 구간을 확인하고, 이후 최적화 전 기준선을 확보한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 04 - 50VU Distributed No Sleep |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":50},{\"duration\":\"5m\",\"target\":50},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6403 |
| HTTP TPS | 13.34 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.06s |
| p95 응답 시간 | 3.09s |
| p99 응답 시간 | 3.71s |
| 최대 응답 시간 | 5.08s |
| max VU | 50 |

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

`14-reset-delivery-perf-baseline.sql` baseline은 `p_deliveries 33600`, `p_delivery_route_histories 67200`, `p_delivery_outboxes 33600`을 적재한다.

현재 DB 총량 기준 실제 증분은 아래와 같다.

| 항목 | baseline | 테스트 후 실측값 | 실측 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40003 | 6403 |
| `p_delivery_route_histories` | 67200 | 80006 | 12806 |
| `p_delivery_outboxes` | 33600 | 40003 | 6403 |
| `delivery.create.succeed` outbox | 26880 | 33283 | 6403 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- `p_deliveries`, `p_delivery_outboxes`, `delivery.create.succeed` 증가량이 k6 총 성공 요청 수 6403건과 정확히 일치했다
- 경로 이력은 배송 1건당 2건 생성 기준으로 12806건 증가했다

### 5. 해석

- 50VU까지 올려도 실패 요청은 없었지만, TPS는 `9.86 -> 13.34 req/s`로 완만하게만 증가했다
- 반면 평균 응답 시간은 `660.67ms -> 2.06s`, p95는 `1.33s -> 3.09s`, p99는 `1.61s -> 3.71s`로 크게 악화됐다
- 즉 현재 baseline은 20VU에서는 안정 구간이지만, 50VU에서는 처리량 증가보다 대기 시간이 더 빠르게 늘어나는 포화 구간에 진입한 것으로 해석된다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| delivery process CPU 최대 / 평균 | 43.93% / 35.11% |
| delivery system CPU 최대 / 평균 | 74.36% / 58.71% |
| JVM heap 최대 | 441,402,368 bytes, 약 421 MiB |
| GC pause 최대 | 61ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 31 |
| `/internal/deliveries` RPS 최대 | 15.33 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 2440.78ms |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 60.12ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 1.52ms |

- 50VU에서는 Hikari pending이 최대 31까지 치솟아, 현재 병목이 DB pool 대기와 트랜잭션 체류 시간 쪽으로 이동한 모습이 분명하다
- 외부 호출 지연보다 `/internal/deliveries` 자체 평균 지연 증가 폭이 훨씬 커서, 병목은 downstream보다 내부 배정/저장 구간에 더 가깝다

### 7. 결론

```text
WARN

- 총 요청 6403건, 실패 0건
- p95 3.09s로 threshold 초과
- 20VU 대비 TPS 증가는 제한적이지만 응답 시간은 급격히 증가
- 현재 baseline에서 50VU distributed는 기능 성공은 유지하지만 성능 기준은 만족하지 못함
```
