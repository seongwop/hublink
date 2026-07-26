# Outbox 상태 배치 UPDATE 결과

Kafka ACK에 성공한 Outbox를 행별로 갱신하던 구조에서 최대 100건을 한 SQL로 갱신하도록 변경한 실험이다.

| 구분 | 결과 |
| --- | --- |
| 대표 결과 | [Run 01 - 100VU](run01-100vu/delivery-outbox-batch-status-update-run01-100vu.md) |
| 판정 | 기능 및 Outbox 개선 효과 `PASS`, 전체 시스템 처리량 `WARN` |
| Outbox scheduler 평균 | 171.78ms → 13.97ms |
| publishable backlog 최대 | 32,236 → 4,681 |
| backlog 안정 0 | 98초 → 57초 |
| HTTP TPS | 118.41 → 85.45 req/s |

배치 UPDATE로 Outbox worker와 DB 부하는 줄었지만 발행이 빨라진 이벤트가 같은 Domain B VM의 Order·Slack consumer로 즉시 전달되면서 공유 호스트 CPU가 다시 포화됐다. 다음 개선은 Outbox UPDATE보다 downstream consumer 격리나 처리량 제어를 우선 검토한다.
