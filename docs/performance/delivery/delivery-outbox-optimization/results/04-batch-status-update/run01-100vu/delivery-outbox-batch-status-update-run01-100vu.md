# Delivery Outbox 상태 배치 UPDATE Run 01 - 100VU 결과

### 1. 테스트 목적

Kafka ACK에 성공한 Outbox를 최대 100번의 행별 UPDATE로 반영하던 구조에서 `WHERE outbox_id IN (...)` 단일 UPDATE로 바꿨을 때 scheduler 실행 시간, backlog와 DB 부하가 줄어드는지 검증한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-26 02:13:29 ~ 02:21:32 KST |
| 회복 관찰 종료 | 2026-07-26 02:23:32 KST |
| 대상 API | `POST /internal/deliveries` |
| 실행 명령어 | `ENV_FILE=.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | 0초 |
| Outbox fixed delay / batch | 100ms / 100건 |
| Kafka 발행 | 100건 요청 선제 제출 후 ACK 확인 |
| 상태 UPDATE | ACK 성공 ID를 모아 단일 `IN` UPDATE |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| Delivery 이미지 | `hublink-delivery-service:07d64df4ce5ace35bf4e36a8d9df1763e90bbc63` |

배포 이미지에 배치 UPDATE 커밋 `bfb1d50`이 포함된 것을 확인했다. 테스트 전 publishable Outbox, Kafka consumer lag와 Hikari pending은 0이었고 Delivery·DB·Redis·Kafka·Grafana·Prometheus·Loki·Zipkin은 모두 응답했다.

### 3. 판정

**WARN**

- 기능: **PASS** — HTTP 실패 0%, DB 생성 건수 일치, Outbox 전량 `PUBLISHED`
- Outbox 개선: **PASS** — scheduler 평균 91.9% 단축, backlog 최대 85.5% 감소
- DB 부하: **개선 정황** — Data VM CPU 평균 26.0%, PostgreSQL commit TPS 평균 21.3% 감소
- 전체 처리량: **WARN** — HTTP TPS 27.8% 감소, p95 45.0% 증가
- 공유 호스트: **WARN** — Domain B CPU 평균 92.1%, 최대 100%, Order·Slack consumer backlog 발생

배치 UPDATE 자체는 유효하다. 다만 Outbox가 이벤트를 더 빨리 밀어내면서 같은 Domain B VM의 Order·Slack consumer 부하가 앞당겨졌고, 공유 호스트 포화가 foreground 배송 생성 처리량을 제한했다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 / 성공 | 41,020 / 41,020 |
| HTTP TPS | 85.45 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 952.30ms / 765.04ms |
| p90 / p95 / p99 | 1.93s / 2.16s / 2.55s |
| 최대 응답 시간 | 4.27s |
| interrupted iteration | 0 |

`p95 < 3초`, `p99 < 6초`, 실패율과 checks threshold를 모두 통과했다.

### 5. 배치 UPDATE 적용 전후 비교

비교 기준은 같은 100VU, polling 100ms, Kafka 발행 요청 병렬화 조건의 [병렬 발행 Run 02](../../03-parallel-kafka-publish/run02-100vu/delivery-outbox-parallel-kafka-publish-run02-100vu.md)다.

| 지표 | 행별 UPDATE | 배치 UPDATE | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 56,840 | 41,020 | -27.83% |
| HTTP TPS | 118.41 | 85.45 | -27.83% |
| 평균 응답 시간 | 686.97ms | 952.30ms | +38.62% |
| p95 | 1.49s | 2.16s | +44.97% |
| p99 | 1.82s | 2.55s | +40.11% |
| 테스트 중 Published TPS 평균 | 50.57 | 80.24 | +58.69% |
| publishable backlog 최대 | 32,236 | 4,681 | -85.48% |
| 테스트 종료 시 backlog | 30,580 | 5 | -99.98% |
| backlog 안정 0 | 98초 | 57초 | -41.84% |
| Outbox scheduler 평균 | 171.78ms | 13.97ms | -91.87% |
| Outbox scheduler p95 | 201.55ms | 35.14ms | -82.57% |
| Outbox scheduler 최대 | 227.59ms | 44.02ms | -80.66% |

Outbox 처리 능력은 명확히 개선됐다. 이전에는 테스트 종료 시 30,580건을 남겼지만 이번에는 5건만 남겼다. 반면 빠르게 전달된 이벤트를 처리하는 downstream 서비스가 공유 VM CPU를 사용하면서 end-to-end HTTP TPS는 감소했다.

### 6. Outbox 처리와 회복

| 관찰값 | 결과 |
| --- | ---: |
| 테스트 중 backlog 평균 / 최대 | 214.33 / 4,681 |
| 테스트 종료 직전 | 5 |
| Prometheus 첫 0 | 종료 12초 후 |
| Prometheus 3회 연속 0 | 종료 42초 후 |
| DB 직접 조회 3회 연속 0 | 종료 57초 후 |
| 최종 PENDING / FAILED | 0 / 0 |

초기 4,681건 스파이크를 제외하면 대부분의 테스트 구간에서 backlog는 200건 미만이었다. 보수적인 직접 DB 조회 기준으로도 57초 안에 안정 0을 확인했다.

Kafka producer error와 retry rate는 0이었다. worker가 빨라지면서 테스트 구간 records/request는 `12.49 → 7.56`, Kafka request rate는 `4.62 → 12.48회/s`로 바뀌었다. backlog를 빠르게 비운 대신 더 작은 Kafka batch를 더 자주 전송하는 trade-off가 생겼다.

### 7. DB 및 비동기 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 74,620 | 41,020 |
| `p_delivery_route_histories` | 67,200 | 149,240 | 82,040 |
| `p_delivery_outboxes` | 33,600 | 74,620 | 41,020 |

- k6 성공 수와 배송·Outbox 증가량이 일치했다.
- 경로 이력은 배송당 2건으로 정확히 증가했다.
- 최종 Outbox 74,620건은 모두 `PUBLISHED`였다.
- PostgreSQL rollback TPS, Hikari timeout, Kafka producer error·retry는 0이었다.
- Kafka `delivery-service / delivery.create` consumer lag는 전 구간 0이었다.
- Redis AI·Delivery consumer는 최종 pending과 lag가 0이었다.
- Slack Redis consumer는 최종 pending 4, lag 8,920으로 별도 downstream backlog가 남았다.

### 8. 자원 관찰

| 테스트 구간 지표 | 행별 UPDATE 평균 / 최대 | 배치 UPDATE 평균 / 최대 | 변화 |
| --- | ---: | ---: | ---: |
| Delivery process CPU | 48.82% / 76.26% | 45.89% / 76.20% | 평균 -6.0% |
| Domain B system CPU | 87.08% / 99.97% | 92.13% / 100.00% | 평균 +5.8% |
| Data VM CPU | 68.45% / 98.90% | 50.68% / 79.44% | 평균 -26.0% |
| Hikari active | 50.42 / 60 | 47.18 / 60 | 평균 -6.4% |
| Hikari pending | 18.30 / 42 | 12.06 / 38 | 평균 -34.1% |
| PostgreSQL active connection | 14.03 / 57 | 9.61 / 48 | 평균 -31.5% |
| PostgreSQL commit TPS | 680.74 / 1,185.20 | 535.64 / 870.08 | 평균 -21.3% |
| JVM heap | 267.57 / 383.05MiB | 248.90 / 393.16MiB | 안정 |
| GC 평균 pause | 36.28 / 70.83ms | 53.68 / 98.50ms | 100ms 미만 |

DB와 connection 지표는 낮아졌지만 HTTP 요청 수도 27.8% 적으므로 감소분 전체를 배치 UPDATE 효과로 환산할 수는 없다. 직접적인 효과 판단에는 scheduler 지연과 backlog를 우선 사용한다.

JVM heap과 GC는 병목이 아니다. 가장 강한 제한 신호는 Domain B system CPU다.

### 9. downstream·로그·Zipkin

| Kafka consumer lag 최대 | 이전 Run | 이번 Run |
| --- | ---: | ---: |
| `order-group / delivery.create.succeed` | 257 | 3,695 |
| `order-group / delivery.create.failed` | 2,000 | 4,409 |

두 Kafka lag는 수집 종료 시 0으로 회복했다. 그러나 테스트 중 Order consumer 작업이 크게 늘어 Delivery와 같은 Domain B VM CPU를 경쟁했다.

Zipkin Outbox scheduler 180개 표본은 평균 13.97ms, p95 35.14ms, 최대 44.02ms였다. 이전 180개 표본의 평균 171.78ms와 비교해 배치 UPDATE의 직접 효과가 확인된다.

Delivery WARN·ERROR는 Zipkin reporter의 일시적인 connection reset 경고 2건뿐이었다. `DELIVERY_OUTBOX_PUBLISH_FAILED`, HTTP 5xx와 비즈니스 예외는 없었다. reporter가 330개 span을 드롭했으므로 해당 시점의 tracing 완전성에는 제한이 있지만 기능 결과에는 영향이 없다.

### 10. 제외 측정

02:02:25 KST의 첫 시도는 시작 직후 Hub 502가 2건 발생했고, 기존 Order retry backlog 처리와 겹쳐 Domain B CPU가 100%에 고정됐다. 2분 52초 시점에 중단하고 결과에서 제외했다.

Order retry와 Delivery Kafka lag 0, Domain B CPU 11%, Data VM CPU 2.9%, Hikari active·pending 0을 확인한 뒤 유효 Run을 재실행했다. 유효 Run에서는 HTTP 실패가 발생하지 않았다.

### 11. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보존한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 전체 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panels/` | target별 개별 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | query와 수집 상태 |
| `local-artifacts/raw/delivery-outbox-batch-update-run01-100vu.log` | 유효 k6 전체 출력 |
| `local-artifacts/raw/delivery-outbox-batch-update-run01-recovery.log` | Outbox 직접 회복 기록 |
| `local-artifacts/raw/delivery-outbox-batch-update-invalid-attempt01-100vu.log` | 제외 측정 원본 |
| `local-artifacts/raw/metrics-summary.csv` | 테스트 구간 핵심 Grafana 지표 |
| `local-artifacts/raw/extra-prometheus.csv` | Outbox·Kafka producer·consumer lag 원본 |
| `local-artifacts/raw/loki-run01.json` | Delivery WARN·ERROR |
| `local-artifacts/raw/delivery-outbox-batch-update-run01-zipkin.json` | Outbox scheduler trace |
| `local-artifacts/raw/final-db-stream-kafka-state.log` | 최종 DB·Stream 상태 |
| `local-artifacts/raw/post-recovery-health.log` | 테스트 후 health와 Redis 상태 |

Grafana 수집 범위는 02:12:29부터 02:23:32 KST까지, step은 15초이며 조회 오류 target은 0개다.

### 12. 결론

상태 배치 UPDATE는 scheduler 평균을 `171.78ms → 13.97ms`, 테스트 종료 backlog를 `30,580 → 5`로 줄여 Outbox worker 최적화 목표를 달성했다.

다음 병목은 Outbox DB UPDATE가 아니라 공유 Domain B VM에서 함께 실행되는 downstream consumer다. 후속 실험은 Outbox 코드를 더 복잡하게 만들기보다 Order·Slack consumer를 별도 VM으로 격리하거나 처리량을 제한한 뒤 동일 100VU를 다시 측정하는 것이 적절하다. polling을 250ms 수준으로 완화해 작은 Kafka 요청 수와 공유 CPU 사용량을 줄이는 절충안도 후보로 남긴다.
