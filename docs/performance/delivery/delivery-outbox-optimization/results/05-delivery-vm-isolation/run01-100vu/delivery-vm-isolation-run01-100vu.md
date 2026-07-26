# 배송 서비스 전용 VM 분리 Run 01 - 100VU 결과

### 1. 테스트 목적

배송 서비스와 Order·Slack 등 다른 서비스를 같은 Domain B VM에서 실행하던 구조를 배송 전용 VM으로 분리했을 때, 공유 CPU 경쟁이 줄고 배송 생성 처리량과 지연이 개선되는지 확인한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 테스트 시간 | 2026-07-26 16:24:23 ~ 16:32:26 KST |
| 후속 회복 관찰 종료 | 2026-07-26 16:50:46 KST |
| 대상 API | `POST /internal/deliveries` |
| 실행 명령어 | `ENV_FILE=.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js --quiet` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Outbox | fixed delay 100ms, batch 100건, Kafka 요청 병렬화, 상태 배치 UPDATE |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 변경 전 | Domain B VM에서 Delivery·Order·Slack 등과 함께 실행 |
| 변경 후 | `hublink-delivery-vm`, `e2-standard-2`, Delivery·Promtail만 실행 |
| Delivery 이미지 | `07d64df4ce5ace35bf4e36a8d9df1763e90bbc63` |
| 이미지 digest | `sha256:adce1f4e04e731de3508a37d8561fc8e8c2d7343a035a9810b450585480e792b` |

비교 기준은 같은 이미지와 100VU 조건으로 공유 Domain B VM에서 측정한 [상태 배치 UPDATE Run 01](../../04-batch-status-update/run01-100vu/delivery-outbox-batch-status-update-run01-100vu.md)이다. 애플리케이션 코드는 바꾸지 않고 서비스 배치만 변경했다.

### 3. 판정

**WARN**

- 배송 VM 분리: **PASS** — HTTP TPS 85.1% 증가, 평균 지연 46.0% 감소, p95 59.2% 감소
- 기능 정합성: **PASS** — HTTP 실패 0%, DB 증가량 일치, Outbox 전량 PUBLISHED
- 공유 CPU 제거: **PASS** — 호스트 CPU 평균 92.1%에서 전용 VM 69.5%로 감소
- Data 계층: **WARN** — Data VM CPU 평균 88.2%, 최대 100%, Hikari pending 최대 42
- downstream: **WARN** — Order 전체 Kafka lag 최대 14,768, AI Redis Stream 회복 18분 20초, Slack lag 35,694 잔존

배송 서비스 격리는 foreground 처리량을 명확히 높였다. 다만 늘어난 처리량이 Data VM과 비동기 downstream으로 전달되면서 다음 병목도 함께 드러났다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 / 성공 | 75,911 / 75,911 |
| HTTP TPS | 158.15 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 514.31ms / 525.89ms |
| p90 / p95 / p99 | 794.55ms / 880.54ms / 1.06s |
| 최대 응답 시간 | 1.81s |
| interrupted iteration | 0 |

`p95 < 3초`, `p99 < 6초`, 실패율과 checks threshold를 모두 통과했다.

### 5. 공유 VM 대비 전용 VM 비교

| 지표 | 공유 Domain B VM | 배송 전용 VM | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 41,020 | 75,911 | +85.06% |
| HTTP TPS | 85.45 | 158.15 | +85.07% |
| 평균 응답 시간 | 952.30ms | 514.31ms | -45.99% |
| p95 | 2.16s | 880.54ms | -59.23% |
| p99 | 2.55s | 1.06s | -58.43% |
| 최대 응답 시간 | 4.27s | 1.81s | -57.61% |
| 실패율 | 0.00% | 0.00% | 동일 |

같은 코드와 입력에서 VM 배치만 바꿨으므로, 처리량과 지연 차이는 공유 호스트 CPU 경쟁 제거 효과로 해석할 수 있다.

### 6. Outbox 처리 결과

| 지표 | 공유 Domain B VM | 배송 전용 VM | 변화 |
| --- | ---: | ---: | ---: |
| 테스트 중 Published TPS 평균 | 80.24 | 148.83 | +85.48% |
| publishable backlog 평균 | 214.33 | 110.30 | -48.54% |
| publishable backlog 최대 | 4,681 | 2,062 | -55.95% |
| 테스트 종료 시 backlog | 5 | 19 | +14건 |
| Prometheus 최초 0 | 종료 12초 후 | 종료 12초 후 | 동일 |
| Prometheus 3회 연속 0 | 종료 42초 후 | 종료 42초 후 | 동일 |

전용 VM에서는 요청이 85% 늘었는데도 backlog 최대값은 절반 이하로 줄었다. 종료 시 19건은 다음 scheduler 주기에서 바로 처리됐고, 15초 간격 관찰에서 종료 42초 후 3회 연속 0을 확인했다.

Zipkin의 `task delivery-outbox-service.publish-pending` 812개 표본은 평균 1.84ms, p95 2.24ms, 최대 10.13ms였다. 공유 VM Run의 평균 13.97ms보다 짧았지만 빈 polling 표본이 포함되므로, 직접 효과 판단에는 Published TPS와 backlog를 우선 사용한다.

### 7. DB 및 비동기 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 109,511 | 75,911 |
| `p_delivery_route_histories` | 67,200 | 219,022 | 151,822 |
| `p_delivery_outboxes` | 33,600 | 109,511 | 75,911 |

- 배송과 Outbox 증가량이 k6 성공 요청 75,911건과 일치한다.
- 경로 이력은 배송당 2건으로 정확히 증가했다.
- 최종 Outbox는 PUBLISHED 109,511건, PENDING 0건, FAILED 0건이다.
- Kafka producer error와 retry rate는 0이다.
- Order 계열 Kafka lag 최대값은 14,768이고, 종료 후 첫 확인부터 0이었다.
- AI Redis Stream은 종료 18분 20초 후 pending·lag가 0으로 회복됐다.
- Delivery Redis Stream은 관찰 구간에서 lag 0을 유지했다.
- Slack Redis Stream은 최종 pending 0, lag 35,694로 별도 downstream backlog가 남았다.

AI와 Slack backlog는 배송 요청 실패로 이어지지 않았지만, 처리량 증가에 맞춰 downstream 용량을 함께 조정해야 한다는 경고 신호다.

### 8. 자원 관찰

| 테스트 구간 지표 | 공유 Domain B VM 평균 / 최대 | 배송 전용 VM 평균 / 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 45.89% / 76.20% | 66.26% / 100.00% |
| 호스트 system CPU | 92.13% / 100.00% | 69.49% / 98.20% |
| Data VM CPU | 50.68% / 79.44% | 88.19% / 99.97% |
| Hikari active | 47.18 / 60 | 50.52 / 60 |
| Hikari pending | 12.06 / 38 | 25.33 / 42 |
| PostgreSQL active connection | 9.61 / 48 | 38.88 / 59 |
| PostgreSQL commit TPS | 535.64 / 870.08 | 852.93 / 1,067.86 |
| JVM heap | 248.90 / 393.16MiB | 212.31 / 293.96MiB |
| GC 평균 pause | 53.68 / 98.50ms | 16.37 / 27.09ms |

배송 프로세스 CPU는 더 많은 요청을 처리하면서 증가했지만 전용 호스트 전체 CPU 평균은 낮아졌다. JVM heap과 GC는 병목이 아니며, Data VM CPU 100%와 Hikari pending이 새로운 처리 한계다. 현재 상태에서 Hikari pool을 더 키우는 것은 Data VM 경쟁을 심화할 수 있어 우선순위가 아니다.

### 9. 로그 및 제외 측정

유효 Run의 Delivery WARN·ERROR, assignment lock timeout, HTTP 5xx는 모두 0이다. Zipkin에는 배송 HTTP root span이 수집되지 않아 HTTP 지연은 k6와 Prometheus를 기준으로 판단했다.

16:13:04 KST의 첫 시도는 시작 직후 Hub 경로 502(`DELIVERY_013`)가 연속 발생해 약 2분 시점에 중단하고 결과에서 제외했다. Hub의 CompanyClient 2초 TimeLimiter 경고와 단일 Hub 인스턴스 재시도가 확인됐다. Order lag가 0으로 회복된 뒤 단일 예열 요청 HTTP 201, Outbox 0, Hikari pending 0을 재확인하고 유효 Run을 수행했다.

### 10. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보존한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/raw/delivery-vm-isolation-run01-100vu.log` | 유효 k6 전체 출력 |
| `local-artifacts/raw/metrics-summary.csv` | 테스트 구간 핵심 Grafana 지표 |
| `local-artifacts/raw/recovery-metrics-summary.csv` | Outbox 회복 구간 지표 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 전체 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panels/` | 패널 target별 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | query와 수집 상태 |
| `local-artifacts/grafana/dashboard-snapshot.json` | 대시보드 스냅샷 |

Grafana 수집 범위는 16:23:23부터 16:34:51 KST, step은 15초이며 60개 target의 조회 오류는 0건이다.

### 11. 결론

배송 서비스 전용 VM 분리로 같은 100VU에서 TPS가 `85.45 → 158.15 req/s`, p95가 `2.16s → 880.54ms`로 개선됐다. 공유 Domain B VM CPU 경쟁이 실제 병목이었다는 근거가 충분하다.

다음 우선순위는 Hikari pool 추가 확대가 아니라 Data VM의 PostgreSQL·Redis·Kafka 컨테이너별 CPU 사용량을 분리 측정하는 것이다. 그 결과에 따라 쿼리·쓰기량을 줄이거나 PostgreSQL과 메시징 계층을 별도 VM으로 분리한 뒤 같은 100VU를 재측정하는 것이 적절하다.
