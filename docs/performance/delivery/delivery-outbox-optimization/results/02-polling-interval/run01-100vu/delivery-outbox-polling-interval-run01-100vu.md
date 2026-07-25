# Delivery Outbox Polling Interval Run 01 - 100VU 결과

### 1. 테스트 목적

Outbox 발행 대상 부분 인덱스를 유지한 상태에서 polling fixed delay를 `1,000ms`에서 `100ms`로 줄였을 때 backlog 처리량과 배송 생성 성능의 변화를 확인한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-24 14:37:25 ~ 14:45:28 KST |
| 대상 API | `POST /internal/deliveries` |
| 실행 명령어 | `ENV_FILE=.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | 0초 |
| Outbox fixed delay | `100ms` |
| batch / 발행 / UPDATE | 100건 / 순차 Kafka 발행 / 행별 UPDATE |
| Outbox 부분 인덱스 | 적용 |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |

테스트 전 서비스와 관측 target은 모두 `UP`, publishable Outbox와 Redis Stream lag는 0이었다. 배포 컨테이너에서 `DELIVERY_OUTBOX_FIXED_DELAY_MS=100` 적용도 확인했다.

### 3. 판정

**WARN**

- 기능: **PASS** — HTTP 실패 0%, Outbox 전량 발행, DB 정합성 일치, 최종 Redis Stream lag 0
- Outbox: **개선** — 안정 회복 `10분 30초 → 3분 14초`, 회복 Published TPS `79.39 → 183.23건/s`
- 배송 생성: **회귀** — HTTP TPS `106.01 → 74.02 req/s`, p95 `1.56 → 2.20초`

100ms polling은 backlog 회복 목표에는 효과가 컸지만 같은 프로세스와 DB 자원을 사용하는 배송 생성 처리량을 약 30% 낮췄다. 현재 값은 최종 채택보다 trade-off 확인용 결과로 보는 것이 적절하다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 / 성공 | 35,528 / 35,528 |
| HTTP TPS | 74.02 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 1.09s / 1.01s |
| p90 / p95 / p99 | 1.86s / 2.20s / 2.81s |
| 최대 응답 시간 | 4.25s |
| interrupted iteration | 0 |

모든 k6 threshold를 통과했다.

### 5. fixed delay 1,000ms 대비

비교 기준은 같은 100VU, Hikari 60, 담당자 한도 60, Outbox 부분 인덱스 조건의 [인덱스 적용 후 Run](../../01-publishable-index/after-run01-100vu/delivery-outbox-publishable-index-after-run01-100vu.md)이다.

| 지표 | 1,000ms | 100ms | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 50,885 | 35,528 | -30.18% |
| HTTP TPS | 106.01 | 74.02 | -30.18% |
| 평균 응답 시간 | 767.66ms | 1.09s | +42.0% |
| p95 | 1.56s | 2.20s | +41.0% |
| p99 | 2.02s | 2.81s | +39.1% |
| 테스트 중 Published TPS 평균 / 최대 | 2.56 / 8.33 | 16.56 / 46.43 | 평균 6.47배 |
| 종료 후 Published TPS 평균 / 최대 | 79.39 / 86.67 | 183.23 / 236.10 | 평균 2.31배 |
| publishable backlog 최대 | 51,109 | 34,888 | -31.74% |
| backlog 안정 0 | 10분 30초 | 3분 14초 | 69.21% 단축 |

backlog 최대값 감소에는 Outbox 처리 개선뿐 아니라 배송 성공 건수가 15,357건 줄어든 영향도 포함된다. 반면 종료 후 Published TPS와 회복 시간은 새 입력이 중단된 구간이므로 polling 단축 효과를 비교하기에 더 직접적이다.

### 6. Outbox 회복

| 시점 | publishable backlog |
| --- | ---: |
| 테스트 종료 직전 최대 | 34,888 |
| 종료 10초 후 | 32,311 |
| 종료 약 1분 후 | 21,917 |
| 종료 약 2분 후 | 7,973 |
| 종료 2분 53초 후 | 0 |
| 종료 3분 14초 후 | 3회 연속 0 |

발행 대상 조회는 테스트 중 평균 `0.89회/s`, 회복 구간 평균 `2.26회/s`였다. 조회 평균 지연은 각각 `22.57ms`, `3.63ms`였다.

`fixedDelay`는 발행 작업 완료 뒤 100ms를 기다리는 방식이다. 따라서 batch 발행과 상태 UPDATE가 오래 걸리는 동안에는 polling이 중첩되지 않으며 실제 조회 빈도도 항상 초당 10회가 되지는 않는다.

Zipkin의 Outbox scheduler 표본 12개는 평균 `695.54ms`, p95와 최대 `1,117.94ms`였다. 현재 회복 구간은 대기 시간보다 순차 Kafka ACK와 행별 UPDATE 실행 시간이 더 큰 비중을 차지한다.

### 7. DB 및 비동기 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 69,128 | 35,528 |
| `p_delivery_route_histories` | 67,200 | 138,256 | 71,056 |
| `p_delivery_outboxes` | 33,600 | 69,128 | 35,528 |

- k6 성공 수와 배송·Outbox 증가량이 일치했다.
- 최종 Outbox 69,128건은 모두 `PUBLISHED`이며 publishable 행은 0건이다.
- PostgreSQL 장기 트랜잭션과 rollback TPS는 0이었다.
- Redis `deadline:requested:stream`의 AI 그룹과 `deadline:generated:stream`의 Delivery·Slack 그룹은 최종 pending 0, lag 0이었다.
- 종료 후 Delivery health는 `UP`, Prometheus down target은 0개였다.

### 8. 자원 관찰

| 테스트 구간 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 48.45% | 65.34% |
| Domain B system CPU | 94.17% | 99.92% |
| Data VM CPU | 51.12% | 76.78% |
| Hikari active | 48.52 | 60 |
| Hikari pending | 10.24 | 41 |
| PostgreSQL active connection | 3.42 | 18 |
| PostgreSQL commit TPS | 422.70 | 576.90 |
| JVM heap | 312.64MiB | 440.35MiB |
| GC 평균 pause | 49.60ms | 120.00ms |

Hikari timeout, HTTP 5xx, Delivery·Hub WARN/ERROR는 0이었다. Data VM CPU는 포화되지 않았지만 Domain B system CPU가 계속 94%대이고 최대 100%에 근접했다. Outbox publisher가 더 자주 실행되며 배송 요청과 같은 Delivery 프로세스의 CPU·connection·트랜잭션 자원을 경쟁한 것이 foreground 처리량 하락의 주된 정황이다.

### 9. Zipkin

유효 구간 최근 500개 trace 중 배송 root 표본은 487개였다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery root | 487 | 110.59ms | 200.02ms | 379.08ms |
| Delivery HTTP client 합계 | 974 | 6.86ms | 17.40ms | 59.16ms |
| Hub server | 487 | 1.25ms | 1.61ms | 2.49ms |
| User server | 487 | 3.77ms | 5.58ms | 9.84ms |

Hub와 User 서버 지연은 낮아 이번 Run의 처리량 하락을 downstream API 병목으로 보기는 어렵다.

### 10. 제외한 첫 시도

14:31:59 KST의 첫 시도는 램프업 중 Hub 호출 지연으로 Circuit Breaker가 열려 중단했다. 진단 시 Hub 회로의 누적 failed는 7건, not-permitted는 2,288건이었다.

Hub health와 직접 경로 호출 5/5 성공, 회로 `CLOSED`, 기존 Outbox 회복을 확인하고 쿨다운 후 재측정했다. 유효 Run에서는 failed와 not-permitted 누적값이 증가하지 않았고 `DELIVERY_013`도 0건이었다. 첫 시도는 비교 결과에서 제외하고 원본만 보존한다.

### 11. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보존한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/grafana/dashboard-all-panels.csv` | 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panels/` | target별 개별 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | 쿼리와 수집 상태 |
| `local-artifacts/raw/delivery-outbox-polling100-run01-100vu.log` | 유효 k6 전체 로그 |
| `local-artifacts/raw/delivery-outbox-polling100-run01-recovery.log` | 10초 간격 backlog |
| `local-artifacts/raw/delivery-outbox-polling100-run01-outbox-query.csv` | Outbox 조회 지연·호출 빈도 |
| `local-artifacts/raw/delivery-outbox-polling100-run01-zipkin.json` | Zipkin 500 trace |
| `local-artifacts/raw/delivery-outbox-polling100-invalid-attempt01-100vu.log` | 제외 시도 로그 |

CSV 수집 범위는 14:36:25부터 14:49:42 KST까지, step은 15초이며 오류 target은 0개다.

### 12. 결론

polling 100ms는 Outbox 안정 회복을 `630초 → 194초`로 줄였지만 배송 TPS도 `106.01 → 74.02 req/s`로 낮췄다. 다음 단계는 100ms를 그대로 확정하기보다 `250~500ms` 절충값을 검토하거나, 계획한 Kafka 발행 병렬화로 한 번의 polling 작업 시간을 줄인 뒤 다시 측정하는 것이다.
