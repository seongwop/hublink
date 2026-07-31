# 배송 배정 통제 재현 02 - Redis 분산락 2초 100VU Run 02

## 1. 테스트 목적

Redis 분산락 대기 시간이 실제로 2초인 배송 서비스 이미지를 새 테스트 인프라에서 다시 측정하고, 동일 이미지의 기존 Run 01과 처리량·지연·실패율·자원 사용량을 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-28 17:10:00 ~ 17:18:00 KST |
| Cloud Run 실행 | `hublink-k6-load-test-88ggz` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `3c68e7d849c163d7ff895c8b56d6b40b0d3f5986` |
| 실제 lock wait | 2초 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |
| 초기 Outbox | 전부 `PUBLISHED` |
| 초기 Redis | DB size 0 |

테스트 직전 배송 프로세스 CPU는 1.1%, 시스템 CPU는 1.5%, Data VM CPU는 4.9%였다. Hikari pending과 Outbox backlog는 0이었고 Prometheus 타깃은 13/13 UP, User·Hub 회로차단기는 모두 `closed`였다.

## 3. 판정

**FAIL**

- 성공 처리량: `15.88 req/s`
- 실패율: `45.90%`로 기준 10% 초과
- p95: `3.61초`로 기준 3초 초과
- 실패 6,467건 중 6,466건은 Redis 배정 락 2초 타임아웃
- 일회성 User 서비스 통신 실패 1건 외에 회로차단기 open, Hikari 대기, DB 대기 락, Outbox 미회복은 없었다.

2초 락 대기는 Run 01보다 성공 처리량을 높였지만, 여전히 요청의 약 46%를 실패시켰다. p95도 3초를 넘었으므로 운영 가능한 결과로 판단할 수 없다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 14,089 |
| 성공 / 실패 | 7,622 / 6,467 |
| 총 HTTP TPS | 29.35 req/s |
| 성공 처리량 | 15.88 req/s |
| 실패율 | 45.90% |
| checks 성공률 | 54.10% |
| 평균 / median | 2.77s / 2.99s |
| p90 / p95 / p99 | 3.43s / 3.61s / 3.98s |
| 최대 응답 시간 | 5.23s |
| 성공 응답 평균 | 2.60s |

k6 종료 코드는 임계치 위반에 따른 `99`다. 요청 실행 장애나 Cloud Run 태스크 재시작은 아니었다.

## 5. 기존 Run 01 비교

| 지표 | Run 01 | Run 02 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 12,481 | 14,089 | +12.88% |
| 성공 요청 | 6,328 | 7,622 | +20.45% |
| 성공 처리량 | 13.16 req/s | 15.88 req/s | +20.64% |
| 실패율 | 49.30% | 45.90% | -3.40%p |
| p95 | 2.54s | 3.61s | +42.13% |
| Delivery process CPU 평균 | 56.98% | 49.92% | -12.39% |
| Data VM CPU 평균 | 32.26% | 24.59% | -23.78% |
| Hikari pending 최대 | 0 | 0 | 동일 |
| Outbox backlog 최대 | 24 | 24 | 동일 |

처리량과 실패율은 개선됐지만 응답 지연은 악화됐다. Run 02에서는 User 서비스의 담당자 조회 평균이 558.8ms, 허브 조회 평균이 275.8ms로 Run 01의 192.8ms, 12.3ms보다 길었다. 반면 Hub 경로 조회는 평균 2.4ms로 낮았다. 따라서 p95 증가는 DB pool이나 Outbox가 아니라 User 서비스 측 downstream 지연의 영향을 더 크게 받은 결과다.

## 6. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 49.92% | 62.11% |
| Delivery system CPU | 52.82% | 64.50% |
| JVM heap 사용량 | 406.43MiB | 623.04MiB |
| GC 평균 pause | 58.46ms | 74.32ms |
| Hikari active | 0.73 | 2 |
| Hikari pending | 0 | 0 |
| Tomcat busy ratio | 39.92% | 50.50% |
| Data VM CPU | 24.59% | 29.71% |
| PostgreSQL commit TPS | 162.17 | 185.42 |
| Outbox 발행 TPS | 14.17 | 16.80 |
| Outbox publishable backlog | 13.33 | 24 |

- Hikari timeout, PostgreSQL rollback TPS, deadlock은 모두 0이었다.
- PostgreSQL cache hit ratio는 평균 99.998%였다.
- User·Hub 회로차단기는 전체 수집 구간에서 `closed=1`, `open=0`, `half_open=0`이었다.
- JVM heap과 GC는 포화 근거가 없고 DB CPU와 connection pool에도 병목 징후가 없다.

## 7. 정합성·로그·회복

| 검증 항목 | 결과 |
| --- | --- |
| 배송 증가 | +7,622건 |
| 경로 증가 | +15,244건, 성공 배송당 2건 |
| Outbox 증가 | +7,622건 |
| Outbox 최종 상태 | `PUBLISHED` 41,222건, `PENDING/FAILED` 0건 |
| lock timeout 로그 | 6,466건, 모두 `waitMillis=2000` |
| HTTP 502 | 1건, `DELIVERY_011` |
| Delivery ERROR 로그 | 0건 |
| Outbox backlog 회복 | 테스트 종료 11초 후 0 |
| Kafka 최종 lag | `order-group / delivery.create.succeed` 0 |
| Redis 배송 락 키 | 0 |
| DB 장기 트랜잭션 / 대기 락 | 0 / 0 |
| Delivery 상태 | UP, restart 0 |

성공 요청 7,622건이 배송·Outbox 증가분과 정확히 일치했고 경로도 두 배 증가했다. 실패 요청이 일부 저장된 정합성 문제나 락 누수는 발견되지 않았다.

## 8. 제외한 사전 실행

최초 100VU 실행은 결과에서 제외했다. Cloud Run 래퍼의 `K6_VUS`가 k6 예약 환경변수와 충돌해 의도한 1분 ramp-up 대신 시작 직후 100VU가 투입됐고, User·Hub 통신 실패가 연쇄 발생했다.

래퍼가 단계 배열을 만든 뒤 예약변수를 제거하도록 수정하고, 5VU 검증에서 `1→2→3→4→5→0` 상승·하강과 214건 전부 성공을 확인했다. 이후 DB·Redis를 다시 초기화하고 본 Run 02를 실행했다.

## 9. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | 기준 대비 DB 증가분 |
| `local-artifacts/metrics-summary.csv` | JVM·HTTP·DB·Outbox 지표 |
| `local-artifacts/loki-summary.csv` | 락 타임아웃과 오류 로그 |
| `local-artifacts/recovery-summary.csv` | 종료 후 회복 상태 |
| `local-artifacts/comparison-run01-vs-run02-summary.csv` | Run 01·02 요약 비교 |
| `local-artifacts/comparison-run01-vs-run02-timeseries.csv` | 비교 그래프용 15초 정렬 시계열 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | 패널 query와 수집 상태 |
| `local-artifacts/valid-cloud-run-k6.log` | 유효 실행 원본 로그 |

Grafana 전체 패널은 오류 target 0개로 수집했다. Zipkin은 보고서 수집 시점에 테스트 구간 trace가 메모리 보존 범위를 벗어나 별도 표본을 확보하지 못했다.

## 10. 결론

Run 02는 Run 01보다 성공 처리량이 20.64% 증가하고 실패율이 3.40%p 낮아졌지만, Redis 분산락 2초 구조 자체가 45.90%의 실패를 만들었다. DB·JVM·Outbox가 아니라 회사·허브 단위의 직렬 락 경합이 주 병목이라는 기존 결론은 유지된다.

이 결과는 이후 원자적 `SELECT + UPDATE + SKIP LOCKED` 결과와 비교할 Redis 분산락 기준선으로 사용할 수 있다.
