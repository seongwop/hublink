# Kafka 발행 요청 병렬화 결과

Outbox 100건의 Kafka 발행 요청을 먼저 제출하고 ACK 결과에 따라 상태를 반영하도록 변경한 실험이다.

| 구분 | 결과 |
| --- | --- |
| 대표 결과 | [Run 02 - 100VU](run02-100vu/delivery-outbox-parallel-kafka-publish-run02-100vu.md) |
| 판정 | 기능 및 개선 효과 `PASS`, 용량 여유 `WARN` |
| HTTP TPS | 74.02 → 118.41 req/s |
| Outbox 안정 0 도달 | 194초 → 98초 |
| Kafka records/request | 1.00 → 24.15 |

배포 후 첫 측정은 처리량이 44.88 req/s로 크게 흔들려 대표값에서 제외하고, 쿨다운 후 재측정한 Run 02를 비교 기준으로 사용했다.
