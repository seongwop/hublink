# 배송 생성 로직 Run 01 20VU 결과

### 1. 테스트 목적

배송 생성 내부 API에 동시 요청을 가해 응답 지연이 DB connection, 배정 lock, user/hub 연동 중 어디서 커지는지 확인했다.
이번 run은 전달받은 명령어 문자열과 달리 실제 결과와 메트릭이 `/internal/deliveries` 경로와 일치해 로직 테스트 기준으로 판독했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 생성 로직 Run 01 20VU |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 전달 명령어 | `STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-kafka-load.js` |
| 실제 판독 근거 | k6 threshold `p(95)<3000`, console `409 DELIVERY_014`, Prometheus URI 집계 |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 2597 |
| HTTP TPS | 5.40 req/s |
| 실패 요청 수 | 9 |
| 실패율 | 0.34% |
| checks 성공률 | 99.65% |
| 평균 응답 시간 | 2.03s |
| p95 응답 시간 | 3.58s |
| p99 응답 시간 | 4.25s |
| 최대 응답 시간 | 4.91s |
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
| 백엔드 성공 처리 수 | 2588 |
| 409 실패 수 | 9 |
| 생성 delivery 수 | 2588 |
| 생성 route history 수 | 5176 |
| `delivery.create.succeed` outbox 수 | 2588 |
| `delivery.create.failed` outbox 수 | 0 |
| `delivery.create.dlq` outbox 수 | 0 |
| Kafka lag / 회복 시간 | N/A |

HTTP 성공 응답 2588건과 DB delivery 생성 2588건, success outbox 2588건이 일치했다.
이번 run의 실패 9건은 비동기 유실이 아니라 요청 단계 `409`로 종료된 케이스다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service CPU 최대값 | 60.97% |
| delivery-service heap 최대값 | 442,092,032 bytes |
| delivery-service GC pause 최대값 | 90ms |
| delivery-service Hikari active 최대값 | 10 |
| delivery-service Hikari pending 최대값 | 7 |
| user-service CPU 최대값 | 27.73% |
| user-service `/internal/delivery-managers/search` 평균 | 82.6ms |
| hub-service `/internal/hub-routes/path` 평균 | 2.7ms |
| `/internal/deliveries` 최대 RPS | 6.4 req/s |

delivery-service 쪽에서 connection pending이 실제로 관측됐고, user-service 조회 지연은 hub-service보다 훨씬 길었다.
GC나 hub-service 자원 포화보다는 delivery-service 내부 대기와 배정 구간 경합 영향이 더 컸다.

### 6. 로그 및 원인 분석

Loki 기준으로 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 경고가 9건 확인됐고, 같은 구간의 별도 `DELIVERY_CREATE_CUSTOM_EXCEPTION`, `FAILED_ENQUEUED`, `DLQ` 로그는 없었다.
경고는 모두 `waitSeconds=3`이며 `lock:delivery:company:*`, `lock:delivery:hub:*` 조합에서 발생했다.

즉 이번 `409` 본문에 찍힌 `DELIVERY_014`는 배정 중복 생성 방지 lock 획득 실패다.
실패가 커맨드창에 그대로 보인 이유는 [delivery-create-logic-load.js](D:/Spring%20Projects/hublink/performance/k6/delivery-create-logic-load.js:126) 에서 비정상 응답을 `console.error(...)`로 출력하고, 그 직전 [같은 파일](D:/Spring%20Projects/hublink/performance/k6/delivery-create-logic-load.js:120) 에서 `200/201/202`만 성공으로 판정하기 때문이다.

### 7. Zipkin 병목 분석

최근 성공 trace 표본에서 `http post /internal/deliveries` root span은 대략 `228~281ms`였다.
같은 trace 안에서 `user-service /internal/delivery-managers/search`는 `69~94ms`, `hub-service /internal/hub-routes/path`는 `1.8~2.7ms` 수준이었다.

Prometheus 전체 구간 평균도 user-service `82.6ms`, hub-service `2.7ms`로 같은 방향을 보였다.
다만 전체 HTTP 평균이 `2.03s`까지 올라간 점, `409` lock timeout 9건, Hikari pending 최대 7을 함께 보면 긴 꼬리는 user/hub 호출보다 delivery 배정 lock 대기와 내부 처리 대기에서 커진 것으로 보는 편이 맞다.

### 8. 결론

```text
WARN delivery create logic 20VU

- 총 요청 2597건
- checks 성공률 99.65%
- HTTP 실패율 0.34%
- p95 3.58s로 threshold 실패
- 백엔드 성공 2588건 / 409 실패 9건
- delivery Hikari pending 최대 7
- 주요 원인: DELIVERY_ASSIGNMENT_LOCK_TIMEOUT
```

다음 run은 같은 20 VU 조건에서 `RECEIVER_COMPANY_IDS`를 분산한 비교군을 먼저 한 번 찍어보는 게 좋다.
거기서 `409`와 p95가 바로 떨어지면 현재 병목은 DB 자체보다 배정 lock hot spot 쪽으로 거의 확정된다.
