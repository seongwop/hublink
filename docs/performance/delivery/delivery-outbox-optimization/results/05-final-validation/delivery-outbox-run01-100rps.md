# Outbox 최종 파이프라인 고정 100 RPS 결과

### 1. 테스트 목적

부분 인덱스, polling 100ms, Kafka 병렬 발행, 상태 배치 UPDATE의 누적 효과를 같은 고정 100 RPS 조건에서 비교한다.

### 2. 변경 내용

| 구분 | 구성 |
| --- | --- |
| 개선 전 | 인덱스 없음, polling 1,000ms, 순차 발행, 단건 UPDATE |
| 개선 후 | partial index, polling 100ms, 병렬 발행, 배치 UPDATE |

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 부하 모델 | `ramping-arrival-rate` |
| 패턴 | 30초 동안 0→100 RPS, 3분 유지 |
| 워밍업 | 5 RPS 30초 후 DB 재초기화 |
| 인프라 | 전후 동일 VM·DB·캐시 구성 |

### 4. 실행 결과

| 항목 | 개선 전 | 개선 후 | 변화 |
| --- | ---: | ---: | ---: |
| 실패율 | 0% | 0% | 동일 |
| 평균 응답 | 48.28 | 33.71ms | -30.2% |
| p95 | 85.95 | 46.25ms | -46.2% |
| Outbox Published TPS | 67.67 | 100.02건/s | +47.8% |
| backlog 최대 | 5,895 | 9건 | -99.8% |
| backlog 0 확인 | 90초 | 15초 이내 | 75초 이상 단축 |

개선 후에는 유입량과 같은 약 100건/s로 Outbox를 발행해 부하 중 backlog가 거의 쌓이지 않았다. 배송·경로·Outbox 증가량은 성공 요청 수와 일치했다.

### 5. 모니터링 및 해석

| 항목 | 개선 전 | 개선 후 |
| --- | ---: | ---: |
| Delivery process CPU | 81.62% | 39.28% |
| Data VM CPU | 58.67% | 65.36% |
| Hikari pending | 0 | 0 |

Data VM CPU 증가는 개선 후 미뤄 두지 않고 발행과 상태 갱신까지 같은 부하 구간에서 처리한 결과다.

### 6. 결론

**PASS** — 고정 100 RPS에서 실패와 dropped iteration 없이 Outbox 발행량이 유입량을 따라잡았다.

[100VU 저장 시계열](../../../../../../monitoring/grafana/provisioning/dashboards/comparison-100vu/04-outbox-pipeline-comparison.json) · [고정 RPS 저장 시계열](../../../../../../monitoring/grafana/provisioning/dashboards/comparison-fixed-rps/04-outbox-throughput-comparison.json)
