# Outbox 발행 대상 인덱스 결과

### 1. 테스트 목적

발행 완료 Outbox가 누적된 상태에서 발행 가능한 100건을 조회하는 비용을 줄인다.

### 2. 변경 내용

`PENDING`, `FAILED`이면서 재시도 5회 미만인 행만 `created_at, outbox_id` 순서로 저장하는 partial index를 추가했다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 전체 Outbox | 약 10만 건 |
| 발행 가능 Outbox | 1,000건 |
| 조회 크기 | 100건 |
| 측정 | `EXPLAIN (ANALYZE, BUFFERS, SUMMARY)` |

### 4. 실행 결과

| 항목 | 인덱스 전 | 인덱스 후 |
| --- | ---: | ---: |
| 실행 계획 | Seq Scan + top-N Sort | Partial Index Scan |
| Execution Time | 38.928ms | 0.075ms |
| Buffers | 7,120 | 35 |
| Rows Removed by Filter | 98,898 | 0 |

### 5. 모니터링 및 해석

| 항목 | 인덱스 전 | 인덱스 후 |
| --- | ---: | ---: |
| 배송 TPS | 138.11 | 106.01 req/s |
| p95 | 1.14 | 1.56초 |
| 종료 후 Published TPS | 79.71 | 79.39건/s |
| backlog 최대 | 64,798 | 51,109건 |
| 회복 시간 | 13분 30초 | 10분 30초 |

인덱스 적용 후 발행 대상 조회는 줄었지만 publisher의 순차 Kafka ACK와 건별 상태 갱신이 남아 전체 발행 처리량은 늘지 않았다. 배송 요청 수도 달라 회복 시간 감소를 인덱스 단독 효과로 보기는 어렵다.

### 6. 결론

**WARN** — 조회 쿼리는 개선됐지만 Outbox 처리량 개선은 다음 발행 단계까지 확인해야 한다.
