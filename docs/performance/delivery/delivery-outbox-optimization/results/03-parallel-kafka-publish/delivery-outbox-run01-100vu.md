# Kafka 병렬 발행 100VU 결과

### 1. 테스트 목적

Outbox를 한 건씩 발행하고 ACK를 기다리던 구간을 겹쳐 처리했을 때 scheduler와 backlog 변화를 확인한다.

### 2. 변경 내용

최대 100건의 `KafkaTemplate.send()`를 먼저 제출한 뒤 ACK 결과를 모아 확인하도록 변경했다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| polling | 100ms |
| Outbox batch | 최대 100건 |

### 4. 실행 결과

| 항목 | 순차 ACK | 병렬 발행 | 변화 |
| --- | ---: | ---: | ---: |
| 배송 TPS | 74.02 | 118.41 req/s | +60.0% |
| p95 | 2.20 | 1.49초 | -32.3% |
| 회복 Published TPS | 168.93 | 310.13건/s | +83.6% |
| Kafka records/request | 1.00 | 24.15 | 24.15배 |
| backlog 안정 0 | 194 | 98초 | -49.5% |

요청 56,840건은 모두 성공했고 Outbox와 Redis Stream lag도 최종 0으로 회복했다.

### 5. 모니터링 및 해석

| 항목 | 평균 / 최대 |
| --- | ---: |
| Data VM CPU | 68.45% / 98.90% |
| Hikari pending | 18.30 / 42 |
| scheduler 지연 | 평균 171.78ms / p95 201.55ms |

Kafka 요청 수는 줄고 메시지 처리율은 늘었다. Data VM CPU가 99%에 접근해 다음 단계는 ACK별 상태 UPDATE 횟수 감소로 정했다.

### 6. 결론

**WARN** — 배송 처리량과 Outbox 회복이 함께 개선됐지만 DB CPU가 상한에 근접했다.
