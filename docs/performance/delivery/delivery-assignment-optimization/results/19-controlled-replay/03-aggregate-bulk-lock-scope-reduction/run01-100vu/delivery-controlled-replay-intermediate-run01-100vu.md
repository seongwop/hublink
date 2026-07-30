# 배송 배정 통제 재현 03 - 집계·벌크 처리 및 Redis 락 범위 축소 100VU

## 1. 테스트 목적

배송 담당자 배정 집계·벌크 처리와 Redis 락 범위 축소가 포함된 중간 단계 이미지를 현재 테스트 인프라에서 재현하고, 직전 Redis 분산 락 2초 Run과 처리량·지연·자원 사용량을 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-28 22:56:59 ~ 23:04:59 KST |
| Cloud Run 실행 | `hublink-k6-load-test-z54nm` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `4a7663202857272091422c00f3012144f481eb62` |
| 단계 구성 | 배정 집계·벌크 처리, Redis 락 범위 축소, hub 우선 락 획득 |
| 실제 lock wait | 2초 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |
| 초기 Outbox | 전부 `PUBLISHED` |
| 초기 Redis | 배송 배정 락 키 0 |

배포 이미지와 컨테이너 상태를 직접 확인했으며 Delivery, User, Hub, PostgreSQL, Redis, Kafka, Grafana, Prometheus, Loki와 Zipkin이 모두 응답했다. 유효 Run은 중단이나 task 재시작 없이 8분을 완료했다.

## 3. 판정

**FAIL**

- 성공 처리량은 `17.34 req/s`로 직전 Run보다 `9.21%` 증가했다.
- p95는 `3.32초`로 `8.03%`, p99는 `3.57초`로 `10.30%` 감소했다.
- 실패율은 `45.90%`에서 `43.35%`로 `2.55%p` 감소했지만 기준 10%를 크게 초과했다.
- 실패 6,370건은 전부 Redis 배송 배정 락 2초 타임아웃이었다.
- Delivery process CPU가 평균 `71.86%`, 최대 `95.73%`까지 올라 포화 여유가 작았다.

중간 단계의 처리량과 tail latency 개선은 확인됐지만, 단일 hub 락 경쟁으로 요청의 43%가 실패하므로 운영 가능한 결과는 아니다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 14,695 |
| 성공 / 실패 | 8,325 / 6,370 |
| 총 HTTP TPS | 30.61 req/s |
| 성공 처리량 | 17.34 req/s |
| 실패율 | 43.35% |
| checks 성공률 | 56.65% |
| 평균 / median | 2.66s / 2.92s |
| p90 / p95 / p99 | 3.21s / 3.32s / 3.57s |
| 최대 응답 시간 | 4.19s |
| 성공 응답 평균 | 2.49s |

k6 종료 코드는 임계값 위반에 따른 `99`다. Cloud Run task 장애나 중단으로 인한 실패가 아니다.

## 5. 직전 Redis 락 2초 Run 비교

비교 대상은 동일한 현재 인프라에서 수행한 `02-redis-distributed-lock-2s/run02-100vu`다.

| 지표 | 직전 Run | 중간 단계 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 14,089 | 14,695 | +4.30% |
| 성공 요청 | 7,622 | 8,325 | +9.22% |
| 성공 처리량 | 15.88 req/s | 17.34 req/s | +9.21% |
| 실패율 | 45.90% | 43.35% | -2.55%p |
| 평균 | 2.77s | 2.66s | -3.97% |
| p95 | 3.61s | 3.32s | -8.03% |
| p99 | 3.98s | 3.57s | -10.30% |
| 최대 | 5.23s | 4.19s | -19.89% |
| Delivery process CPU 평균 | 49.92% | 71.86% | +43.96% |
| Delivery process CPU 최대 | 62.11% | 95.73% | +54.13% |
| Data VM CPU 평균 | 24.59% | 24.52% | -0.29% |
| Data VM CPU 최대 | 29.71% | 40.53% | +36.43% |
| PostgreSQL commit TPS 평균 | 162.17 | 189.16 | +16.64% |
| Outbox 발행 TPS 평균 | 14.17 | 15.92 | +12.28% |
| Outbox backlog 최대 | 24 | 34 | +41.67% |
| Hikari pending 최대 | 0 | 0 | 동일 |

DB 평균 CPU와 Hikari 대기는 증가하지 않았는데 Delivery CPU와 commit TPS가 함께 증가했다. 락 범위 축소로 성공 작업이 늘면서 애플리케이션과 DB가 더 많은 유효 작업을 수행한 결과다. 다만 Delivery CPU 최대가 96%에 가까워 다음 단계에서는 처리량 증가뿐 아니라 CPU 여유도 함께 확인해야 한다.

## 6. 배정 집계 및 락 계측

Prometheus의 8분 `increase()`는 scrape 경계 보정으로 횟수가 소수로 나타날 수 있으므로, 정확한 성공·실패 건수는 k6와 DB 증가량을 기준으로 사용했다. 시간 지표는 해당 계측의 평균과 최대를 사용했다.

| 계측 | 평균 | 최대 | 해석 |
| --- | ---: | ---: | --- |
| hub 락 획득 성공 대기 | 1,710.11ms | 2,007.43ms | 성공 요청도 대부분의 시간을 hub 락에서 대기 |
| hub 락 타임아웃 대기 | 2,003.26ms | 2,149.01ms | 설정한 2초 wait와 일치 |
| company 락 획득 성공 대기 | 0.61ms | 22.06ms | company 락 경쟁은 미미 |
| mixed 락 점유 | 52.52ms | 388.17ms | 락을 얻은 뒤의 실제 점유 시간 |
| company 배정 집계 read | 11.67ms | - | 집계 테이블 조회 |
| hub 배정 집계 read | 18.67ms | - | 집계 테이블 조회 |
| mixed 배정 집계 increase | 1.16ms | - | 벌크 증가 처리 |
| 배송 트랜잭션 내부 계측 | 12.58ms | - | 저장·Outbox·경로 이력 처리 |

병목은 company 락이나 집계 증가 쿼리가 아니라 hub 락 직렬화다. 락 점유 시간은 평균 52.52ms지만 100VU가 같은 hub 키에 집중되면서 성공 요청도 평균 1.71초를 기다렸고, 6,370건은 2초 안에 획득하지 못했다.

## 7. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 71.86% | 95.73% |
| Delivery system CPU | 74.06% | 97.53% |
| JVM heap 사용량 | 515.50MiB | 817.52MiB |
| GC 평균 pause | 68.50ms | 110.83ms |
| Hikari active | 1.27 | 2 |
| Hikari pending | 0 | 0 |
| Tomcat busy ratio | 39.97% | 50.50% |
| Data VM CPU | 24.52% | 40.53% |
| PostgreSQL commit TPS | 189.16 | 237.46 |
| Outbox 발행 TPS | 15.92 | 24.47 |
| Outbox publishable backlog | 16.33 | 34 |

Hikari timeout, PostgreSQL rollback TPS, deadlock과 DB waiting lock은 모두 0이었다. JVM heap과 GC 사용량은 늘었지만 장시간 GC 정지나 메모리 고갈은 없었다. 이번 Run의 우선 병목은 JVM 메모리나 DB pool이 아니라 Redis hub 락 경쟁과 그 과정에서 높아진 Delivery CPU다.

## 8. 정합성 및 회복

| 검증 항목 | 결과 |
| --- | --- |
| 배송 증가 | +8,325건 |
| 경로 증가 | +16,650건, 성공 배송당 2건 |
| Outbox 증가 | +8,325건 |
| 배정 활성 건수 증가 | +16,650건, 성공 배송당 2건 |
| Outbox 최종 상태 | `PUBLISHED` 41,925건, `PENDING/FAILED` 0건 |
| Outbox backlog 회복 | 테스트 종료 후 첫 15초 scrape에서 34, 다음 scrape인 16초 후 0 |
| Kafka 최종 lag | `order-group / delivery.create.succeed` 0 |
| Redis 배송 락 키 | 0 |
| DB 장기 트랜잭션 / waiting lock | 0 / 0 |
| Delivery 상태 | UP, restart 0 |

성공 8,325건이 배송·Outbox 증가량과 정확히 일치했고 경로와 배정 활성 건수는 두 배 증가했다. 실패 요청이 일부 저장되거나 락 키가 누수된 정합성 문제는 발견되지 않았다.

## 9. 로그 및 Zipkin

Delivery WARN 6,373건 중 6,370건은 배정 락 타임아웃이다. 나머지는 Prometheus meter 태그 충돌 경고 1건과 Zipkin reporter 경고 2건이며, reporter가 220개 span을 유실했다. Delivery ERROR, HTTP 5xx, `DELIVERY_OUTBOX_PUBLISH_FAILED`와 `DELIVERY_011`은 0건이었다.

Zipkin의 테스트 구간 최근 trace 1,000개 중 성공 배송 root span 968개를 집계했다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery root | 968 | 753.20ms | 1,329.38ms | 2,097.51ms |
| User 담당자 검색 client | 968 | 53.47ms | 74.47ms | 137.44ms |
| User hub 조회 client | 968 | 4.82ms | 6.61ms | 19.73ms |
| Hub 경로 조회 client | 968 | 2.39ms | 3.37ms | 35.17ms |
| Outbox scheduler | 28 | 461.35ms | 544.90ms | 554.01ms |

Zipkin 표본은 저장 제한으로 종료 구간의 성공 요청에 치우쳐 전체 k6 지연을 대표하지 않는다. 전체 부하 구간의 병목 판단에는 k6와 Prometheus 락 계측을 우선 사용했다.

## 10. 제외한 사전 실행

유효 Run 전에 `PRE_TEST_SQL_FILE=/dev/null`을 지정한 실행 1건은 runner의 일반 파일 검사에서 즉시 종료돼 k6 트래픽이 시작되지 않았다. 해당 실행은 결과에서 완전히 제외했다. 이후 DB와 Redis를 기준 상태로 초기화하고 읽기 전용 snapshot SQL을 사용해 유효 Run을 실행했으며, 수집 후 Cloud Run Job의 임시 `PRE_TEST_SQL_FILE` 설정을 제거해 기본 초기화 절차로 복구했다.

## 11. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | 기준 대비 DB 증가분 |
| `local-artifacts/metrics-summary.csv` | JVM·HTTP·DB·Outbox 지표 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 집계·락 계측 원본 집계 |
| `local-artifacts/loki-summary.csv` | 락 타임아웃과 오류 로그 |
| `local-artifacts/zipkin-summary.csv` | Zipkin span 통계 |
| `local-artifacts/recovery-summary.csv` | 종료 후 회복 상태 |
| `local-artifacts/comparison-redis-lock-2s-vs-intermediate-summary.csv` | 직전 Run 대비 요약 비교 |
| `local-artifacts/comparison-redis-lock-2s-vs-intermediate-timeseries.csv` | 동일 PromQL·15초·8분 비교 시계열 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | 패널 query와 수집 상태 |
| `local-artifacts/valid-cloud-run-k6.log` | 유효 실행 원본 로그 |

Grafana 전체 패널은 오류 target 0개로 수집했다.

## 12. 결론

집계·벌크 처리와 Redis 락 범위 축소 단계는 직전 Run보다 성공 처리량을 9.21% 높이고 p95를 8.03% 낮췄다. 따라서 변경 효과는 유효하다.

하지만 같은 hub 키에 대한 Redis 락이 요청을 계속 직렬화해 실패율이 43.35%에 머물렀다. 또한 증가한 유효 작업으로 Delivery CPU 최대가 95.73%에 도달했다. 다음 단계는 동일 100VU 조건에서 Redis 락 직렬화를 제거하는 원자적 DB 선점 방식과 비교하고, 성공률 개선과 함께 Delivery CPU가 감당 가능한 수준인지 확인해야 한다.
