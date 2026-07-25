# Delivery Outbox Kafka 발행 요청 병렬화 Run 02 - 100VU 결과

### 1. 테스트 목적

Outbox 100건을 한 건씩 `send → ACK 대기 → DB UPDATE`하던 구조에서 Kafka 발행 요청을 먼저 제출한 뒤 ACK 결과를 확인하도록 변경했다. 동일한 100VU 조건에서 Kafka 발행 효율, backlog 회복, 배송 생성 성능을 함께 검증한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-24 17:30:21 ~ 17:38:25 KST |
| 회복 관찰 종료 | 2026-07-24 17:40:03 KST |
| 대상 API | `POST /internal/deliveries` |
| 실행 명령 | `ENV_FILE=.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 약 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | 0초 |
| Outbox fixed delay / batch | 100ms / 100건 |
| Kafka 발행 | 100건 요청 선제 제출 후 ACK 확인 |
| 상태 UPDATE | ACK별 순차 UPDATE |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| Delivery 이미지 | `hublink-delivery-service:0ff89171f53fdfdafac4e5b44e775fba403d939c` |

배포 이미지에 Kafka 병렬 발행 커밋 `3d6ac04`가 포함됐고, 테스트 전 Delivery health와 Prometheus target은 `UP`, Hikari pending과 publishable Outbox는 0이었다.

### 3. 판정

**WARN**

- 기능: **PASS** — 요청 실패 0%, DB 생성 건수 일치, Outbox 전량 발행, Redis Stream pending과 lag 0
- 개선 효과: **PASS** — HTTP TPS 60.0% 증가, 회복 Published TPS 83.6% 증가, 안정 backlog 0 도달 시간 49.5% 단축
- 용량 여유: **WARN** — Data VM CPU 최대 98.90%, Hikari active 최대 60 및 pending 최대 42

Kafka 병렬 발행 자체는 유효하다. 다만 늘어난 처리량을 ACK별 Outbox UPDATE가 받아내면서 DB와 커넥션 풀이 다음 병목으로 이동했다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 / 성공 | 56,840 / 56,840 |
| HTTP TPS | 118.41 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 686.97ms / 592.20ms |
| p90 / p95 / p99 | 1.32s / 1.49s / 1.82s |
| 최대 응답 시간 | 3.34s |
| interrupted iteration | 0 |

모든 k6 threshold를 통과했다.

### 5. 순차 발행 대비 비교

비교 기준은 같은 100VU, polling 100ms, batch 100건 조건의 [Polling Interval Run 01](../../02-polling-interval/run01-100vu/delivery-outbox-polling-interval-run01-100vu.md)이다.

| 지표 | 순차 발행 | 요청 병렬화 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 35,528 | 56,840 | +60.0% |
| HTTP TPS | 74.02 | 118.41 | +60.0% |
| 평균 응답 시간 | 1.09s | 686.97ms | -37.0% |
| p95 | 2.20s | 1.49s | -32.3% |
| 회복 Published TPS 평균 | 168.93 | 310.13 | +83.6% |
| Kafka records/request 평균 | 1.00 | 24.15 | +2,315% |
| Kafka batch size 평균 | 529.50B | 12,982.23B | +2,352% |
| Kafka record send rate 평균 | 147.47 | 260.09 | +76.4% |
| Kafka request rate 평균 | 149.92 | 10.83 | -92.8% |
| publishable backlog 최대 | 34,888 | 32,236 | -7.6% |
| backlog 안정 0 | 194초 | 98초 | -49.5% |
| Outbox scheduler 평균 | 695.54ms | 171.78ms | -75.3% |

동일한 메시지 수를 더 큰 Kafka batch로 묶어 보냈다. 브로커 요청 횟수는 크게 줄고 메시지 전송률은 증가했으며, foreground 배송 생성과 backlog 회복이 함께 개선됐다.

### 6. Outbox 회복

| 테스트 종료 후 경과 | publishable backlog |
| --- | ---: |
| 26초 | 18,881 |
| 37초 | 15,109 |
| 47초 | 11,352 |
| 57초 | 7,740 |
| 67초 | 3,719 |
| 77초 | 0 |
| 87초 | 0 |
| 98초 | 0 |

첫 0은 종료 후 77초, 3회 연속 0으로 확인한 안정 0은 98초다. 회복 구간의 Published TPS는 평균 310.13건/s, 최대 366.67건/s였다.

### 7. DB 및 비동기 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 90,440 | 56,840 |
| `p_delivery_route_histories` | 67,200 | 180,880 | 113,680 |
| `p_delivery_outboxes` | 33,600 | 90,440 | 56,840 |

- k6 성공 수와 배송·Outbox 증가량이 일치한다.
- 최종 Outbox 90,440건은 모두 `PUBLISHED`이며 publishable 잔여는 0건이다.
- Kafka producer error와 retry, HTTP 5xx, Hikari timeout, PostgreSQL rollback은 0이었다.
- Redis `deadline:requested:stream`의 최종 pending과 lag는 0이었다.

### 8. 자원 관찰

| 테스트 구간 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 48.82% | 76.26% |
| Domain B system CPU | 87.08% | 99.97% |
| Data VM CPU | 68.45% | 98.90% |
| Hikari active | 50.42 | 60 |
| Hikari pending | 18.30 | 42 |
| PostgreSQL active connection | 14.03 | 57 |
| PostgreSQL commit TPS | 680.74 | 1,185.20 |
| JVM heap | 267.57MiB | 383.05MiB |
| GC 평균 pause | 36.28ms | 70.83ms |
| Outbox 조회 지연 | 36.57ms | 103.91ms |

JVM heap과 GC는 안정적이었다. 반면 Data VM CPU와 Hikari가 상한에 가까워졌으므로 풀을 더 늘리는 것보다 Outbox 상태 UPDATE 배치화로 DB 작업 횟수를 줄이는 것이 우선이다.

### 9. Kafka 및 Zipkin

| 회복 구간 Kafka 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| records/request | 24.15 | 24.26 |
| batch size | 12,982.23B | 13,042.62B |
| record send rate | 260.09 | 337.88 |
| request rate | 10.83 | 14.03 |
| request latency | 2.39ms | 3.21ms |
| queue time | 0.50ms | 0.85ms |

Zipkin Outbox scheduler 180개 표본은 평균 171.78ms, p95 201.55ms, 최대 227.59ms였다. 부하 구간의 Delivery HTTP root span은 수집 누락이 있어 요청 지연 분석에는 k6와 Prometheus를 우선 사용했다.

### 10. 제외 측정

배포 후 첫 Run은 21,544건, 44.88 req/s, p95 3.64초였다. 실패·5xx·Kafka 오류는 없었지만 대표 Run보다 처리량이 62% 낮아 배포 직후 cold/warm 상태와 일시적인 host 포화 영향을 분리하기 어려웠다. 쿨다운 후 상태를 다시 확인하고 수행한 Run 02를 대표 결과로 채택했다.

### 11. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보존한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 전체 패널 통합 CSV |
| `local-artifacts/grafana/panels/` | 패널 target별 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | 35개 패널, 60개 target 수집 상태 |
| `local-artifacts/grafana/dashboard-snapshot.json` | 수집 당시 대시보드 정의 |
| `local-artifacts/raw/delivery-outbox-parallel-publish-run02-100vu.log` | k6 전체 출력 |
| `local-artifacts/raw/delivery-outbox-parallel-publish-run02-recovery.log` | 10초 간격 backlog 회복 기록 |
| `local-artifacts/raw/metrics-summary.csv` | 테스트 구간 핵심 지표 |
| `local-artifacts/raw/recovery-metrics-summary.csv` | 회복 구간 핵심 지표 |
| `local-artifacts/raw/loki-run02.json` | WARN·ERROR 조회 결과 |
| `local-artifacts/raw/delivery-outbox-parallel-publish-run02-zipkin.json` | Outbox scheduler trace |

Grafana 수집 범위는 17:29:21부터 17:40:03 KST까지, step은 15초이며 조회 오류 target은 0개다.

### 12. 결론

Kafka 발행 요청 병렬화로 한 scheduler 주기에서 ACK 대기 시간을 겹쳐 처리하면서 Kafka 요청 수와 scheduler 실행 시간이 크게 줄었다. 그 결과 배송 TPS는 74.02에서 118.41 req/s로 증가했고 backlog 안정 회복은 194초에서 98초로 단축됐다.

다음 실험은 동일한 100VU에서 ACK별 Outbox 상태 UPDATE를 batch UPDATE로 바꿔 Data VM CPU, PostgreSQL commit TPS, Hikari active·pending이 낮아지는지 검증한다.
