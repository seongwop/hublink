# Outbox Polling Interval 실험 계획

### 변경값

| 구분 | fixed delay |
| --- | ---: |
| 기준 | 1,000ms |
| 개선안 | 100ms |

현재 `fixedDelay`는 한 번의 발행 작업이 끝난 뒤부터 다음 조회까지 기다리는 시간이다. 같은 스케줄러 실행이 겹치지 않으므로 100ms로 줄여도 polling 작업이 중첩되지는 않는다.

1,000ms에서 100ms로 바로 비교해 대기 시간 제거 효과를 분명하게 확인한다. 0ms에 가까운 busy polling은 빈 backlog에서도 DB 조회를 계속 유발하므로 사용하지 않는다. 100ms에서 DB CPU, connection, 빈 조회 횟수가 과도하게 증가할 때만 250ms를 후속 후보로 측정한다.

### 설정 방식

애플리케이션 코드는 이미 다음 설정을 사용한다.

```java
@Scheduled(fixedDelayString = "${delivery.kafka.outbox.fixed-delay-ms:1000}")
```

Config Server 설정에는 환경변수로 조정할 수 있는 값을 명시한다.

```yaml
delivery:
  kafka:
    outbox:
      fixed-delay-ms: ${DELIVERY_OUTBOX_FIXED_DELAY_MS:1000}
```

기준 Run은 `DELIVERY_OUTBOX_FIXED_DELAY_MS=1000`, 개선 Run은 `DELIVERY_OUTBOX_FIXED_DELAY_MS=100`으로 실행한다. GCP 배포 workflow의 기본값은 개선값인 `100`으로 설정한다. 실제 배포 컨테이너의 환경변수와 서비스 재기동 시각을 확인한 뒤 테스트를 시작한다.

### 고정 조건

polling 간격 외에는 다음 조건을 변경하지 않는다.

- 발행 대상 조회 batch 100건
- 순차 `KafkaTemplate.send().get()` 발행
- Outbox 상태 행별 UPDATE
- 발행 대상 부분 인덱스 적용
- Delivery Hikari 최대 60
- 담당자 배정 한도 60건
- 최대 100VU, 8분, client sleep 0초

### 비교 지표

전체 배송 성능은 k6 TPS·응답 시간·실패율, DB 정합성, CPU·JVM·Hikari·PostgreSQL·로그·Zipkin을 기존과 동일하게 기록한다.

Outbox는 다음 값을 우선 비교한다.

- 테스트 중과 종료 후 Published TPS
- publishable backlog 최대값과 테스트 종료 시점 값
- backlog 안정 0까지의 회복 시간
- polling 실행·빈 조회·조회 행 수
- 발행 성공·실패 건수
- 대상 조회·Kafka ACK 대기·상태 UPDATE 평균과 p95
- Data VM CPU, PostgreSQL TPS·connection·buffer

현재 대시보드는 Published TPS와 backlog를 측정할 수 있다. polling 횟수와 단계별 지연을 정확히 비교하려면 첫 Run 전에 전용 Micrometer counter와 timer를 추가한다.
