# Delivery Outbox 최적화

배송 생성 트랜잭션은 Outbox를 저장하고 스케줄러가 Kafka로 발행한다. 발행 대상 조회, Kafka ACK 대기, 상태 UPDATE를 순서대로 개선했다.

## 변경 단계

| 단계 | 변경 | 대표 결과 |
| --- | --- | --- |
| [01. 발행 대상 인덱스](results/01-publishable-index/delivery-outbox-run01.md) | 발행 가능한 행만 partial index로 구성 | 조회 38.928ms에서 0.075ms로 감소 |
| [02. polling 단축](results/02-polling-interval/delivery-outbox-run01-100vu.md) | fixed delay 1,000ms에서 100ms로 변경 | 회복 630초에서 194초로 단축 |
| [03. Kafka 병렬 발행](results/03-parallel-kafka-publish/delivery-outbox-run01-100vu.md) | send 선제 제출 후 ACK 회수 | 회복 Published TPS 83.6% 증가 |
| [04. 상태 배치 갱신](results/04-batch-status-update/delivery-outbox-run01-100vu.md) | ACK 성공 ID 일괄 UPDATE | scheduler 평균 171.78ms에서 13.97ms로 감소 |
| [05. 최종 검증](results/05-final-validation/delivery-outbox-run01-100rps.md) | 누적 개선 전후 동일 100 RPS 비교 | backlog 최대 5,895건에서 9건으로 감소 |
| [06. 용량 경계](results/06-capacity-boundary/delivery-outbox-capacity-summary.md) | 140·150·160 RPS 단계 측정 | 비포화 상한 140 RPS |

## 구현 위치

- partial index: [`PartialIndexInitializer`](../../../../delivery-service/src/main/java/com/msa/delivery_service/infrastructure/PartialIndexInitializer.java)
- 발행 처리: [`DeliveryOutboxService`](../../../../delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryOutboxService.java)
- 상태 일괄 갱신: [`DeliveryOutboxCommandRepositoryImpl`](../../../../delivery-service/src/main/java/com/msa/delivery_service/repository/DeliveryOutboxCommandRepositoryImpl.java)
- polling 설정: [`delivery-service.yml`](../../../../config-repo/delivery-service.yml)

현재 partial index는 애플리케이션 시작 시 생성한다. 실제 운영 배포에서는 DDL 권한과 잠금 영향을 분리할 수 있도록 migration 도구로 관리하는 편이 적합하다.
