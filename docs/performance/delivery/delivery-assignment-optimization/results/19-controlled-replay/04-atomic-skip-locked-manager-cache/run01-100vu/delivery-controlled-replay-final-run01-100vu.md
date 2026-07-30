# 배송 배정 통제 재현 04 - 원자적 선점·SKIP LOCKED·담당자 캐시 100VU

## 1. 테스트 목적

Redis 분산락 전체 임계 구간, 집계·벌크 처리와 Redis 락 범위 축소, 원자적 선점·`SKIP LOCKED`·담당자 캐시 누적 적용의 세 단계를 동일한 100VU 조건에서 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-29 13:11:06 ~ 13:19:06 KST |
| Cloud Run 실행 | `hublink-k6-load-test-d9qlm` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `e0149eeffd3931ce4f2b843986950c9853716617` |
| 적용 내용 | DB 원자적 선점, `FOR UPDATE SKIP LOCKED`, 담당자 Caffeine 캐시 |
| 담당자 배정 한도 | 60건 |
| Hikari maximum pool size | 60 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |
| 초기 Outbox | 모두 `PUBLISHED` |

배포 이미지와 설정값을 컨테이너에서 직접 확인했다. Cloud Run runner가 본 실행 직전에 `db/seed/14-reset-delivery-perf-baseline.sql`을 적용했으며 중단이나 재시도 없이 8분을 완료했다.

테스트 전 플랫폼 VM에서 Grafana·Prometheus·Loki·Zipkin이 실행되지 않은 상태를 확인했다. 기존 `docker-compose.platform-monitoring.yml`의 모니터링 서비스만 복구한 후 Prometheus 13개 target, Grafana, Loki와 Zipkin이 모두 정상인 상태에서 트래픽을 시작했다. 애플리케이션 VM 사양과 서비스 배치는 변경하지 않았다.

## 3. 판정

**측정 유효 / 운영 판정 WARN**

- 성공 처리량은 `112.78 req/s`로 Redis 분산락 2초 단계보다 `610.28%`, 중간 단계보다 `550.37%` 증가했다.
- p95는 `1.15초`로 각각 `68.14%`, `65.36%` 감소했다.
- DB 정합성, Outbox 최종 발행, Redis Streams 최종 소비와 서비스 회복은 정상이다.
- 초기 ramp-up 약 30초 동안 Hub 회로가 열리며 5,366건의 502가 발생해 실패율이 `9.02%`였다.
- Delivery process CPU 평균 `91.61%`, 최대 `100%`와 Hikari pending 최대 `33`으로 추가 부하 여유가 부족하다.
- Outbox backlog 최대 `52,374건`과 Slack consumer 회복 지연으로 동기 배송 생성 이후의 비동기 파이프라인이 다음 병목으로 이동했다.

k6의 p95 3초 미만과 실패율 10% 미만 기준은 통과했지만, 회로 차단과 자원 포화가 확인됐으므로 운영 가능한 PASS로 판정하지 않는다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 59,506 |
| 성공 / 실패 | 54,140 / 5,366 |
| 총 HTTP TPS | 123.96 req/s |
| 성공 처리량 | 112.78 req/s |
| 실패 처리량 | 11.18 req/s |
| 실패율 | 9.02% |
| checks 성공률 | 90.98% |
| 평균 / median | 656.51ms / 711.95ms |
| p90 / p95 | 1.03s / 1.15s |
| p99 | k6 최종 출력 미제공 |
| 최대 응답 시간 | 6.55s |
| 성공 응답 평균 | 711.15ms |
| 중단 iteration | 0 |
| 종료 코드 | 0 |

최대 응답 시간은 초기 Hub 호출 지연과 회로 상태 전환 구간의 영향을 받았다. p95와 성공 처리량은 전체 8분 결과이며 502는 초기 구간에 집중됐다.

## 5. 세 단계 비교

| 지표 | Redis 분산락 2초 | 집계·벌크·락 범위 축소 | 원자적 선점·캐시 | 최초 대비 |
| --- | ---: | ---: | ---: | ---: |
| 총 요청 | 14,089 | 14,695 | 59,506 | +322.36% |
| 성공 요청 | 7,622 | 8,325 | 54,140 | +610.31% |
| 성공 TPS | 15.88 | 17.34 | 112.78 | +610.28% |
| 실패율 | 45.90% | 43.35% | 9.02% | -36.88%p |
| 평균 응답 시간 | 2.77s | 2.66s | 0.66s | -76.30% |
| p95 | 3.61s | 3.32s | 1.15s | -68.14% |
| 최대 응답 시간 | 5.23s | 4.19s | 6.55s | +25.24% |
| Delivery CPU 평균 | 49.92% | 71.86% | 91.61% | +41.69%p |
| Delivery CPU 최대 | 62.11% | 95.73% | 100.00% | +37.89%p |
| Hikari active 평균 | 0.73 | 1.27 | 48.24 | +47.51 |
| Hikari pending 최대 | 0 | 0 | 33 | +33 |
| Data VM CPU 평균 | 24.59% | 24.52% | 59.15% | +34.56%p |
| PostgreSQL commit TPS 평균 | 162.17 | 189.16 | 595.92 | +267.47% |

세 단계는 각각 다음 이미지를 사용했다.

| 단계 | 이미지 | 핵심 구현 |
| --- | --- | --- |
| 개선 전 | `3c68e7d849c163d7ff895c8b56d6b40b0d3f5986` | Redis 분산락 전체 임계 구간 |
| 중간 단계 | `4a7663202857272091422c00f3012144f481eb62` | 집계·벌크 처리와 Redis 락 범위 축소 |
| 최종 개선 후 | `e0149eeffd3931ce4f2b843986950c9853716617` | 원자적 선점·`SKIP LOCKED`·담당자 캐시 |

최종 단계는 실패 요청까지 빠르게 반환된 영향을 총 TPS에 포함한다. 그러나 성공 TPS만 비교해도 중간 단계 대비 `550.37%` 증가하므로 핵심 개선 효과는 유효하다.

## 6. 배정 선점과 담당자 캐시

| 계측 | 평균 | 최대 | 건수 |
| --- | ---: | ---: | ---: |
| Company 원자적 선점 | 58.92ms | 357.22ms | 54,140 |
| Hub 원자적 선점 | 81.17ms | 469.53ms | 54,140 |
| 담당자 캐시 hit | - | - | 108,256 |
| 담당자 캐시 miss | - | - | 24 |
| 담당자 캐시 hit ratio | 99.98% | - | 108,280 |

Redis lock wait와 lock timeout 계측은 최종 구현 경로에서 발생하지 않았다. 중간 단계의 Hub 락 획득 평균 `1,710.11ms`, timeout 평균 `2,003.26ms`와 달리 두 담당자를 서로 다른 DB row에서 원자적으로 선점해 단일 Hub 락 직렬화를 제거했다.

캐시 miss 24건은 동시 초기 조회를 포함한다. 이후 최신 Zipkin 성공 trace 표본에는 담당자 검색 HTTP client span이 나타나지 않았으며 Hub 기본 정보 조회만 요청별로 유지됐다.

## 7. 502와 회로 차단 분석

| 지표 | Hub Service | User Service |
| --- | ---: | ---: |
| 성공 호출 | 54,140 | 108,280 |
| 실패 호출 | 5 | 0 |
| 회로 차단으로 거부 | 5,368 | 0 |
| 실패 호출 평균 | 4.06s | - |
| 실패 호출 최대 | 4.26s | - |

초기 5개의 느린 Hub 호출이 실패로 집계된 뒤 Hub 회로가 열렸다. Grafana 15초 표본에서 `open=1`은 13:11:21과 13:11:36 KST에만 관측됐고 13:11:51부터 닫힌 상태를 유지했다. k6의 502 본문은 모두 `DELIVERY_013`이었다.

Hub Service 자체 process CPU는 평균 `13.41%`, 최대 `31.64%`, Hikari pending은 최대 0이었다. `/internal/hub-routes/path` 서버 평균은 `2.71ms`였으므로 Hub 프로세스의 지속적인 CPU·DB 포화보다 Delivery 측 초기 호출 지연과 회로 정책의 연쇄 거부로 해석한다.

Resilience4j의 not-permitted 5,368건과 k6 502 5,366건의 2건 차이는 서비스 시작 이후 누적 counter와 유효 k6 요청 범위의 차이다.

## 8. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 91.61% | 100.00% |
| Delivery system CPU | 92.14% | 99.93% |
| JVM heap 사용량 | 331.31MiB | 425.29MiB |
| GC 평균 pause | 27.11ms | 61.25ms |
| Hikari active | 48.24 | 60 |
| Hikari pending | 10.42 | 33 |
| Tomcat busy ratio | 38.98% | 50.50% |
| Data VM CPU | 59.15% | 77.81% |
| Data VM memory | 13.68% | 14.40% |
| PostgreSQL commit TPS | 595.92 | 782.92 |

Hikari timeout, PostgreSQL rollback, deadlock과 대기 락은 모두 0이다. Heap과 GC는 안정적이어서 JVM 메모리 튜닝 근거는 확인되지 않았다.

처리량 증가로 Delivery CPU와 DB connection pool 사용량이 동시에 상승했다. pool 크기만 추가로 늘리면 CPU 경합과 Data VM 부하를 더 높일 수 있으므로 현재 100VU에서 먼저 CPU profile과 트랜잭션·쿼리 비용을 분리해 확인해야 한다.

## 9. DB 정합성과 배정 한도

| 테이블·지표 | 기준 | 종료 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 87,740 | 54,140 |
| `p_delivery_route_histories` | 67,200 | 175,480 | 108,280 |
| `p_delivery_outboxes` | 33,600 | 87,740 | 54,140 |
| `p_delivery_assignment_counts` | 3,300 | 3,300 | 0 |
| 활성 배정 합계 | 5,400 | 113,680 | 108,280 |

성공 54,140건과 배송·Outbox 증가량이 일치하고, 경로와 활성 배정 합계는 성공 건수의 두 배로 일치한다. Company 담당자 최대는 35건, Hub 담당자 최대는 39건으로 한도 60에 도달하지 않았다. 테스트 종료 후 대기 락과 30초 이상 장기 트랜잭션은 0이었다.

## 10. Outbox·Redis Streams 회복

| 지표 | 최대·종료 직후 | 최종 회복 |
| --- | ---: | --- |
| Outbox publishable backlog | 최대 52,374 | 종료 후 10분 43초 내 0 |
| Outbox 종료 직후 `PENDING` | 47,940 | `PENDING/FAILED` 0 |
| AI requested stream lag | 최대 관측 28,546 | 종료 후 8분 35초 내 0 |
| Delivery generated stream lag | 최대 관측 10,917 | 종료 후 4분 26초 내 0 |
| Slack generated stream lag | 최대 관측 28,226 | 종료 후 25분 17초에 0 확인 |

최종 Outbox는 `PUBLISHED` 87,740건이며 Redis Streams의 AI·Delivery·Slack group은 모두 pending 0, lag 0이다. 동기 배송 생성은 크게 개선됐지만 Outbox publisher와 Slack consumer의 회복 시간이 길어져 비동기 처리 용량이 다음 개선 대상이다.

## 11. 로그와 Zipkin

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery 성공 root | 991 | 75.27ms | 127.93ms | 185.18ms |
| User 담당자 검색 client | 0 | - | - | - |
| User Hub 조회 client | 991 | 7.75ms | 18.05ms | 55.53ms |
| Hub 경로 조회 client | 991 | 4.39ms | 11.63ms | 39.32ms |
| Outbox scheduler | 9 | 547.16ms | 932.51ms | 1,037.01ms |

Zipkin은 최근 trace 1,000개 제한으로 회로가 복구된 뒤의 성공 구간에 편향된 표본이다. 전체 지연과 오류율 판정에는 k6와 Prometheus를 우선 사용한다.

Loki에서 Delivery WARN은 18건, ERROR와 배정 lock timeout은 0건이었다. k6 원본 로그에는 초기 구간의 `DELIVERY_013` 502가 기록됐다.

## 12. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | 기준 대비 DB 증가분 |
| `local-artifacts/metrics-summary.csv` | HTTP·JVM·pool·DB·Outbox 지표 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 원자적 선점과 캐시 계측 |
| `local-artifacts/circuit-breaker-summary.csv` | Hub·User 회로 차단 계측 |
| `local-artifacts/downstream-summary.csv` | Hub Service CPU·pool·경로 조회 계측 |
| `local-artifacts/loki-summary.csv` | 오류·경고 로그 집계 |
| `local-artifacts/zipkin-summary.csv` | Zipkin span 통계 |
| `local-artifacts/recovery-summary.csv` | DB·Outbox·Redis 최종 회복 |
| `local-artifacts/comparison-three-stage-summary.csv` | 세 단계 요약 비교 |
| `local-artifacts/comparison-three-stage-timeseries.csv` | 세 단계 15초 정렬 비교 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/valid-cloud-run-k6.log` | 유효 Cloud Run 실행 원본 로그 |

Grafana 전체 패널은 target 오류 0건으로 저장했다. `local-artifacts`는 로컬 분석 자료로 Git 추적에서 제외된다.

## 13. 결론

원자적 선점·`SKIP LOCKED`·담당자 캐시 누적 적용은 동일 100VU에서 성공 TPS를 `15.88 → 112.78 req/s`, p95를 `3.61 → 1.15초`로 개선했다. Redis 단일 Hub 락에서 기다리다 실패하던 구조를 제거한 효과는 명확하다.

동시에 병목은 Delivery CPU·DB connection pool과 비동기 파이프라인으로 이동했다. 현재 결과는 배정 구조 개선의 성능 효과를 입증하지만 초기 Hub 회로 차단 502와 CPU 100% 때문에 최종 운영 한계값으로 사용할 수 없다. 다음 단계는 pool 확대가 아니라 CPU profile과 원자적 선점 SQL·트랜잭션 비용을 확인하고, Outbox·Slack 처리 용량을 별도로 검증하는 것이다.
