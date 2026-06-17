# 배송 생성 로직 Run 03 20VU 결과

### 1. 테스트 목적

`RECEIVER_COMPANY_IDS`를 분산했을 때 20VU 조건에서 배송 생성 로직의 lock 경합이 얼마나 줄고, 응답 지연과 내부 자원 사용은 어떻게 바뀌는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 03 20VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `SUPPLIER_COMPANY_ID=고정, RECEIVER_COMPANY_IDS=18개 분산, STAGES=[1m 20VU, 5m 유지, 2m 0VU] ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 2703 |
| HTTP TPS | 5.62 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 1.91s |
| p95 응답 시간 | 3.21s |
| p99 응답 시간 | 3.58s |
| 최대 응답 시간 | 4.12s |
| max VU | 20 |

Threshold 결과는 부분 실패다.

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✓ p(99)<6000

http_req_failed
✓ rate<0.10
```

### 4. 처리 결과

| 항목 | 값 |
| --- | --- |
| 백엔드 성공 처리 수 | 2703 |
| 생성 delivery 수 | 2703 |
| 생성 route history 수 | 5406 |
| `delivery.create.succeed` outbox 수 | 2703 |
| `delivery.create.failed` outbox 수 | 0 |
| `delivery.create.dlq` outbox 수 | 0 |
| Kafka lag / 회복 시간 | N/A |

HTTP 성공 2703건과 DB delivery 생성 2703건, success outbox 2703건이 일치했다.
이번 run에서는 `409 DELIVERY_014`나 비동기 실패 없이 전 요청이 정상 완료됐다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 33.33% |
| delivery-service system CPU 최대값 | 48.69% |
| delivery-service heap 최대값 | 432,728,096 bytes |
| delivery-service GC pause 최대값 | 102ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 7 |
| user-service `/internal/delivery-managers/search` 평균 | 81.5ms |
| user-service `/internal/delivery-managers/search` 최대 | 85.1ms |
| hub-service `/internal/hub-routes/path` 평균 | 5.0ms |
| hub-service `/internal/hub-routes/path` 최대 | 6.85ms |
| `/internal/deliveries` 최대 RPS | 6.4 req/s |

분산 실행 후 lock timeout은 사라졌지만 Hikari pending 최대값은 여전히 7까지 올라갔다.
CPU와 heap은 이전 run보다 낮아졌지만 p95는 아직 3초를 넘겨, lock 경합만 제거해도 지연이 완전히 해소되지는 않았다.

### 6. 로그 및 원인 분석

Loki 기준으로 이번 구간에는 `ERROR`, `WARN`, `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`, `DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`가 모두 0건이었다.

이전 Run 01에서는 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 9건과 `409 DELIVERY_014`가 있었는데, 이번 Run 02에서는 해당 현상이 사라졌다.
즉 분산 실행은 lock hot spot 완화에는 효과가 있었고, 이번 p95 초과의 주된 원인은 더 이상 lock timeout 재시도 쪽이 아니다.

### 7. Zipkin 병목 분석

성공 trace 샘플에서 `http post /internal/deliveries` root span은 약 `228.7ms`였다.
같은 시간대 하위 호출을 보면 `delivery-service -> UserClient -> /delivery-managers/search`가 대략 `63~104ms` 구간으로 가장 의미 있는 외부 호출이었고, `hub-service /internal/hub-routes/path`는 약 `1.8~2.8ms` 수준이었다.

즉 외부 호출 병목은 여전히 user-service 쪽이 더 크고, hub-service 경로 조회는 이번 구간에서 큰 부담이 아니었다.

### 8. 결론

```text
WARN delivery create logic 20VU distributed

- 총 요청 2703건
- checks 성공률 100.00%
- HTTP 실패율 0.00%
- delivery / outbox 2703건 일치
- lock timeout 0건, 409 0건
- p95 3.21s로 threshold 초과
- 남은 병목 후보: Hikari pending + user-service 조회 지연
```

Run 01과 비교하면 분산 실행으로 `409` 실패와 lock timeout은 제거됐고, p95도 `3.58s -> 3.21s`로 소폭 개선됐다.
다만 Hikari pending 최대값이 그대로 7이어서, 다음 단계는 lock 분산보다 delivery 내부 DB 대기와 `delivery-managers/search` 구간 최적화 쪽으로 넘어가는 편이 맞다.
