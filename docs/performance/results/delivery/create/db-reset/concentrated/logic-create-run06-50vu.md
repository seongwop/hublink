# 배송 생성 로직 Run 02 50VU 결과

### 1. 테스트 목적

동일 receiver 집중 조건에서 50VU까지 올렸을 때 배송 생성 로직이 얼마나 버티는지, 그리고 이전 집중 20VU에서 보였던 lock timeout이 reset 기준선에서는 사라지는지 확인했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 02 50VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `PRE_TEST_SQL_FILE=db/seed/11-reset-delivery-loadtest-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| 입력 조건 | receiver 1개 집중, baseline reset 전/후 적용 |
| VU / duration | 최대 50 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 5258 |
| HTTP TPS | 10.93 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 2.73s |
| p95 응답 시간 | 4.25s |
| p99 응답 시간 | 4.89s |
| 최대 응답 시간 | 7.87s |
| max VU | 50 |

Threshold 결과는 부분 실패였다.

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
| Spring 201 응답 카운터 증가 | 5258 |
| Spring 409 응답 카운터 증가 | 0 |
| Spring 5xx 응답 카운터 증가 | 0 |
| 중복 skip metric 증가 | 0 |
| lock timeout 로그 수 | 0 |
| failed enqueue / DLQ 로그 수 | 0 / 0 |
| DB 최종 row 직접 비교 | 종료 후 baseline reset 적용으로 제외 |

Prometheus 기준 `/internal/deliveries` 201 카운터는 종료 후 1분 tail까지 포함하면 `5258`건 증가했고, 이는 k6 총 요청 수 `5258`건과 일치했다.
즉 이번 run에서는 HTTP 성공과 백엔드 수락 결과 사이의 차이가 보이지 않았고, `409`, `5xx`, duplicate skip도 확인되지 않았다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 80.49% |
| delivery-service system CPU 최대값 | 94.17% |
| delivery-service heap 최대값 | 405,128,192 bytes |
| delivery-service GC pause 최대값 | 93ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 34 |
| `/internal/deliveries` 평균 RPS | 11.32 req/s |
| `/internal/deliveries` 최대 RPS | 14.17 req/s |
| Tomcat busy ratio 최대값 | 21.50% |
| user-service `/internal/delivery-managers/search` 평균 | 86.27ms |
| user-service `/internal/delivery-managers/search` 최대 | 125.47ms |
| hub-service `/internal/hub-routes/path` 평균 | 2.65ms |
| hub-service `/internal/hub-routes/path` 최대 | 14.71ms |

실패 없이 처리하긴 했지만 Hikari pending이 `34`까지 올라갔고 process/system CPU도 각각 `80%`, `94%` 수준까지 상승했다.
이번 구간의 지연은 downstream 호출 실패보다 delivery-service 내부 대기와 DB connection 경쟁이 더 크게 작용한 쪽에 가깝다.

### 6. 로그 및 원인 분석

Loki 기준 delivery-service `ERROR`는 0건이었고, `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT`, `DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`도 모두 0건이었다.

다만 테스트 시작 직전 `PrometheusMeterRegistry`에서 `http.client.requests.active` meter tag key mismatch 경고가 1건 있었다.
이 경고는 delivery 생성 실패 로그가 아니라 메트릭 등록 형식 경고라서, 이번 run의 비즈니스 성공/실패 판정에는 영향을 주지 않는 관측성 이슈로 보는 편이 맞다.

즉 이전 집중 20VU run에서 보였던 `409 DELIVERY_014` + lock timeout 패턴은 이번 reset 기준선 50VU run에서는 재현되지 않았다.

### 7. Zipkin 병목 분석

성공 trace 표본에서 `http post /internal/deliveries` root span은 대략 `189~271ms` 수준이었다.
같은 trace 안에서 가장 의미 있는 하위 외부 호출은 `user-service /internal/delivery-managers/search`였고, client span은 약 `65~68ms`, 대응하는 server span은 약 `70~74ms`였다.
`hub-service /internal/hub-routes/path`는 대체로 `1.1~1.3ms` 수준으로 가벼웠다.

즉 Zipkin만 보면 외부 API가 아주 느린 상태는 아니고, k6 평균 `2.73s`와 p95 `4.25s`를 설명하는 주된 원인은 trace에 직접 크게 드러나지 않는 delivery-service 내부 대기와 DB pool 경쟁 쪽으로 보는 해석이 더 맞다.

### 8. 결론

```text
WARN delivery create logic 50VU concentrated

- 총 요청 5258건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- Spring 201 증가 5258건
- 409 / 5xx / duplicate skip / lock timeout 모두 0
- p95 4.25s로 threshold 실패
- Hikari pending 최대 34
- 주요 병목: 실패보다 내부 대기와 DB connection 경쟁
```

이번 Run 02는 집중 50VU에서도 정합성은 유지됐지만, latency는 여전히 기준을 넘었다. 다음 개선 단계는 lock 실패 대응보다 delivery 내부 저장 구간과 connection 대기 완화 쪽을 보는 편이 더 맞다.
