# Delivery Assignment Baseline Run 01 - 20VU Concentrated No Sleep 결과

### 1. 테스트 목적

`14-reset-delivery-perf-baseline.sql` 기준의 현재 배송 기사 배정 로직을 측정하고, 이후 최적화 단계와 비교할 baseline을 확보한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Baseline Run 01 - 20VU Concentrated No Sleep |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | concentrated, no-sleep, receiver 1개 고정 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 6691 |
| HTTP TPS | 13.92 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 175.31ms |
| p95 응답 시간 | 596.42ms |
| p99 응답 시간 | 1.58s |
| 최대 응답 시간 | 2.99s |
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

baseline seed 기준 총량과 테스트 후 총량을 비교했다.

| 항목 | baseline | 테스트 후 | 증가량 |
| --- | --- | --- | --- |
| `p_deliveries` | 33600 | 40291 | 6691 |
| `p_delivery_route_histories` | 67200 | 80582 | 13382 |
| `p_delivery_outboxes` | 33600 | 40291 | 6691 |

- `p_deliveries` 증가량 6691건은 k6 총 성공 요청 수 6691건과 일치
- `p_delivery_outboxes` 증가량도 6691건으로 일치
- `p_delivery_route_histories`는 13382건 증가해 배송 1건당 평균 2건의 경로 이력이 생성된 것으로 확인

### 5. Grafana 관측 결과

| 항목 | 값 |
| --- | --- |
| delivery process CPU 최대 / 평균 | 41.95% / 26.86% |
| delivery system CPU 최대 / 평균 | 95.01% / 68.19% |
| JVM heap 최대 | 434993008 bytes, 약 415 MiB |
| GC pause 최대 | 77ms |
| Hikari active 최대 | 10 |
| Hikari idle 최대 | 10 |
| Hikari pending 최대 | 2 |
| Tomcat busy ratio 최대 | 6% |
| `/internal/deliveries` 서버 평균 지연 피크 | 488.46ms |
| `user-service /internal/delivery-managers/search` 평균 지연 피크 | 25.23ms |
| `hub-service /internal/hub-routes/path` 평균 지연 피크 | 17.01ms |
| data-vm host CPU 최대 | 46.53% |
| data-vm host memory 최대 | 30.07% |
| data-vm host load1 최대 | 1.12 |
| circuit breaker failure rate 최대 | user-service 0%, hub-service 0% |

관측 구간 동안 connection pool 대기는 최대 2건으로 짧게 발생했고, Tomcat thread 사용률도 낮게 유지됐다. 외부 호출 지연도 수십 ms 수준에 머물러 baseline 20VU에서는 인프라 포화나 downstream 병목은 뚜렷하지 않았다.

### 6. 로그 및 병목 분석

- Loki 기준 `delivery-service` WARN 0건, ERROR 0건
- `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 로그 0건
- `DELIVERY_CREATE_DUPLICATE_SKIPPED` 로그 0건
- Zipkin 샘플 trace의 root span `POST /internal/deliveries`는 대략 `54~61ms`
- 같은 trace에서 가장 긴 외부 호출은 `user-service /internal/delivery-managers/search`로 대략 `13~15ms`
- `hub-service /internal/hub-routes/path`는 대략 `1~2ms`

Prometheus repository 지표 기준으로는 아래 메서드가 상대적으로 길었다.

| 메서드 | 관측 최대 평균 |
| --- | --- |
| `DeliveryRepository.existsByOrderId` | 48.56ms |
| `DeliveryRouteHistoryRepository.countActiveAssignmentsByManagerIds` | 15.63ms |
| `DeliveryRepository.countActiveAssignmentsByManagerIds` | 13.31ms |
| `DeliveryRouteHistoryRepository.saveAllAndFlush` | 4.58ms |
| `DeliveryRepository.saveAndFlush` | 3.17ms |

샘플 trace와 downstream 지표만 보면 외부 API보다 내부 DB 조회와 배정 카운트 집계 쪽이 더 눈에 띄는 비용이다. 특히 `existsByOrderId`, active assignment count 계열 쿼리가 이후 최적화 비교 지점으로 적절하다.

### 7. 결론

```text
PASS

- 총 요청 6691건, 실패 0건
- p95 596.42ms, p99 1.58s
- DB/outbox 증가량이 k6 성공 요청 수와 일치
- lock timeout, duplicate skip, warn/error 로그 없음
- 외부 API 병목은 크지 않았고, 내부 repository 조회/집계 쿼리가 우선 관찰 대상
```
