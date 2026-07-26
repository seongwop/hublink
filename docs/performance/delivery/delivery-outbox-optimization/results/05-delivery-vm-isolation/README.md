# 배송 서비스 전용 VM 분리

공유 Domain B VM에서 실행하던 배송 서비스를 전용 VM으로 분리한 뒤, 같은 애플리케이션 이미지와 100VU 조건으로 성능을 다시 측정한다.

| 항목 | 결과 |
| --- | --- |
| 대표 결과 | [Run 01 - 100VU](run01-100vu/delivery-vm-isolation-run01-100vu.md) |
| HTTP TPS | 85.45 → 158.15 req/s |
| 평균 응답 시간 | 952.30ms → 514.31ms |
| p95 | 2.16s → 880.54ms |
| Outbox backlog 최대 | 4,681 → 2,062 |
| 판정 | VM 분리 효과 PASS, Data VM·downstream 용량 WARN |
