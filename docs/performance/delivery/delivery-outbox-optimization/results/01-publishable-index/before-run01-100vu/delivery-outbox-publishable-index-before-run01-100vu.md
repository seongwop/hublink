# Delivery Outbox Publishable Index Before Run 01 - 100VU 결과

### 1. 테스트 목적

Outbox 발행 대상 부분 인덱스를 적용하기 전 상태에서 100VU 부하와 후속 발행 회복을 측정한다. 같은 조건의 인덱스 적용 후 Run과 비교할 수 있도록 Grafana 대시보드 전체 패널 데이터를 CSV로 보존한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-23 00:24:42 ~ 00:32:42 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | `SLEEP_SECONDS=0` |
| DB pool | Delivery Hikari 최대 60 |
| 담당자 배정 한도 | 60건 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| 초기화 SQL | `db/seed/14-reset-delivery-perf-baseline.sql` |
| Outbox 개선 인덱스 | 미적용 |

테스트 전 Delivery health는 `UP`, 미발행 Outbox와 Redis 세 소비자 그룹의 pending·lag는 모두 0이었다. 기존 Redis Stream 길이는 이전 처리 이력이지만 소비자 lag가 0이므로 테스트 시작 조건에 영향을 주지 않는다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 / 성공 | 66,298 / 66,298 |
| HTTP TPS | 138.11 req/s |
| 실패 요청 / 실패율 | 0 / 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 589.06ms / 554.47ms |
| p90 / p95 / p99 | 984.05ms / 1.14s / 1.47s |
| 최대 응답 시간 | 2.84s |
| max VU | 100 |
| interrupted iteration | 0 |

모든 threshold를 통과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 1.14s < 3s
p99: 1.47s < 6s
```

### 4. DB 및 Outbox 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 99,898 | 66,298 |
| `p_delivery_route_histories` | 67,200 | 199,796 | 132,596 |
| `p_delivery_outboxes` | 33,600 | 99,898 | 66,298 |

k6 성공 수와 배송·Outbox 증가량이 일치하고 경로 이력은 요청당 2건 증가했다. 회복 완료 후 Outbox 99,898건은 모두 `PUBLISHED`이며 `PENDING`, `FAILED`, failed topic, DLQ 증가는 0건이다.

개선 인덱스는 테스트 전과 회복 완료 후 모두 `NOT PRESENT`를 확인했다.

### 5. Outbox 처리량과 회복

| 지표 | 값 |
| --- | ---: |
| publishable backlog 최대 | 64,798건 |
| 테스트 중 Published TPS 평균 / 최대 | 3.39 / 10.33건/s |
| 테스트 종료 후 Published TPS 평균 / 최대 | 79.71 / 83.33건/s |
| 종료 직후 확인 PENDING | 60,898건 |
| backlog 최초 0 | 00:46:12 KST |
| 종료 후 회복 시간 | 약 13분 30초 |
| 안정 0 재확인 | 최초 0 확인 후 약 35초 |

동기 배송 생성 부하 중에는 Outbox publisher가 평균 3.39건/s로 제한되면서 backlog가 64,798건까지 증가했다. 동기 부하가 끝난 뒤 발행 속도는 약 80건/s로 회복했지만 backlog를 모두 해소하는 데 약 13분 30초가 걸렸다.

이 수치는 부분 인덱스 적용 후 비교할 핵심 기준이다. 개선 후에는 같은 입력량에서 테스트 중 Published TPS, peak backlog, 종료 후 회복 시간을 함께 비교해야 한다.

### 6. Grafana 관찰 결과

테스트 구간 기준이다.

| 지표 | 평균 / 최대 |
| --- | ---: |
| Delivery process CPU | 41.19% / 55.22% |
| Domain B system CPU | 85.95% / 99.90% |
| Data VM CPU | 71.12% / 93.58% |
| Hikari active | 47.18 / 60 |
| Hikari pending | 20.00 / 39 |
| PostgreSQL active connection | 14.45 / 52 |
| PostgreSQL commit TPS | 673.24 / 848.60 |
| JVM heap 최대 | 599.79MiB |
| GC 평균 pause 최대 | 85.7ms |

Data VM은 이전 100VU 대표 Run처럼 CPU 100%에 도달하지 않았지만 Domain B system CPU는 최대 99.90%까지 상승했다. Hikari timeout과 HTTP 5xx는 0이었고 JVM heap과 GC도 직접 병목으로 볼 근거는 없다.

### 7. 로그 및 Zipkin

Grafana/Loki 전체 수집 구간에서 다음 항목은 모두 0건이었다.

- Delivery WARN / ERROR
- 배정 lock timeout
- HTTP 5xx
- Hikari timeout
- duplicate skip

Zipkin에는 테스트 후반 표본으로 배송 요청 root trace 130개와 Outbox scheduler trace 1개가 남았다. 배송 요청 root 평균은 46.31ms, 최대는 135.23ms였고 client span은 `UserClient` 평균 4.70ms·최대 13.24ms, `HubClient` 평균 2.13ms·최대 6.38ms였다. 표본상 외부 HTTP보다 DB 경쟁과 Outbox 발행 속도를 우선 비교하는 것이 적절하다.

### 8. EXPLAIN ANALYZE 개선 전 근거

부하 테스트 직전 별도 실행한 조회 계획은 다음 특징을 보였다.

| 항목 | 값 |
| --- | ---: |
| 실행 방식 | `Seq Scan -> Sort -> Limit` |
| Rows Removed by Filter | 113,541건 |
| shared buffer hit | 8,313 blocks |
| Execution Time | 37.432ms / 38.998ms |

발행 대상이 0건이어도 전체 Outbox를 검사했다. 이 실행계획은 부하 테스트 초기화 전 113,541건 상태에서 측정했으므로 이번 Run의 최종 99,898건과 행 수는 다르지만, 인덱스 미적용 조회 구조를 설명하는 근거로 사용한다.

### 9. Grafana 전체 CSV 패키지

CSV 수집 범위는 테스트 시작 1분 전인 00:23:42부터 Outbox 안정 회복 이후인 00:48:11 KST까지이며 step은 15초다.

| 파일 | 설명 |
| --- | --- |
| [dashboard-all-panels.csv](./local-artifacts/grafana/dashboard-all-panels.csv) | 35개 패널·60개 target의 8,624개 샘플 통합본 |
| [panel-manifest.csv](./local-artifacts/grafana/panel-manifest.csv) | 패널 ID, 제목, query, CSV 경로, series·sample 수, 오류 여부 |
| [metadata.csv](./local-artifacts/grafana/metadata.csv) | 대시보드 버전과 수집 시작·종료 시각, step |
| [dashboard-snapshot.json](./local-artifacts/grafana/dashboard-snapshot.json) | 수집 당시 Grafana 대시보드 정의 |
| [`local-artifacts/grafana/panels`](./local-artifacts/grafana/panels/) | target별 개별 CSV 60개 |
| [k6 원본 로그](./local-artifacts/raw/k6-outbox-index-before-run01-100vu.log) | 원격 k6 실행 전체 로그 |

빈 CSV 5개는 수집 오류가 아니라 409, 배정 lock timeout, Delivery WARN·ERROR가 없었던 정상 결과다. 모든 target의 조회 상태는 manifest에서 `OK`로 확인했다.

### 10. 결론

```text
WARN - 요청은 전부 성공했지만 Outbox 발행 backlog와 긴 회복 시간 확인

- 66,298건 전부 성공, 실패율 0%, 138.11 req/s
- 평균 589.06ms, p95 1.14s, p99 1.47s
- DB·Outbox 증가량과 k6 성공 수 일치, 최종 미발행·failed·DLQ 0
- publishable backlog 최대 64,798건
- 테스트 중 발행 평균 3.39건/s, 종료 후 평균 79.71건/s
- Outbox 종료 후 약 13분 30초에 회복
- 개선 전 Seq Scan에서 발행 대상 0건이어도 113,541건 필터 검사
```

다음 Run은 같은 100VU·8분·초기화·서비스 warm 상태에서 부분 인덱스와 실제 애플리케이션 쿼리 사용 여부를 확인한 뒤 반복한다. 비교 시 HTTP TPS만 보지 않고 Outbox Published TPS, peak backlog, 회복 시간, DB CPU, PostgreSQL TPS를 우선 사용한다.
