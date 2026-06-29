# Delivery Assignment Baseline Run 05 - 100VU Distributed No Sleep 결과

### 1. 테스트 목적

`14-reset-delivery-perf-baseline.sql` 기준 분산 입력 100VU에서 배송 생성 및 배정 로직의 한계 구간을 확인하고, 이후 최적화 전 기준선을 확보한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 05 - 100VU Distributed No Sleep |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":100},{\"duration\":\"5m\",\"target\":100},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6757 |
| HTTP TPS | 14.07 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 4.8s |
| p95 응답 시간 | 6.29s |
| p99 응답 시간 | 7.39s |
| 최대 응답 시간 | 12.59s |
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
| `p_deliveries` | 33600 | 40357 | 6757 |
| `p_delivery_route_histories` | 67200 | 80714 | 13514 |
| `p_delivery_outboxes` | 33600 | 40357 | 6757 |
| `delivery.create.succeed` outbox | 26880 | 33637 | 6757 |
| `delivery.create.failed` outbox | 5040 | 5040 | 0 |
| `delivery.create.dlq` outbox | 1680 | 1680 | 0 |

- `p_deliveries`, `p_delivery_outboxes`, `delivery.create.succeed` 증가량이 k6 총 요청 수 6757건과 일치했다
- `failed`, `dlq` 증가는 없어서 요청은 모두 성공 경로로 처리됐다
- `p_delivery_route_histories`는 배송 1건당 2건 생성 기준으로 13514건 증가했다

### 5. 해석

- 50VU 대비 TPS는 `13.34 -> 14.07 req/s`로 거의 늘지 않았는데, 평균 응답 시간은 `2.06s -> 4.8s`, p95는 `3.09s -> 6.29s`, p99는 `3.71s -> 7.39s`로 크게 악화됐다
- 즉 100VU에서는 처리량 확장보다 대기 시간 증가가 먼저 발생했고, 이미 포화 구간에 진입한 상태로 보는 편이 맞다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| HTTP `201` 최대 | 4628.89 |
| HTTP `409` | 관측 없음 |
| HTTP `5xx` | 관측 없음 |
| delivery process CPU 최대 / 평균 | 41.92% / 35.26% |
| delivery system CPU 최대 / 평균 | 64.52% / 51.03% |
| JVM heap 최대 | 456,717,312 bytes, 약 435.5 MiB |
| GC pause max panel 최대 | 95ms |
| GC pause avg panel 최대 | 29.53ms |
| GC count 최대 | 분당 36회 |
| GC sum 최대 | 분당 1.004s |
| Hikari active 최대 | 10 |
| Hikari max | 10 |
| Hikari idle 최저 | 0에 수렴 |
| Hikari pending 최대 / 평균 | 78 / 55.2 |
| Hikari timeout | 0 |
| `/internal/deliveries` RPS 최대 | 16.1 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 5508.63ms |
| Tomcat busy ratio 최대 | 43.5% |
| 현재 DB connection 최대 / 평균 | 102 / 86.34 |
| DB connection ratio 최대 | 1.25% |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 129.73ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 57.05ms |
| `hub-service /internal/hubs/{hubId}` 평균 지연 최대 | 6.75ms |

- Hikari active가 10으로 고정된 상태에서 pending이 최대 78까지 누적됐고 idle도 거의 0으로 붙어 있어, 이번 구간의 가장 선명한 병목은 DB pool 대기였다
- downstream 호출은 가장 느린 `user-service /internal/delivery-managers/search`도 129.73ms 수준이어서, 5초대 내부 지연을 설명할 만큼 크지 않았다
- CPU와 GC는 부하가 있긴 했지만, 처리량이 안 늘고 응답만 밀린 주원인으로 보기는 어려웠다

### 7. 결론

```text
WARN

- 총 요청 6757건, 실패 0건
- backend 생성량과 success outbox 증가량은 6757건으로 일치
- p95 6.29s, p99 7.39s로 둘 다 임계치 초과
- 100VU에서도 오류는 없었지만 처리량은 50VU 대비 거의 증가하지 않음
- 병목은 downstream보다 delivery-service 내부 DB pool 대기와 트랜잭션 체류 구간에 더 가까움
```
