# Delivery Outbox Publishable Index After Run 01 - 100VU 결과

### 1. 테스트 목적

Outbox 발행 대상 부분 인덱스를 적용한 상태에서 100VU 배송 생성 성능과 후속 발행 회복을 측정한다. 동일 조건의 인덱스 적용 전 Run과 비교해 조회 성능 개선이 전체 쓰기 처리량과 backlog 회복에도 효과가 있는지 확인한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-23 15:16:07 ~ 15:24:07 KST |
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
| Outbox 개선 인덱스 | 적용 |

적용한 인덱스는 발행 가능한 행만 `created_at`, `outbox_id` 순서로 보관하는 부분 인덱스다.

```sql
CREATE INDEX CONCURRENTLY idx_delivery_outbox_publishable_created_at
ON delivery_service.p_delivery_outboxes (created_at, outbox_id)
WHERE status IN ('PENDING', 'FAILED')
  AND retry_count < 5;

ANALYZE delivery_service.p_delivery_outboxes;
```

테스트 전 Delivery와 Gateway health는 `UP`, 미발행 Outbox는 0건이었다. Redis는 `PONG`, PostgreSQL 장기 트랜잭션은 없었으며 개선 인덱스의 `indisvalid`, `indisready`가 모두 `true`인 것을 확인했다. 중지되어 있던 Prometheus 컨테이너는 테스트 전에 복구했고 13개 scrape target이 모두 `UP`인 상태에서 유효 Run을 시작했다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 / 성공 | 50,885 / 50,885 |
| HTTP TPS | 106.01 req/s |
| 실패 요청 / 실패율 | 0 / 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 767.66ms / 693.41ms |
| p90 / p95 / p99 | 1.33s / 1.56s / 2.02s |
| 최대 응답 시간 | 3.31s |
| max VU | 100 |
| interrupted iteration | 0 |

모든 threshold를 통과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 1.56s < 3s
p99: 2.02s < 6s
```

### 4. 인덱스 적용 전후 비교

| 지표 | 적용 전 100VU | 적용 후 100VU | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 66,298 | 50,885 | -23.25% |
| HTTP TPS | 138.11 | 106.01 | -23.24% |
| 평균 응답 시간 | 589.06ms | 767.66ms | +30.32% |
| median | 554.47ms | 693.41ms | +25.06% |
| p95 | 1.14s | 1.56s | +36.84% |
| p99 | 1.47s | 2.02s | +37.41% |
| 실패율 | 0.00% | 0.00% | 동일 |
| publishable backlog 최대 | 64,798 | 51,109 | -21.13% |
| 종료 후 회복 시간 | 13분 30초 | 10분 30초 | -22.22% |

backlog와 회복 시간이 줄었지만 생성 요청 자체가 23.25% 감소했다. 종료 후 발행 처리율도 적용 전후 약 80건/s로 같으므로, 줄어든 회복 시간을 인덱스의 처리량 개선으로 해석할 수 없다.

### 5. DB 및 Outbox 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 84,485 | 50,885 |
| `p_delivery_route_histories` | 67,200 | 168,970 | 101,770 |
| `p_delivery_outboxes` | 33,600 | 84,485 | 50,885 |

k6 성공 수와 배송·Outbox 증가량이 일치하고 경로 이력은 요청당 2건 증가했다. 회복 완료 후 Outbox 84,485건은 모두 `PUBLISHED`이며 발행 가능한 `PENDING`, `FAILED`는 0건이다. 인덱스는 최종 확인 시에도 valid·ready 상태였다.

### 6. Outbox 처리량과 회복

| 지표 | 적용 전 | 적용 후 |
| --- | ---: | ---: |
| 테스트 중 Published TPS 평균 / 최대 | 3.39 / 10.33건/s | 2.56 / 8.33건/s |
| 종료 후 Published TPS 평균 / 최대 | 79.71 / 83.33건/s | 79.39 / 86.67건/s |
| publishable backlog 최대 | 64,798건 | 51,109건 |
| 종료 후 회복 시간 | 약 13분 30초 | 약 10분 30초 |

적용 후 테스트 종료 시 Grafana backlog는 51,109건이었다. 직접 DB 조회에서 최초 0을 확인한 시각은 15:34:37 KST이며 10초 간격으로 두 차례 더 0을 확인했다.

종료 후 발행 속도는 적용 전 79.71건/s, 적용 후 79.39건/s로 사실상 동일하다. 발행 대상 조회가 빨라져도 현재 publisher의 순차 Kafka 전송과 행별 상태 갱신이 남아 있어 전체 회복 처리량은 증가하지 않았다.

### 7. EXPLAIN ANALYZE 비교

발행 가능 Outbox가 없는 상태에서 조회 계획은 다음과 같이 변경됐다.

| 항목 | 적용 전 | 적용 후 |
| --- | ---: | ---: |
| 실행 방식 | `Seq Scan -> Sort -> Limit` | `Index Scan -> Limit` |
| 불필요하게 필터링한 행 | 113,541건 | 0건 |
| shared buffer | 8,313 blocks | 1 block |
| Execution Time | 37.432ms | 0.020ms |

실제 발행 가능 Outbox 1,000건을 만든 통제 실험에서도 같은 경향을 확인했다.

| 항목 | 인덱스 미사용 | 인덱스 사용 |
| --- | ---: | ---: |
| 반환 행 | 100건 | 100건 |
| 실행 방식 | `Seq Scan -> top-N Sort` | `Index Scan` |
| Rows Removed by Filter | 98,898건 | 0건 |
| shared buffer | 7,120 blocks | 35 blocks |
| Execution Time | 38.928ms | 0.075ms |

조회 실행 시간은 약 99.8%, 접근 buffer는 약 99.5% 감소했다. 다만 두 결과는 물리 read 유무가 다른 시점의 단일 측정이므로 정확한 519배 개선으로 일반화하지 않고 실행계획 전환과 불필요한 전체 검사 제거를 핵심 근거로 사용한다.

### 8. 쓰기 경로 영향

부분 인덱스는 조회 대상만 작게 유지하지만 새 Outbox의 `PENDING` 삽입과 `PUBLISHED` 상태 변경 때 인덱스 항목도 추가하거나 제거해야 한다. 따라서 읽기 비용을 줄이는 대신 쓰기 비용이 추가된다.

애플리케이션 구간 계측에서 Outbox `insert_on_conflict` 평균은 적용 전 14.21ms에서 적용 후 28.45ms로 100.2% 증가했다. 같은 시점에 HTTP TPS도 23.24% 감소했다. 단일 Run이며 테스트 시점의 시스템 상태 차이가 있으므로 처리량 감소 전부를 인덱스 때문이라고 단정할 수는 없지만, 조회 개선만으로 전체 성능 개선이 보장되지 않는다는 근거다.

### 9. Grafana 관찰 결과

테스트 구간 기준이다.

| 지표 | 적용 전 평균 / 최대 | 적용 후 평균 / 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 41.19% / 55.22% | 44.59% / 66.98% |
| Domain B system CPU | 85.95% / 99.90% | 93.40% / 99.97% |
| Data VM CPU | 71.12% / 93.58% | 54.11% / 76.63% |
| Hikari active | 47.18 / 60 | 50.24 / 60 |
| Hikari pending | 20.00 / 39 | 13.33 / 38 |
| PostgreSQL active connection | 14.45 / 52 | 8.21 / 45 |
| PostgreSQL commit TPS | 673.24 / 848.60 | 563.28 / 750.74 |
| JVM heap 최대 | 599.79MiB | 338.92MiB |
| GC 평균 pause 최대 | 85.7ms | 84.3ms |

적용 후 Data VM CPU와 PostgreSQL TPS가 낮아졌지만 이는 요청 처리량 감소의 영향도 함께 받는다. Delivery process CPU와 Domain B system CPU는 더 높았으므로 DB 읽기 부하 감소가 애플리케이션 전체 처리량 증가로 이어지지는 않았다. Hikari timeout, HTTP 5xx, Delivery WARN·ERROR, 배정 lock timeout은 0이었다.

### 10. Zipkin

유효 Run 구간의 최근 500개 표본 중 배송 root trace는 497개였다.

| span | 표본 수 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery root | 497 | 114.12ms | 202.45ms | 284.85ms |
| `HubClient` | 497 | 4.93ms | 13.56ms | 31.46ms |
| `UserClient` | 497 | 8.54ms | 22.10ms | 60.70ms |
| Hub server | 497 | 0.93ms | 1.23ms | 2.58ms |
| User server | 497 | 3.08ms | 4.14ms | 6.96ms |

k6 평균 767.66ms보다 서버 root span 평균이 짧아 애플리케이션 진입 전 대기와 동시성 경합이 포함된 것으로 해석한다. 하위 HTTP 서버 자체의 지연은 주 병목으로 보기 어렵다.

### 11. 제외한 첫 시도

15:00:45 KST에 시작한 첫 시도는 20,390건 중 1,932건이 HTTP 502로 실패해 비교 결과에서 제외했다. Hub 호출의 일시 실패로 `hub-service` Circuit Breaker가 열렸고 not-permitted 호출이 1,956건 발생했다. 동시에 Prometheus가 이전 SIGTERM 이후 중지된 상태여서 관측 구간도 불완전했다.

Prometheus를 재기동해 13개 target의 `UP`을 확인하고, Hub 응답과 Circuit Breaker `CLOSED`, Outbox backlog 0을 확인한 뒤 두 번째 시도를 수행했다. 두 번째 시도에서는 기존 502와 not-permitted 누적값이 증가하지 않았다. 제외한 시도의 원본 로그는 재현 근거로만 보존한다.

### 12. Grafana 전체 CSV 패키지

CSV 수집 범위는 테스트 시작 1분 전인 15:15:07부터 Outbox 안정 회복 이후인 15:37:01 KST까지이며 step은 15초다.

| 파일 | 설명 |
| --- | --- |
| [dashboard-all-panels.csv](./local-artifacts/grafana/dashboard-all-panels.csv) | 35개 패널·60개 target의 7,744개 샘플 통합본 |
| [panel-manifest.csv](./local-artifacts/grafana/panel-manifest.csv) | 패널 ID, 제목, query, CSV 경로, series·sample 수, 오류 여부 |
| [metadata.csv](./local-artifacts/grafana/metadata.csv) | 대시보드 버전과 수집 시작·종료 시각, step |
| [dashboard-snapshot.json](./local-artifacts/grafana/dashboard-snapshot.json) | 수집 당시 Grafana 대시보드 정의 |
| [`local-artifacts/grafana/panels`](./local-artifacts/grafana/panels/) | target별 개별 CSV 60개 |
| [유효 k6 원본 로그](./local-artifacts/raw/k6-outbox-index-after-run01-100vu.log) | 실패율 0%인 두 번째 실행 전체 로그 |
| [Outbox 회복 원본](./local-artifacts/raw/outbox-recovery-10s.log) | 10초 간격 publishable backlog |
| [제외 시도 원본 로그](./local-artifacts/raw/k6-invalid-attempt01-hub-circuit-breaker.log) | Hub Circuit Breaker로 제외한 첫 실행 |

빈 CSV 5개는 수집 오류가 아니라 409, 배정 lock timeout, Delivery WARN·ERROR가 없었던 정상 결과다. 모든 target의 조회 상태는 manifest에서 `OK`로 확인했다.

### 13. 결론

```text
WARN - 조회 쿼리는 개선됐지만 전체 쓰기 처리량 개선은 확인하지 못함

- 50,885건 전부 성공, 실패율 0%, 모든 threshold 통과
- Seq Scan과 Sort가 Index Scan으로 전환
- 1,000건 backlog 실험에서 실행 시간 38.928ms -> 0.075ms
- HTTP TPS 138.11 -> 106.01 req/s, 23.24% 감소
- Outbox insert 구간 평균 14.21ms -> 28.45ms
- 종료 후 발행 처리율은 약 80건/s로 변화 없음
- 짧아진 회복 시간은 생성 요청과 backlog 감소 영향
```

부분 인덱스는 발행 대상 조회 최적화 근거로는 충분하지만 현재 형태를 전체 성능 개선으로 확정하기 어렵다. 다음 단계는 publisher의 순차 `KafkaTemplate.send().get()`과 행별 상태 갱신을 개선한 뒤, 같은 100VU에서 Published TPS와 backlog 회복 속도가 실제로 증가하는지 비교하는 것이다.
