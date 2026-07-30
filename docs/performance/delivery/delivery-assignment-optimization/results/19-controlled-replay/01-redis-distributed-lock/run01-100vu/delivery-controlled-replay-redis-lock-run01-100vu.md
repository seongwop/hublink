# 배송 배정 통제 재현 01 - 최초 Redis 분산락 100VU 결과

### 1. 테스트 목적

배송 배정 최적화 과정을 동일한 현재 인프라와 테스트 데이터로 다시 측정하기 위한 첫 기준선을 만든다. 최초 Redis 분산락 구현 이미지를 배포하고 100VU에서 처리량, 락 충돌, DB 정합성, JVM·DB·Outbox 상태를 함께 기록했다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 테스트 시간 | 2026-07-27 11:15:07 ~ 11:23:08 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `6f067af2be7c25a63007afb9c85a7a577d15ba5a` |
| 배정 방식 | 회사·허브별 Redis 분산락 안에서 담당자 조회와 배송 저장 수행 |
| 실제 lock wait | **3초** |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| 배정 집계 행 | HUB 1,500명, COMPANY 1,800명 |
| Delivery VM | 전용 `hublink-delivery-vm`, `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| Outbox | polling 1,000ms, publishable 부분 인덱스 없음 |

이 이미지의 `DeliveryAssignmentLockService`는 `WAIT_SECOND = 3L`을 상수로 사용한다. 따라서 Config Server의 `delivery.assignment.lock-wait=2s`는 이 바이너리에 적용되지 않는다. 이번 결과는 당초 예상했던 “최초 구현 + 2초”가 아니라 **순수 최초 구현의 3초 대기 기준선**이다.

현재 표준 초기화 SQL의 JSON Outbox payload는 구버전의 `@Lob` 매핑과 호환되지 않는다. 모든 재현 단계에서 같은 데이터를 사용할 수 있도록 표준 seed를 실행하되, 기존 PENDING·FAILED Outbox 8,400건은 외부에 노출되기 전 같은 트랜잭션에서 PUBLISHED로 전환했다. 이후 1VU 17건 smoke가 모두 성공하고 Hikari pending·Kafka lag·Outbox backlog가 0인 것을 확인한 뒤 본 Run을 시작했다.

### 3. 판정

**FAIL**

- 처리량: 총 `20.84 req/s`, 성공 처리량 `15.07 req/s`
- 기능 정합성: 성공 7,240건과 배송·Outbox 증가량 7,240건 일치
- 실패율: `27.69%`로 기준 10% 초과
- 지연: p95 `3.59초`로 기준 3초 초과
- 실패 원인: 2,773건 모두 Redis 회사 락 3초 타임아웃
- 자원: Hikari pending 0, Data VM CPU 최대 40.96%, Heap 최대 1.08GiB로 DB 풀·DB CPU·JVM 포화 아님

분산락이 회사별 요청을 직렬화하면서 DB 풀과 DB CPU를 충분히 사용하기 전에 요청을 탈락시켰다. 이번 기준선의 핵심 병목은 자원 부족이 아니라 넓은 락 범위와 긴 임계 구간이다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 10,013 |
| 성공 / 실패 | 7,240 / 2,773 |
| 총 HTTP TPS | 20.84 req/s |
| 성공 처리량 | 15.07 req/s |
| 실패율 | 27.69% |
| checks 성공률 | 72.30% |
| 평균 / median | 2.91s / 3.14s |
| p90 / p95 / p99 | 3.48s / 3.59s / 3.79s |
| 최대 응답 시간 | 4.11s |

`checks > 90%`, `http_req_failed < 10%`, `p95 < 3초`를 통과하지 못했다. p99는 6초 기준을 통과했다.

### 5. 락 충돌 분석

| 지표 | 결과 |
| --- | ---: |
| HTTP 409 / Loki lock timeout | 2,773 / 2,773 |
| 회사 2번 락 실패 | 1,381건 |
| 회사 3번 락 실패 | 1,392건 |
| HTTP 5xx | 0건 |
| 종료 후 Redis 배송 락 키 | 0개 |

모든 실패 로그는 `failedKey=lock:delivery:company:*`, `waitSeconds=3`으로 기록됐다. 두 receiver 회사에 실패가 49.8%와 50.2%로 고르게 분포해 특정 데이터 이상이 아니라 회사 단위 락 경합임을 확인했다.

Hikari active는 평균 1.12, 최대 3이고 pending은 전체 구간 0이었다. 락을 획득한 소수 요청만 트랜잭션으로 진입했기 때문에 pool 60을 거의 사용하지 못했다.

### 6. DB 및 비동기 정합성

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 40,840 | 7,240 |
| `p_delivery_route_histories` | 67,200 | 81,680 | 14,480 |
| `p_delivery_outboxes` | 33,600 | 40,840 | 7,240 |

- 배송과 Outbox 증가량이 k6 성공 요청 7,240건과 일치한다.
- 경로 이력은 배송당 2건으로 정확히 증가했다.
- 최종 Outbox는 PUBLISHED 40,840건이고 PENDING·FAILED는 0건이다.
- Outbox publishable backlog는 평균 14.30건, 최대 23건으로 작았고 최종 0으로 회복됐다.
- `delivery.create.succeed` 발행 TPS는 평균 14.11, 최대 17.33이었다.
- 모든 Delivery·Order Kafka consumer group lag는 최종 0이다.
- Redis 배송 락 키와 Hikari pending도 최종 0이다.

실패 요청은 DB에 일부만 반영되거나 유실된 것이 아니라 락 획득 전에 일관되게 거절됐다.

### 7. 자원 및 downstream 관찰

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 50.20% | 83.37% |
| Delivery system CPU | 52.08% | 84.34% |
| Data VM CPU | 29.83% | 40.96% |
| Hikari active | 1.12 | 3 |
| Hikari pending | 0 | 0 |
| JVM heap | 628.78MiB | 1,101.51MiB |
| GC 평균 pause 1m | 52.77ms | 100.11ms |
| Tomcat busy ratio | 29.46% | 40.50% |
| PostgreSQL commit TPS | 211.22 | 258.06 |
| PostgreSQL cache hit | 99.869% | 99.869% |
| User 담당자 검색 평균 지연 | 239.12ms | 360.94ms |
| User 허브 조회 평균 지연 | 17.70ms | 25.79ms |
| Hub 경로 조회 평균 지연 | 1.53ms | 2.49ms |

Heap 최대는 확인된 최대 heap 약 1,988MiB의 55.4%이며 서비스 재기동과 Hikari timeout은 없었다. PostgreSQL deadlock 누적값은 테스트 전후 1로 유지돼 신규 deadlock도 없었다.

최초 구현은 매 요청마다 담당자 목록을 다시 조회하고 그 작업까지 분산락 안에서 수행한다. 현재 HUB 담당자 1,500명 데이터에서 User 검색 지연이 커졌고, 이 시간이 락 보유 시간을 늘려 3초 타임아웃을 대량 발생시킨 것으로 해석된다.

### 8. 과거 동일 구조 100VU 비교

가장 가까운 과거 기준은 [Baseline Run 05 - 100VU, 최초 Redis 분산락 3초](../../../01-baseline/delivery-assignment-baseline-run05-100vu-distributed.md)다. lock wait 2초인 Run 08은 이번 SHA와 다른 구현이므로 직접 기준에서 제외했다.

| 지표 | 과거 Run 05 | 현재 통제 재현 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 6,757 | 10,013 | +48.19% |
| 성공 요청 | 6,757 | 7,240 | +7.15% |
| 성공 처리량 | 14.07 req/s | 15.07 req/s | +7.12% |
| 실패율 | 0.00% | 27.69% | +27.69%p |
| 평균 응답 | 4.80s | 2.91s | -39.37% |
| p95 | 6.29s | 3.59s | -42.93% |
| p99 | 7.39s | 3.79s | -48.71% |
| 최대 응답 | 12.59s | 4.11s | -67.36% |

현재 총 TPS가 높고 지연이 짧아 보이는 주된 이유는 요청 27.69%가 3초 락 타임아웃으로 빠르게 종료됐기 때문이다. 따라서 총 TPS와 지연 감소를 성능 개선으로 해석하면 안 된다. 성공 처리량은 7.12% 증가했지만 현재는 pool 60·전용 Delivery VM·확대된 Data VM을 사용하고 담당자 수도 다르므로 엄격한 동일 인프라 비교가 아니다.

과거에는 Hikari pool 10이 포화돼 pending이 평균 55.2, 최대 78이었지만 현재는 pending이 0이다. 고정 인프라를 확대한 덕분에 과거 DB pool 병목은 제거됐고, 대신 대규모 담당자 조회를 포함한 분산락 임계 구간이 직접 드러났다.

### 9. 로그, Zipkin 및 제외 측정

유효 Run의 Delivery 경고 2,773건은 모두 assignment lock timeout이다. Delivery·User·Hub에는 Zipkin reporter span drop 경고가 각각 1건 있었고 HTTP 5xx는 없었다. 테스트 시간대의 Zipkin 배송 request trace 조회 결과는 빈 배열이어서 요청 지연 근거로 사용하지 않았다.

11:02:34 KST의 첫 시도는 표준 seed의 JSON PENDING·FAILED Outbox를 구버전 `@Lob` 매핑이 읽지 못해 scheduler 오류와 downstream 502가 발생했다. 약 1분 만에 중단했으며 결과에서 제외했다. 이후 기존 Outbox를 같은 초기화 트랜잭션에서 PUBLISHED로 정리했고, `Bad value for type long` 오류 0건과 1VU smoke 성공을 확인한 뒤 유효 Run을 수행했다.

### 10. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보존한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/raw/k6-delivery-replay-redis-lock-run01-100vu.log` | 유효 k6 전체 출력 |
| `local-artifacts/raw/run.meta` | 시작 시각과 원격 프로세스 정보 |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | DB baseline·최종·증가량 |
| `local-artifacts/metrics-summary.csv` | 테스트 구간 핵심 Grafana 지표 |
| `local-artifacts/loki-summary.csv` | 락 실패 및 오류 집계 |
| `local-artifacts/recovery-summary.csv` | Outbox·Kafka·Redis·Hikari 회복 결과 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 전체 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panels/` | target별 CSV 60개 |
| `local-artifacts/grafana/panel-manifest.csv` | 각 query와 수집 상태 |
| `local-artifacts/grafana/dashboard-snapshot.json` | 테스트 당시 대시보드 스냅샷 |
| `local-artifacts/raw/zipkin-delivery-service-test-window.json` | 테스트 구간 Zipkin 조회 결과 |

Grafana 수집 범위는 11:14:07부터 11:24:08 KST, step은 15초다. 60개 target 모두 조회에 성공했고 3,913개 시계열 표본을 저장했다. Error 로그 target의 표본이 0인 것은 조회 실패가 아니라 해당 로그가 없었기 때문이다.

### 11. 결론

최초 Redis 분산락 구현은 현재 표준 100VU 데이터에서 성공 처리량 `15.07 req/s`, 실패율 `27.69%`를 기록했다. DB 풀, Data VM CPU, JVM에는 여유가 있었지만 담당자 조회를 포함한 회사 단위 락 임계 구간 때문에 2,773건이 3초 안에 락을 얻지 못했다.

다음 재현 단계에서는 같은 인프라·동일 seed·동일 100VU를 유지하고 집계·벌크 처리 및 Redis 락 범위 축소 이미지만 교체해야 한다. 이때 성공 처리량, 409, lock timeout, Hikari 사용량과 User 담당자 검색 지연이 어떻게 변하는지를 직접 비교한다.
