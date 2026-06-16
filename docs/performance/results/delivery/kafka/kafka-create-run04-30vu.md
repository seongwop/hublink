# 배송 Kafka 생성 Run 04 30VU 결과

### 1. 테스트 목적

consumer concurrency 3, `delivery.create` 파티션 3 조건에서 30 VU 부하를 다시 주입해 처리량, lag 회복 시간, 실제 백엔드 완료 결과를 확인했다.
이번 run은 이전 30 VU 단일 consumer 구조와 비교해 Kafka 병렬 처리 효과와 남은 실패 구간을 함께 보는 데 목적이 있다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 Kafka 생성 Run 04 30VU |
| 대상 API | `POST /api/v1/deliveries/test/delivery-create` |
| 대상 topic | `delivery.create`, `delivery.create.succeed`, `delivery.create.failed`, `delivery.create.dlq` |
| k6 script | `delivery-create-kafka-load.js` |
| 실행 명령어 | `STAGES='[{"duration":"1m","target":30},{"duration":"5m","target":30},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-kafka-load.js` |
| VU / duration | 최대 30 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 비교 기준 | Run 02 30VU, 단일 consumer 구조 |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 11716 |
| HTTP TPS | 24.39 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 3.78ms |
| p95 응답 시간 | 7.17ms |
| p99 응답 시간 | 13.11ms |
| 최대 응답 시간 | 53.12ms |
| max VU | 30 |

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

### 4. 처리 결과

| 항목 | 값 |
| --- | --- |
| lag 최대값 | 9127 |
| lag 최종값 | 0 |
| lag 회복 시간 | 22분 00초 |
| 최종 성공 처리 수 | 11505 |
| 실패/DLQ 수 | 211 / 0 |
| 요청 대비 차이 | 0 |

k6 기준으로는 11716건 모두 성공 응답이었지만, 비동기 백엔드 완료 기준으로는 `11505건 성공 + 211건 실패`로 집계됐다.
테스트 종료 시점 이후 lag는 계속 감소했고, `13:28:30`에 0으로 회복됐다.

### 5. DB / Outbox 결과

| 항목 | 값 |
| --- | --- |
| 테스트 구간 배송 생성 수 | 3053 |
| 회복 시점까지 최종 배송 생성 수 | 11505 |
| 테스트 구간 route history 생성 수 | 6106 |
| 회복 시점까지 최종 route history 생성 수 | 23010 |
| `delivery.create.succeed` outbox 발행 수 | 11505 |
| `delivery.create.failed` outbox 발행 수 | 211 |
| `delivery.create.dlq` outbox 발행 수 | 0 |
| outbox PENDING 현재 수 | 0 |

테스트 구간 실처리 TPS는 `3053 / 480초 = 6.36 deliveries/s`였다.
lag 회복 구간까지 포함한 전체 완료 처리량은 `11505 / 1800초 = 6.39 deliveries/s` 수준으로 계산된다.

### 6. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 36.07% |
| delivery-service system CPU 최대값 | 60.88% |
| heap 사용량 최대값 | 580,466,688 bytes |
| GC pause 최대값 | 55ms |
| Hikari active connection 최대값 | 2 |
| Hikari pending connection 최대값 | 0 |
| HTTP RPS 최대값 | 29.97 req/s |
| Kafka broker host CPU 최대값 | 51.07% |
| Kafka broker host memory 최대값 | 28.94% |
| 주요 consumer 지표 | `delivery.create` lag 최대 9127, 최종 0 |

delivery-service와 Kafka broker host 모두 CPU가 올라가긴 했지만 포화 구간으로 보일 정도는 아니었다.
DB connection pending도 끝까지 `0`이라서, 이번 run에서 눈에 띄는 병목은 DB 풀 고갈이나 브로커 호스트 자원 부족보다는 애플리케이션 처리 경로 쪽에 더 가깝다.

### 7. 로그 및 원인 분석

Loki 기준으로 이번 구간에서 `DELIVERY_CREATE_DUPLICATE_SKIPPED`, `DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DLQ_ENQUEUED`, `DELIVERY_CREATE_UNEXPECTED_FAILED` 로그는 직접 확인되지 않았다.
그런데 DB에서는 `delivery.create.failed` outbox가 `211건` 확인돼, 실패 결과는 존재하지만 delivery-service 로그 패널에서는 같은 사실이 드러나지 않는 관측 공백이 있다.

즉 이번 run은 "실패가 없었다"가 아니라 "HTTP는 모두 성공했고, 백엔드에서는 211건이 failed topic으로 분기됐지만 그 근거 로그가 Loki에서 잘 보이지 않았다"에 가깝다.
실제 원문 로그 기준으로는 `DELIVERY_CREATE_CUSTOM_EXCEPTION` 뒤에 `DELIVERY_CREATE_FAILED_ENQUEUED`가 이어졌고, 이번 실패는 Kafka publish 실패가 아니라 배송 생성 처리 중 `CustomException`이 발생한 케이스로 해석된다.
실패 211건 중 209건이 테스트 초반 1분에 몰렸고 동일 receiver 회사들이 이후에는 정상 성공했기 때문에, 이번 run의 가장 유력한 원인은 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 또는 `DELIVERY_ASSIGNMENT_CONFLICT` 같은 초반 배정 경합이다.
다음 테스트부터는 `errorCode`, `orderId`, `lockKeys`가 함께 로그에 남도록 delivery-service 로그를 보강했다.

### 8. Zipkin 병목 분석

Zipkin에서 확인한 delivery trace에서는 `delivery-service -> UserClient -> /delivery-managers/search` 호출이 가장 의미 있는 하위 외부 호출로 보였고, client span 샘플은 대략 `60~94ms` 범위였다.
Prometheus 기준 같은 시점 `user-service /internal/delivery-managers/search` 평균은 약 `131ms`, `hub-service /internal/hub-routes/path` 평균은 약 `4.5ms`로 확인되어, 이번 구간에서 더 눈에 띄는 downstream 지연은 hub-service보다 user-service 조회 경로였다.

다만 trace 이름이 `http post`, `http get`처럼 일반적이라 모든 느린 케이스를 정량적으로 묶기는 어려웠고, hub-service 경로보다 user-service 조회 경로가 현재 Zipkin에서는 더 선명하게 보였다.

### 9. 결론

```text
WARN throughput improved, but backend failures remain

- 총 요청 11716건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- 최종 성공 11505건 / failed 211건 / dlq 0건
- lag 최대 9127
- lag 회복 시간 22분 00초
- Kafka 병렬 처리 효과는 확인됐지만 실패 211건 원인 추적과 로그 가시성 보강이 필요
```

Run 02와 비교하면 같은 30 VU에서도 테스트 구간 배송 생성 수는 `2461 -> 3053`으로 늘었고, lag 회복 시간은 `31분 30초 -> 22분 00초`로 줄었다.
즉 consumer 3 / partition 3 전환으로 처리 효율은 분명 좋아졌지만, 이번에는 `failed outbox 211건`이 새로 드러났기 때문에 다음 단계로 넘어가기 전 실패 원인과 로그/메트릭 관측 누락부터 정리하는 편이 좋다.
