# Delivery Assignment Baseline Run 03 - 20VU Distributed No Sleep 결과

### 1. 테스트 목적

`14-reset-delivery-perf-baseline.sql` 기준으로 분산 입력 20VU baseline을 다시 수집하고, 이후 배송 기사 배정 최적화 전후 성능 비교 기준선을 확정한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 03 - 20VU Distributed No Sleep |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{\"duration\":\"1m\",\"target\":20},{\"duration\":\"5m\",\"target\":20},{\"duration\":\"2m\",\"target\":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 4737 |
| HTTP TPS | 9.86 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 660.67ms |
| p95 응답 시간 | 1.33s |
| p99 응답 시간 | 1.61s |
| 최대 응답 시간 | 2.85s |
| max VU | 20 |

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

`run04` 시작 시 `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql` 이 다시 실행되면서 delivery 테이블이 baseline으로 재초기화됐다. 그래서 `run03`의 DB 실측 post-count는 현재 시점에서 복구할 수 없다.

복구 가능한 값은 k6 성공 요청 수 기준 기대 증분뿐이다.

| 항목 | baseline | 기대 증가량 | 테스트 후 기대값 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 4737 | 38337 |
| `p_delivery_route_histories` | 67200 | 9474 | 76674 |
| `p_delivery_outboxes` | 33600 | 4737 | 38337 |
| `delivery.create.succeed` outbox | 26880 | 4737 | 31617 |
| `delivery.create.failed` outbox | 5040 | 0 | 5040 |
| `delivery.create.dlq` outbox | 1680 | 0 | 1680 |

### 5. 해석

- `14-reset-delivery-perf-baseline.sql` 수정 후 baseline seed 자체는 live DB에서 정상 `COMMIT`까지 검증됐다
- 분산 입력 20VU에서는 실패 요청 없이 전 구간 threshold를 통과했지만, 이전 run02 대비 응답 시간이 커져 baseline 데이터셋 확대 영향이 드러났다
- 현재 기준선은 `avg 660.67ms`, `p95 1.33s`, `p99 1.61s`, `9.86 req/s` 수준이다

### 6. Grafana / Prometheus 관측

| 항목 | 값 |
| --- | --- |
| delivery process CPU 최대 / 평균 | 38.39% / 31.01% |
| delivery system CPU 최대 / 평균 | 82.23% / 65.63% |
| JVM heap 최대 | 462,503,352 bytes, 약 441 MiB |
| GC pause 최대 | 66ms |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 2 |
| `/internal/deliveries` RPS 최대 | 11.93 req/s |
| `/internal/deliveries` 서버 평균 지연 최대 | 808.12ms |
| `user-service /internal/delivery-managers/search` 평균 지연 최대 | 142.26ms |
| `hub-service /internal/hub-routes/path` 평균 지연 최대 | 63.69ms |

- 20VU에서는 Hikari pending이 최대 2에 머물러 DB pool 대기는 아직 제한적이었다
- 가장 큰 외부 호출 지연은 `user-service /internal/delivery-managers/search` 쪽에서 관측됐다

### 7. 결론

```text
PASS

- 총 요청 4737건, 실패 0건
- p95 1.33s, p99 1.61s
- baseline seed 수정 후 20VU distributed 재측정 완료
- 다음 단계는 같은 seed 기준 50VU distributed 확장 측정
```
