# 배송 생성 로직 No Sleep Run 01 20VU 결과

### 1. 테스트 목적

`db-reset + concentrated + no-sleep` 조건에서 20VU까지 올렸을 때 배송 생성 로직의 lock 경합 재현 여부와 현재 기준선 성능을 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 No Sleep Run 01 20VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/11-reset-delivery-loadtest-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| 입력 조건 | receiver 1개 집중, baseline reset, `SLEEP_SECONDS=0` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 5062 |
| HTTP TPS | 10.53 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 554.46ms |
| p95 응답 시간 | 1.30s |
| p99 응답 시간 | 1.72s |
| 최대 응답 시간 | 3.07s |
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

### 4. 처리 결과

| 항목 | 값 |
| --- | --- |
| Spring 201 응답 카운터 증가 | 5061 |
| Spring 409 응답 카운터 증가 | 0 |
| Spring 5xx 응답 카운터 증가 | 0 |
| duplicate skip metric 증가 | 0 |
| lock timeout 로그 수 | 0 |
| failed enqueue / DLQ 로그 수 | 0 / 0 |
| DB 최종 row 직접 비교 | 종료 후 baseline reset 적용으로 제외 |

Prometheus 기준 `/internal/deliveries` 201 카운터 증가는 `5061`건으로 k6 총 요청 수 `5062`건보다 1건 적었다.
다만 같은 구간에 `409`, `5xx`, duplicate skip, lock timeout이 모두 0건이었고 k6 실패 요청도 0건이어서, 이 1건 차이는 스크레이프 경계 오차로 보는 해석이 가장 자연스럽다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 53.38% |
| delivery-service system CPU 최대값 | 67.70% |
| delivery-service heap 최대값 | 417,595,840 bytes |
| delivery-service GC pause 최대값 | 48ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 3 |
| `/internal/deliveries` 평균 RPS | 8.44 req/s |
| `/internal/deliveries` 최대 RPS | 15.30 req/s |
| DeliveryRepository.existsByOrderId 최대 | 33.87ms |
| DeliveryRepository.countActiveAssignmentsByManagerIds 최대 | 28.25ms |
| DeliveryRouteHistoryRepository.countActiveAssignmentsByManagerIds 최대 | 32.48ms |

delivery-service 내부 자원은 안정적으로 유지됐고, Hikari pending도 최대 `3` 수준에 그쳤다.
이 구간에서는 connection pool 고갈이나 lock 대기가 두드러지지 않았고, repository 집계 쿼리도 수십 ms 수준에서 유지됐다.

### 6. 로그 및 병목 분석

Loki 기준 delivery-service `WARN`, `ERROR`, `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_CREATE_FAILED_ENQUEUED`는 모두 0건이었다.

Zipkin 샘플 trace에서는 root span이 대략 `61.9~77.9ms`, `user-service /internal/delivery-managers/search`가 `65.95~83.65ms`, `hub-service /internal/hub-routes/path`가 `1.23~1.47ms` 수준이었다.
즉 이번 run의 응답 시간은 lock 경합이나 예외 처리보다 정상 경로의 downstream 호출과 애플리케이션 내부 처리 시간이 누적된 결과에 가까웠다.

### 7. 결과 해석

이번 `db-reset + concentrated + no-sleep` 20VU에서는 lock timeout이 재현되지 않았다.
기존 집중 20VU run에서 보였던 `409 DELIVERY_014` 패턴도 나타나지 않았고, p95는 `3.58s -> 1.30s`, 실패 요청은 `9건 -> 0건`, Hikari pending은 `7 -> 3`으로 개선됐다.

즉 현재 기준선에서는 think time을 제거해도 20VU 수준까지는 lock 경합보다 정상 처리 여유가 더 크다고 보는 편이 맞다.

### 8. 결론

```text
PASS delivery create logic no-sleep 20VU

- 총 요청 5062건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- 409 / 5xx / duplicate skip / lock timeout 모두 0
- p95 1.30s
- Hikari pending 최대 3
- 주요 병목: lock 재현 없음, 정상 경로 처리 시간 중심
```

다음 단계는 같은 `db-reset + concentrated + no-sleep` 축에서 50VU를 다시 측정해 lock 경합이 실제로 언제부터 드러나는지 확인하는 것이다.
