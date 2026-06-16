# 배송 Kafka 생성 Run 02 30VU 결과

### 1. 테스트 목적

기존 20 VU 조건보다 높은 유입량에서 `delivery.create` consumer backlog와 lag 회복 시간을 확인했다.
이번 테스트는 동일 구조에서 부하만 높였을 때 처리 한계가 얼마나 빠르게 악화되는지 보는 데 목적이 있다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 Kafka 생성 Run 02 30VU |
| 대상 API | `POST /api/v1/deliveries/test/delivery-create` |
| 대상 topic | `delivery.create`, `delivery.create.succeed`, `delivery.create.failed` |
| k6 script | `delivery-create-kafka-load.js` |
| 실행 명령어 | `STAGES='[{"duration":"1m","target":30},{"duration":"5m","target":30},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-kafka-load.js` |
| VU / duration | 최대 30 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 11720 |
| HTTP TPS | 24.40 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 3.50ms |
| p95 응답 시간 | 5.55ms |
| p99 응답 시간 | 10.98ms |
| 최대 응답 시간 | 106.88ms |
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

### 4. Kafka 결과

| 항목 | 값 |
| --- | --- |
| lag 최대값 | 9661 |
| lag 최대 시점 | 테스트 종료 직전 |
| lag 회복 시간 | 31분 30초 |

부하 종료 후에도 backlog가 한동안 남았고, `delivery.create` lag는 계단식으로 감소하다가 약 31분 30초 후 0으로 회복되었다.

### 5. DB / Outbox 결과

| 항목 | 값 |
| --- | --- |
| 테스트 구간 배송 생성 수 | 2461 |
| lag 회복 시점까지 최종 배송 생성 수 | 11714 |
| 테스트 구간 route history 생성 수 | 4922 |
| lag 회복 시점까지 최종 route history 생성 수 | 23428 |
| `delivery.create.succeed` outbox 발행 수 | 11714 |
| `delivery.create.failed` outbox 발행 수 | 0 |
| `delivery.create.dlq` outbox 발행 수 | 0 |

k6 총 주입 수는 `11720`건이지만, DB 배송 생성과 `delivery.create.succeed` outbox 발행은 `11714`건으로 확인되었다.
즉 `6`건 차이가 있으며, 이 구간에서는 `failed`나 `dlq`로 분류되지도 않아 별도 원인 확인이 필요하다.

### 6. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 25.36% |
| delivery-service system CPU 최대값 | 39.74% |
| heap 사용량 최대값 | 168,760,824 bytes |
| GC pause 최대값 | 36ms |
| Hikari active connection 최대값 | 2 |
| Hikari pending connection 최대값 | 0 |
| Kafka poll 간격 최대값 | 102.36s |

CPU, heap, GC, DB connection pool 기준으로는 여전히 뚜렷한 자원 포화가 보이지 않았다.

### 7. 결과 해석

30 VU 조건에서 HTTP 주입 TPS는 `24.40 req/s`까지 올라갔지만, consumer는 그 유입량을 감당하지 못해 lag가 `9661`까지 증가했다.

이전 20 VU 테스트와 비교하면:

- HTTP TPS: `16.27 -> 24.40 req/s`
- lag 최대값: `6048 -> 9661`
- lag 회복 시간: `18분 15초 -> 31분 30초`

즉, 부하를 높였을 때 처리량이 비례해서 늘지 않고 backlog와 회복 시간이 더 빠르게 악화되었다.

Grafana 기준 CPU, heap, Hikari pending이 크게 치솟지 않았기 때문에, 현재 병목은 인프라 자원 부족보다는 `delivery.create` 소비 경로 자체의 처리 비용과 단일 소비 구조에 더 가깝다.

또한 이번 테스트에서는 `11720`건 주입 대비 `11714`건만 성공 처리되어 `6`건 차이가 남았다.
Loki 기준 `DELIVERY_CREATE_CUSTOM_EXCEPTION`, `DELIVERY_CREATE_UNEXPECTED_FAILED`, `DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DLQ_ENQUEUED`는 확인되지 않았다.

다만 `DELIVERY_CREATE_DUPLICATE_SKIPPED`는 `info` 로그이고 현재 `delivery-service`의 root log level이 `WARN`이기 때문에, 이번 로그 수집 범위에서는 직접 확인할 수 없다.
코드상 `DUPLICATE_ORDER_DELIVERY` 예외가 consumer에서 발생하면 failed/dlq 발행 없이 `DELIVERY_CREATE_DUPLICATE_SKIPPED`만 남기고 return 하므로, 현재 `6`건 차이에 대한 가장 유력한 후보는 이 경로다.

### 8. 결론

```text
Stress PASS with larger backlog and count gap

- 총 요청 11720건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- lag 최대 9661
- lag 회복 시간 31분 30초
- 최종 성공 11714건 / 미확인 6건
- 20 VU 대비 backlog와 회복시간 모두 악화
```

현재 구조에서는 부하 증가에 따라 lag와 recovery cost가 빠르게 커지는 경향이 확인되었다.
다음 단계는 같은 부하 조건에서 `delivery.create` 파티션 수와 consumer concurrency를 조정한 뒤 비교 측정하는 것이 적절하다.
