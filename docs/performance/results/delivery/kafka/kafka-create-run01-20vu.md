# 배송 Kafka 생성 Run 01 20VU 결과

### 1. 테스트 목적

`delivery.create` Kafka 이벤트 주입 대비 `delivery-service` consumer의 실제 처리량과 lag 회복 시간을 확인했다.
이번 테스트는 HTTP 응답이 아니라 Kafka 소비, 배송 생성, outbox 발행까지 포함한 실제 처리 성능을 보는 데 목적이 있다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 Kafka 생성 Run 01 20VU |
| 대상 API | `POST /api/v1/deliveries/test/delivery-create` |
| 대상 topic | `delivery.create`, `delivery.create.succeed`, `delivery.create.failed` |
| k6 script | `delivery-create-kafka-load.js` |
| 실행 명령어 | `STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-kafka-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 7821 |
| HTTP TPS | 16.27 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 5.10ms |
| p95 응답 시간 | 11.17ms |
| p99 응답 시간 | 21.20ms |
| 최대 응답 시간 | 1.40s |
| max VU | 20 |

Threshold 결과는 모두 통과했다.

```text
checks
✓ rate>0.95

http_req_duration
✓ p(95)<2500
✓ p(99)<5000

http_req_failed
✓ rate<0.05
```

### 4. Kafka / DB / Outbox 결과

| 항목 | 값 |
| --- | --- |
| 테스트 종료 전 마지막 관측 최대값 | 5835 |
| 전체 구간 최대 lag | 6048 |
| lag 회복 시간 | 18분 15초 |
| 테스트 구간 배송 생성 수 | 2163 |
| lag 회복 시점까지 최종 배송 생성 수 | 7820 |
| 테스트 구간 route history 생성 수 | 4326 |
| lag 회복 시점까지 최종 route history 생성 수 | 15640 |
| `delivery.create.succeed` outbox 발행 수 | 7820 |
| `delivery.create.failed` outbox 발행 수 | 1 |

| 항목 | 값 |
| --- | --- |
| 주입 TPS | 16.27 events/s |
| 테스트 구간 실제 배송 생성 TPS | 4.51 deliveries/s |
| recovery 구간 backlog 처리 TPS | 5.17 deliveries/s |
| 전체 구간 end-to-end 처리 TPS | 4.97 deliveries/s |

`7821`건이 Kafka에 주입되었고, 최종적으로 `7820`건 성공, `1`건 실패가 확인되었다.

여기서 `5835 -> 6048` 차이는 수치 모순이라기보다 관측 시점 차이로 보는 것이 맞다.
테스트 종료 시각은 `23:20:15 KST`였고, Prometheus lag 데이터는 30초 단위 샘플로 확인했기 때문에 종료 직전 마지막 관측값은 `5835`, 종료 직후 다음 샘플에서 확인된 전체 최대값은 `6048`이었다.

### 5. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 46.19% |
| delivery-service system CPU 최대값 | 88.39% |
| heap 사용량 최대값 | 224,070,128 bytes |
| heap 사용 비율 최대값 | 10.75% |
| GC pause 최대값 | 126ms |
| Hikari active connection 최대값 | 1 |
| Hikari pending connection 최대값 | 0 |
| Kafka consumer fetch rate 최대값 | 54.93 records/s |
| Kafka poll 간격 최대값 | 108.41s |

CPU, heap, GC, DB connection pool 기준으로는 명확한 자원 포화가 보이지 않았다.

### 6. 로그 및 원인 분석

이번 테스트의 실패 1건은 집계 이상이 아니라 실제 실패 1건이다.

- `delivery-service`에서 테스트 초반 `DELIVERY_CREATE_CUSTOM_EXCEPTION`
- 에러 메시지: `허브 서비스와 통신할 수 없습니다.`
- 이후 `delivery.create.failed` outbox 1건 발행

즉, `delivery.create.failed = 1`은 정상 집계 결과이며 무시하면 안 된다. 다만 이 1건이 전체 lag 지연의 주원인은 아니다.

현재 병목은 자원 포화보다 `delivery.create` 1건당 처리 경로가 무거운 쪽에 가깝다.

- `delivery-service` consumer는 단일 listener 설정으로 보이며 `max-poll-records` 튜닝이 없다.
- 배송 생성 1건마다 hub-service 호출, user-service 호출, Redis lock 획득, assignment count 조회, 배송 저장, route history 저장, outbox 저장이 순차 실행된다.
- 저장 구간에서도 `saveAndFlush`, `saveAllAndFlush`, outbox `saveAndFlush`가 이어져 flush 비용이 누적된다.
- 실제 지표도 HTTP 주입 TPS `16.27/s` 대비 end-to-end 처리 TPS가 `4.97/s` 수준에 머물렀고, lag가 테스트 종료 후에도 한동안 증가했다.

추가로 hub-service 경고 로그:

```text
No timeLimiterConfig found for CompanyClientgetCompanyLocationUUID in time limiter registry
```

이 경고는 circuit breaker open이 아니라 `company-service` Feign 호출에 대한 TimeLimiter 설정이 없어 기본값으로 동작했다는 뜻이다. 실제 circuit breaker 상태는 테스트 동안 `open=0`, `closed=1`로 확인되었다.

### 7. 결론

```text
Load PASS with backlog

- 총 요청 7821건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- 최종 성공 7820건 / 실패 1건
- lag 최대 6048
- lag 회복 시간 18분 15초
- end-to-end 처리 TPS 4.97 deliveries/s
```

현재 상태는 "주입은 안정적이지만 consumer 처리 속도가 주입 속도를 따라가지 못하는 상태"로 보는 것이 맞다.
다음 테스트는 브로커 증설보다 `delivery.create` 파티션 수와 consumer concurrency를 먼저 늘린 뒤, 같은 부하로 비교하는 것이 더 우선순위가 높다.
