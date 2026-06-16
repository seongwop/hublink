# 배송 Kafka 생성 Run 03 20VU 결과

### 1. 테스트 목적

`delivery.create` 파티션 3, consumer concurrency 3 조건에서 같은 20 VU 부하를 다시 주입해 처리량과 lag 회복 시간이 얼마나 개선되는지 확인했다.
이번 테스트는 기존 단일 소비 구조 대비 실제 배송 생성 TPS와 backlog 회복 속도 차이를 보는 데 목적이 있다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | 배송 Kafka 생성 Run 03 20VU |
| 대상 API | `POST /api/v1/deliveries/test/delivery-create` |
| 대상 topic | `delivery.create`, `delivery.create.succeed`, `delivery.create.failed`, `delivery.create.dlq` |
| k6 script | `delivery-create-kafka-load.js` |
| 실행 명령어 | `STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-kafka-load.js` |
| VU / duration | 최대 20 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 비교 기준 | Run 01 20VU, 단일 consumer 구조 |

### 3. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 7819 |
| HTTP TPS | 16.28 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 응답 시간 | 5.05ms |
| p95 응답 시간 | 9.82ms |
| p99 응답 시간 | 17.54ms |
| 최대 응답 시간 | 1.10s |
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

### 4. 처리 결과

| 항목 | 값 |
| --- | --- |
| lag 최대값 | 5795 |
| lag 최종값 | 0 |
| lag 회복 시간 | 11분 00초 |
| 최종 성공 처리 수 | 7819 |
| 실패/DLQ 수 | 0 |
| 요청 대비 차이 | 0 |

부하 종료 직전 lag가 `5795`까지 증가했고, 종료 후 `11분` 뒤 0으로 회복됐다.
이번 run에서는 k6 총 주입 수 `7819`건과 최종 배송 생성/성공 outbox 수 `7819`건이 일치했다.

### 5. DB / Outbox 결과

| 항목 | 값 |
| --- | --- |
| 테스트 구간 배송 생성 수 | 3178 |
| 회복 시점까지 최종 배송 생성 수 | 7819 |
| 테스트 구간 route history 생성 수 | 6356 |
| 회복 시점까지 최종 route history 생성 수 | 15638 |
| `delivery.create.succeed` outbox 발행 수 | 7819 |
| `delivery.create.failed` outbox 발행 수 | 0 |
| `delivery.create.dlq` outbox 발행 수 | 0 |
| outbox PENDING 현재 수 | 0 |

테스트 구간 실처리 TPS는 `3178 / 480초 = 6.62 deliveries/s`였다.
회복 구간 backlog 처리 TPS는 `(7819 - 3178) / 660초 = 7.03 deliveries/s`, 전체 end-to-end 처리 TPS는 `7819 / 1140초 = 6.86 deliveries/s`로 계산된다.

### 6. Grafana 관찰 결과

| 항목 | 값 |
| --- | --- |
| delivery-service process CPU 최대값 | 64.56% |
| delivery-service system CPU 최대값 | 77.64% |
| heap 사용량 최대값 | 558,802,864 bytes |
| GC pause 최대값 | 69ms |
| Hikari active connection 최대값 | 2 |
| Hikari pending connection 최대값 | 0 |
| 주요 consumer 지표 | `delivery.create` lag 최대 5795, 최종 0 |

CPU와 heap 사용량은 이전 Run 01보다 상승했지만, Hikari pending은 끝까지 `0`이었고 GC pause도 `69ms` 수준에 머물렀다.
즉 병렬 소비 증가로 애플리케이션 자원을 더 적극적으로 사용했지만, DB connection 대기나 GC 이상 징후로 이어지지는 않았다.

### 7. 로그 및 원인 분석

Loki 기준 `DELIVERY_CREATE_CUSTOM_EXCEPTION`, `DELIVERY_CREATE_UNEXPECTED_FAILED`, `DELIVERY_CREATE_FAILED_ENQUEUED`, `DELIVERY_CREATE_DLQ_ENQUEUED`, `DELIVERY_CREATE_DUPLICATE_SKIPPED`는 이번 구간에서 확인되지 않았다.

다만 hub-service에서는 `No timeLimiterConfig found for CompanyClientgetCompanyLocationUUID` 경고가 반복 확인됐다.
이번 run에서는 delivery 실패나 DLQ로 이어지지 않았지만, hub-service 배포 설정에 timelimiter 구성이 실제 반영됐는지는 별도 확인이 필요하다.

### 8. 결론

```text
PASS throughput improved with concurrency 3 / partition 3

- 총 요청 7819건
- HTTP 실패율 0.00%
- checks 성공률 100.00%
- 최종 성공 7819건 / 실패 또는 미확인 0건
- lag 최대 5795
- lag 회복 시간 11분 00초
- 같은 20 VU 기준 실처리 TPS와 회복 시간이 모두 개선
```

Run 01과 비교하면 총 요청 수는 거의 같지만, 테스트 구간 배송 생성 수는 `2163 -> 3178`, 실처리 TPS는 `4.51 -> 6.62`, 전체 end-to-end 처리 TPS는 `4.97 -> 6.86`으로 증가했다.
lag 최대값은 `6048 -> 5795`로 소폭 감소했고, lag 회복 시간은 `18분 15초 -> 11분 00초`로 약 `39.7%` 단축됐다.
