# Delivery Outbox Optimization

배송 생성 성능을 전체적으로 관찰하되 Outbox 조회·발행·상태 갱신 처리량을 중점적으로 개선하는 실험 패키지다.

Outbox 중심 문서 구성은 이 패키지의 실험 기간에만 적용한다. 이후 배송 일반 최적화는 해당 실험의 병목과 목적에 맞는 지표를 중심으로 작성한다.

### 실험 순서

| 단계 | 디렉터리 | 변경 대상 |
| --- | --- | --- |
| 01 | `results/01-publishable-index` | 발행 대상 조회 부분 인덱스 |
| 02 | `results/02-polling-interval` | polling 간격 단축 |
| 03 | `results/03-parallel-kafka-publish` | Kafka 발행 병렬화 |
| 04 | `results/04-batch-status-update` | Outbox 상태 UPDATE 배치화 |

각 단계는 직전 단계가 적용된 상태를 기준으로 100VU Run을 수행한다. 한 번에 하나의 변수만 변경하고 나머지 조건은 동일하게 유지한다.

### 공통 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU, 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| client sleep | 0초 |
| DB pool | Delivery Hikari 최대 60 |
| 담당자 배정 한도 | 60건 |
| 초기화 SQL | `db/seed/14-reset-delivery-perf-baseline.sql` |

### 결과 문서 기준

전체 배송 성능은 다음 지표를 계속 기록한다.

- k6 요청 수, TPS, 실패율, 평균·p95·p99·최대 응답 시간
- 배송·경로·Outbox 생성 정합성
- Delivery와 Data VM CPU
- JVM heap, GC pause
- Hikari active·pending·timeout
- PostgreSQL connection, TPS, lock
- HTTP 5xx, WARN·ERROR, Circuit Breaker
- 주요 Zipkin request trace

Outbox는 다음 지표를 별도로 강조한다.

- 테스트 중과 회복 구간 Published TPS
- publishable backlog 최대·종료 시점·최종값
- 테스트 종료부터 backlog 안정 0까지 걸린 시간
- PENDING·FAILED·PUBLISHED와 retry count
- Outbox 조회·Kafka 발행·상태 UPDATE 처리 시간
- polling 횟수, 조회 건수, 발행 성공·실패 건수
- 인덱스 scan과 DB buffer·WAL 변화

### Git 보관 기준

Markdown 결과 문서와 최종 비교 이미지처럼 포트폴리오에서 직접 사용하는 자료만 Git에 보관한다.

대시보드 전체 CSV, 패널별 CSV, k6 원본 로그, 회복 원본 로그, Grafana 스냅샷은 각 Run의 `local-artifacts`에 저장한다. 이 디렉터리는 `.gitignore` 대상이지만 로컬 비교와 재분석을 위해 삭제하지 않는다.
