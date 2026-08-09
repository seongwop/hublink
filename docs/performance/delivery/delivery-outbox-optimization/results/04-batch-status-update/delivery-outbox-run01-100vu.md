# Outbox 상태 배치 갱신 100VU 결과

### 1. 테스트 목적

Kafka ACK 성공 건을 행별로 UPDATE하던 방식을 일괄 UPDATE로 바꿔 DB 작업 횟수와 scheduler 시간을 줄인다.

### 2. 변경 내용

성공한 Outbox ID를 모아 `WHERE outbox_id IN (...)` 조건의 단일 UPDATE로 상태를 변경했다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| polling | 100ms |
| Kafka 발행 | 최대 100건 병렬 제출 |
| VM 배치 | Domain B 공유 VM |

### 4. 실행 결과

| 항목 | 건별 UPDATE | 배치 UPDATE | 변화 |
| --- | ---: | ---: | ---: |
| scheduler 평균 | 171.78 | 13.97ms | -91.9% |
| scheduler p95 | 201.55 | 35.14ms | -82.6% |
| backlog 최대 | 32,236 | 4,681건 | -85.5% |
| 종료 시 backlog | 30,580 | 5건 | -99.98% |
| 배송 TPS | 118.41 | 85.45 req/s | -27.8% |
| p95 | 1.49 | 2.16초 | +45.0% |

요청 41,020건은 모두 성공했고 최종 Outbox는 전부 `PUBLISHED`로 전환됐다.

### 5. 모니터링 및 해석

| 항목 | 건별 UPDATE | 배치 UPDATE |
| --- | ---: | ---: |
| Data VM CPU 평균 | 68.45% | 50.68% |
| Hikari pending 평균 | 18.30 | 12.06 |
| Domain B CPU 평균 | 87.08% | 92.13% |

DB 작업은 줄었지만 Outbox가 빨라지면서 Order·Slack consumer 작업도 같은 공유 VM에 유입됐다. 배송 TPS 감소는 공유 VM CPU 경합이 포함된 결과다.

### 6. 결론

**WARN** — 상태 갱신과 backlog는 개선됐지만 공유 VM 경합으로 배송 처리량은 감소했다. 후속 검증은 배송 서비스를 분리한 환경에서 진행한다.
