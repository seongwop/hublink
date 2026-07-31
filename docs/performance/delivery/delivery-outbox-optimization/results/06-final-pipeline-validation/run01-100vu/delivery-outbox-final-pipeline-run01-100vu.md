# Delivery Outbox Final Pipeline Run 01 - 100VU 결과

### 1. 테스트 목적

Outbox 부분 인덱스, polling 100ms, Kafka 병렬 발행, 상태 배치 UPDATE가 모두 적용된 최종 이미지에서 100VU 처리량과 backlog 회복을 검증한다. 포트폴리오 비교 기준은 다음 두 누적 상태다.

| 구분 | 이미지 | Outbox 상태 |
| --- | --- | --- |
| 개선 전 | `e0149eeffd3931ce4f2b843986950c9853716617` | 인덱스 없음, polling 1000ms, 순차 발행, 단건 UPDATE |
| 최종 개선 후 | `bfb1d501f98cc690086bce3b2a317181d00a648c` | 부분 인덱스, polling 100ms, 병렬 발행, 상태 배치 UPDATE |

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 Cloud Run 실행 | `hublink-k6-load-test-2j7pn` |
| 테스트 시간 | 2026-07-30 17:15:28 ~ 17:23:28 KST |
| 대상 API | `POST /internal/deliveries` |
| VU / duration | 최대 100VU / 8분 |
| 부하 패턴 | 1분 상승, 5분 유지, 2분 하강 |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | `SLEEP_SECONDS=0` |
| 초기화 SQL | `db/seed/14-reset-delivery-perf-baseline.sql` |
| Delivery Hikari | 최대 60 |
| 담당자 배정 한도 | 60건 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |

같은 warm 상태를 만들기 위해 10VU 30초 실행 `hublink-k6-load-test-6w77z`와 5VU 20초 실행 `hublink-k6-load-test-mhtnb`를 차례로 완료했다. 각 단계 사이에는 DB 자동 초기화와 Redis Stream의 완료 데이터 정리를 수행했고, 본 실행 직전 Redis pending·lag와 Kafka consumer lag는 모두 0이었다.

첫 본 실행 `hublink-k6-load-test-cr5q7`도 84,223건 전부 성공, 175.46 req/s, p95 798.26ms로 안정적이었다. 다만 새 프로젝트의 `postgres-exporter`가 `localhost:5432`를 보고 있어 Outbox backlog 시계열이 누락됐으므로 최종 비교에서는 제외했다. exporter를 `10.10.0.40:5432`로 복구한 뒤 동일 워밍업과 부하를 반복한 `2j7pn`만 유효 결과로 채택했다.

### 3. k6 결과

| 지표 | 값 |
| --- | ---: |
| 총 요청 / 성공 | 82,648 / 82,648 |
| HTTP TPS | 172.18 req/s |
| 실패 요청 / 실패율 | 0 / 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 472.52ms / 489.55ms |
| p90 / p95 / p99 | 732.12ms / 805.87ms / 967.18ms |
| 최대 응답시간 | 1.50s |
| interrupted iteration | 0 |

모든 threshold를 통과했다.

### 4. 개선 전후 비교

| 지표 | 개선 전 `e0149` | 최종 `bfb1` | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 66,298 | 82,648 | +24.7% |
| HTTP TPS | 138.11 | 172.18 | +24.7% |
| 평균 응답시간 | 589.06ms | 472.52ms | -19.8% |
| p95 | 1.14s | 805.87ms | -29.3% |
| p99 | 1.47s | 967.18ms | -34.2% |
| Outbox Published TPS 평균 | 3.39 | 166.07 | +4,798.8% |
| Outbox Published TPS 최대 | 10.33 | 188.90 | +1,728.7% |
| publishable backlog 최대 | 64,798 | 4,259 | -93.4% |
| 종료 후 backlog 회복 | 약 13분 30초 | 약 15초 | -98.1% |

개선 전에는 동기 부하 중 Outbox 발행이 평균 3.39건/s로 제한돼 backlog가 64,798건까지 누적됐다. 최종 상태에서는 평균 발행 처리량이 동기 배송 생성량과 비슷한 166.07건/s까지 올라 backlog가 부하 중 대부분 100건 이하로 유지됐다. 초기 최고 4,259건도 빠르게 해소됐고 종료 시점 1건은 다음 15초 scrape에서 0건이 됐다.

이 비교에는 Outbox 코드뿐 아니라 개선 전 공유 Domain B VM에서 최종 배송 전용 VM으로 바뀐 인프라 차이도 포함된다. 따라서 표의 수치는 최종 누적 개선 효과로 사용하고, 부분 인덱스·polling·병렬 발행·배치 UPDATE 각각의 인과는 단계별 Run 문서를 근거로 분리한다.

### 5. DB 및 Outbox 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 116,248 | 82,648 |
| `p_delivery_route_histories` | 67,200 | 232,496 | 165,296 |
| `p_delivery_outboxes` | 33,600 | 116,248 | 82,648 |

k6 성공 수와 배송·Outbox 증가량이 일치하고 경로 이력은 요청당 2건 증가했다. 사후 조회에서 Outbox는 전부 `PUBLISHED`였으며 부분 인덱스 `idx_delivery_outbox_publishable_created_at`도 존재했다.

| Outbox retry count | PUBLISHED |
| --- | ---: |
| 0 | 111,208 |
| 1 | 1,680 |
| 2 | 1,680 |
| 3 | 1,680 |

retry count 1~3의 5,040건은 초기화 SQL에 포함된 기존 baseline이고, 이번 실행에서 새로 생성된 82,648건은 모두 정상 발행됐다.

### 6. Grafana 관찰 결과

| 지표 | 평균 / 최대 |
| --- | ---: |
| Delivery process CPU | 50.00% / 60.80% |
| Delivery system CPU | 49.10% / 57.90% |
| Data VM CPU | 93.91% / 99.97% |
| Hikari active | 51.39 / 60 |
| Hikari pending | 27.46 / 42 |
| JVM heap | 평균 366.28MiB / 최대 551.74MiB |
| GC pause 최대 | 35ms |

JVM heap과 GC는 병목 징후가 없었다. 반면 Data VM CPU는 최대 99.97%에 도달했고 Hikari pending도 최대 42까지 상승했다. 최종 파이프라인이 Outbox 발행을 배송 생성과 거의 같은 속도로 처리하면서 DB·Kafka·Redis 공유 VM을 적극적으로 사용한 결과이며, 다음 확장 한계는 애플리케이션 JVM보다 Data VM 자원이다.

Loki 수집 구간에서 Delivery WARN, ERROR, HTTP 5xx, Hikari timeout과 배정 lock timeout은 모두 0건이었다.

### 7. Zipkin 및 비동기 후속 처리

Zipkin의 `task delivery-outbox-service.publish-pending` 14개 표본은 평균 4.46ms, p95 5.63ms, 최대 6.51ms였다. 표본 수가 적으므로 scheduler 절대 성능 판단에는 Published TPS와 backlog 시계열을 우선 사용한다.

본 실행 직후 Outbox와 Kafka consumer group lag는 모두 0이었지만 Redis 후속 소비자는 아직 회복 중이었다.

| 소비자 | pending | lag |
| --- | ---: | ---: |
| Delivery | 0 | 0 |
| AI | 57 | 59,006 |
| Slack | 0 | 9,604 |

따라서 Outbox 발행 파이프라인은 부하를 따라잡았지만 배송 이후 AI·Slack end-to-end 처리가 같은 시점에 모두 회복된 것은 아니다.

### 8. 저장 자료

| 자료 | 위치 |
| --- | --- |
| k6 원본 로그 | `local-artifacts/raw/cloud-run-k6-2j7pn.log` |
| Grafana 전체 통합 CSV | `local-artifacts/grafana/dashboard-all-panels.csv` |
| 패널별 CSV 60개 | `local-artifacts/grafana/panels/` |
| 패널 query·수집 상태 | `local-artifacts/grafana/panel-manifest.csv` |
| Zipkin Outbox trace | `local-artifacts/raw/final-outbox-traces.json` |
| 전후 비교 패널 CSV | `comparison-dashboard/local-artifacts/e0149-vs-bfb1/` |
| 포트폴리오 대시보드 정의 | `monitoring/grafana/provisioning/dashboards/portfolio/04-outbox-pipeline-comparison.json` |

Grafana 접속 후 대시보드 경로:

```text
http://localhost:3000/d/pf-outbox-pipeline/outbox-pipeline-before-vs-after
```

대시보드는 `e0149` 개선 전과 `bfb1` 최종 개선 후 두 시계열만 표시하며 배송 RPS·응답시간, Outbox Published TPS·backlog, Data VM CPU와 Hikari pending을 같은 경과시간 축으로 비교한다.

### 9. 결론

```text
PASS with capacity warning

- 82,648건 전부 성공, 실패율 0%, 172.18 req/s
- 평균 472.52ms, p95 805.87ms, p99 967.18ms
- Outbox 발행 평균 3.39 -> 166.07건/s
- backlog 최대 64,798 -> 4,259건, 회복 13분 30초 -> 15초
- DB 증가량과 k6 성공 수 일치, 최종 미발행 Outbox 0
- JVM 병목은 없지만 Data VM CPU 최대 99.97%로 다음 용량 한계 확인
```
