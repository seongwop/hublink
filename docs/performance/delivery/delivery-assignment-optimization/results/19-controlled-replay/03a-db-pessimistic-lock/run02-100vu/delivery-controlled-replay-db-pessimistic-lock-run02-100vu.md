# 배송 배정 통제 재현 C 단계 - DB 비관적 락 100VU Run 02

## 1. 테스트 목적

C 단계의 높은 실패율이 콜드 상태나 일시적인 서버 이상인지 확인한다. 저부하 예열과 기존 표준 워밍업을 모두 통과한 뒤 같은 100VU 조건으로 재측정하고 Run 01과 재현성을 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-31 14:14:25 ~ 14:22:25 KST |
| Cloud Run 실행 | `hublink-k6-load-test-cpgqn` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `86c4636f3f9dece7d4ad84e50e9b1e3ea037b83d` |
| 적용 내용 | Redis 배정 락 제거, DB 비관적 락 적용 |
| Delivery Hikari | 최대 60 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |

최초 10VU 콜드 워밍업은 Hub 연쇄 호출 회로가 열려 5,118건 중 5,096건이 실패했으므로 본 측정 준비 단계에서 제외했다. 이후 1VU 경로 예열 119건, 10VU 30초 577건, 5VU 20초 414건이 모두 성공했다. 표준 워밍업 후 DB를 다시 초기화하고 워밍된 회사 경로 캐시는 유지했다.

본 실행 직전 발행 가능 Outbox, Kafka 배송 성공·실패 토픽 lag, Redis Stream 길이, DB 대기 락, Hikari pending, 회로 차단기 OPEN은 모두 0이었다.

## 3. 판정

**FAIL - Run 01과 같은 User 조회 병목 재현**

- 총 317,313건 중 4,350건만 성공하고 312,963건이 실패해 실패율은 `98.62%`였다.
- 성공 처리량은 `9.06 req/s`로 Run 01의 `11.01 req/s`와 같은 낮은 범위다.
- User 회로 차단기는 15초 표본 33개 중 22개에서 OPEN이었다. 최초 OPEN은 부하 시작 1분 뒤인 `14:15:25 KST`였다.
- User process CPU는 최대 `96.53%`, system CPU는 최대 `99.92%`였지만 Hikari pending과 DB 대기 락은 0이었다.
- 따라서 콜드 시작이나 DB pool 문제가 아니라, Redis 직렬화 제거 뒤 요청마다 수행되는 User 담당자 조회가 다음 병목으로 드러난 결과다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 317,313 |
| 성공 / 실패 | 4,350 / 312,963 |
| 총 HTTP TPS | 661.03 req/s |
| 성공 처리량 | 9.06 req/s |
| 실패율 | 98.62% |
| 전체 평균 / median | 120.44ms / 89.92ms |
| 전체 p90 / p95 | 164.42ms / 248.00ms |
| 성공 응답 평균 / p95 | 1.41s / 2.80s |
| 최대 응답 시간 | 9.59s |
| 중단 iteration | 0 |
| 종료 코드 | 99 |

전체 평균과 p95가 짧은 이유는 열린 User 회로가 대부분 요청을 빠르게 `DELIVERY_011` 502로 반환했기 때문이다. 성공 응답만 보면 평균 1.41초, p95 2.80초이므로 전체 지연을 개선 수치로 사용할 수 없다.

## 5. Run 01 재현성

| 지표 | Run 01 | Run 02 | 해석 |
| --- | ---: | ---: | --- |
| 성공 처리량 | 11.01 req/s | 9.06 req/s | 둘 다 약 10 req/s |
| 실패율 | 97.96% | 98.62% | `+0.66%p`, 동일한 실패 상태 |
| 성공 건수 | 5,287 | 4,350 | User 회로 개방 시점 차이 |
| 전체 평균 | 148.64ms | 120.44ms | 빠른 502 비중 증가 |
| 전체 p95 | 296.99ms | 248.00ms | 빠른 502 비중 증가 |
| User 회로 OPEN 표본 | 22 / 33 | 22 / 33 | 개방 지속 시간 동일 |
| User 조회 RPS 평균 | 9.79 req/s | 8.81 req/s | 같은 처리 상한 |
| User CPU 최대 | 98.70% | 96.53% | CPU 포화 재현 |

Run 02는 워밍업을 더 안정적으로 통과했지만 본 부하에서는 같은 회로 개방이 발생했다. 따라서 Run 01의 결과는 일시적인 서버 이상이 아니라 C 단계 구현의 재현 가능한 한계다.

## 6. A→C 및 C→D 해석

| 단계 | 성공 처리량 | 실패율 | 평균 응답 | p95 | 핵심 병목 |
| --- | ---: | ---: | ---: | ---: | --- |
| A: Redis 분산락 전체 임계 구간 | 15.07 req/s | 27.69% | 2.91s | 3.59s | Redis lock timeout |
| C Run 02: DB 비관적 락 | 9.06 req/s | 98.62% | 120.44ms | 248.00ms | User 회로 개방 |
| D: 원자적 선점·담당자 캐시 | 172.33 req/s | 0.00% | 472.12ms | 778.23ms | 비교 조건에서 병목 해소 |

A→C는 완성된 성능 개선 비교가 아니라 Redis 직렬화 병목을 제거한 뒤 User 조회 병목이 이동한 진단 과정이다. 성능 개선 완료 수치는 C→D 누적 비교로 제시해야 한다.

## 7. 배정 계측

| 구간 | 평균 | 1분 평균 최대 | 단건 최대 |
| --- | ---: | ---: | ---: |
| Company 집계 행 `read_for_update` | 10.78ms | 21.20ms | 336.99ms |
| Hub 집계 행 `read_for_update` | 155.82ms | 577.40ms | 6.52s |
| Company·Hub 집계 증가 | 1.58ms | 8.62ms | 317.71ms |

배송 저장은 평균 0.24ms, 경로 이력 저장은 0.31ms, Outbox 적재는 1.73ms, 성공 트랜잭션 전체는 2.49ms였다. 실제 쓰기보다 Hub 후보 집계 행을 비관적 락으로 읽는 구간이 훨씬 오래 걸려 C 단계 DB 선점에도 직렬화 병목이 남아 있다.

Prometheus `increase()`로 추정한 성공 경로 표본은 약 3,909건이다. 경계 보정이 있으므로 정확한 성공 건수는 DB 증가량과 k6의 4,350건을 기준으로 삼았다.

## 8. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 68.25% | 89.28% |
| Delivery system CPU | 69.27% | 89.98% |
| User process CPU | 31.41% | 96.53% |
| User system CPU | 59.27% | 99.92% |
| JVM heap 사용량 | 251.32MiB | 389.44MiB |
| GC 평균 pause | 11.70ms | 16.90ms |
| GC 단일 최대 pause | - | 35.00ms |
| Hikari active | 3.91 | 28 |
| Hikari pending | 0 | 0 |
| Data VM CPU | 12.94% | 22.86% |
| PostgreSQL commit TPS | 704.83 | 1,004.54 |
| User 담당자 조회 지연 | 1,193.08ms | 1,658.03ms |
| User 담당자 조회 RPS | 8.81 req/s | 29.77 req/s |

JVM heap과 GC, DB connection pool, Data VM CPU에는 포화 근거가 없다. User 서비스 CPU와 담당자 조회 지연이 1차 실패 원인이다.

## 9. 정합성 및 회복

| 항목 | 실행 전 | 실행 후 | 증가 |
| --- | ---: | ---: | ---: |
| 배송 | 33,600 | 37,950 | 4,350 |
| 배송 경로 | 67,200 | 75,900 | 8,700 |
| Outbox | 33,600 | 37,950 | 4,350 |
| 배정 집계 행 | 3,300 | 3,300 | 0 |
| 활성 배정 합계 | 5,400 | 14,100 | 8,700 |

성공 4,350건이 배송·Outbox 증가량과 정확히 일치하고 경로 및 활성 배정은 그 두 배다. 실패 요청의 부분 저장은 확인되지 않았다.

종료 후 발행 가능 Outbox, Kafka 배송 성공·실패 토픽 lag, DB 대기 락, Hikari pending은 모두 0이었다. Delivery 컨테이너 재시작은 없었고 health는 `UP`, User 회로는 `CLOSED`로 회복했다.

`deadline:requested:stream` 길이 4,350은 성공 배송 수와 같고 consumer group이 없는 이벤트 보관 값이다. 처리 대기 Kafka lag나 Outbox backlog로 해석하지 않으며 다음 테스트 초기화 시 제거한다.

## 10. 로그와 trace

Loki에서 Delivery WARN 300건, ERROR 0건, 배정 락 timeout 0건을 확인했다. WARN은 모두 단일 User 인스턴스 재시도 시 대체 인스턴스가 없다는 `RetryAwareServiceInstanceListSupplier` 메시지였고 299건에서 `USER-SERVICE`가 식별됐다.

Zipkin 조회 시점에는 고빈도 Outbox 스케줄러 trace가 저장 공간을 덮어써 테스트 구간의 `POST /internal/deliveries` trace를 회수하지 못했다. 따라서 이번 판정은 k6, Prometheus, Loki, DB 정합성 결과를 기준으로 한다.

## 11. 산출물

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 결과 |
| `local-artifacts/warmup-summary.csv` | 예열·표준 워밍업 결과 |
| `local-artifacts/grafana/` | Grafana 35개 패널·60개 target 전체 CSV |
| `local-artifacts/grafana-instrumentation/` | 배정 계측 9개 패널 전체 CSV |
| `local-artifacts/metrics-summary.csv` | CPU·JVM·Hikari·DB·User 요약 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 비관적 락과 집계 연산 계측 |
| `local-artifacts/db-summary.csv` | 실행 전후 정합성 |
| `local-artifacts/recovery-summary.csv` | Outbox·Kafka·Redis·회로 회복 |
| `local-artifacts/loki-summary.csv` | WARN·ERROR·lock timeout 분류 |
| `local-artifacts/comparison-a-to-c-timeseries.csv` | A→C Run 02 저장 시계열 |
| `local-artifacts/comparison-c-to-d-timeseries.csv` | C Run 02→D 저장 시계열 |

## 12. 결론

충분한 예열과 동일한 초기화 뒤에도 C 단계는 User 회로 개방으로 실패율 98.62%를 기록했다. Run 01과 Run 02가 같은 OPEN 표본 수와 약 10 req/s의 성공 상한을 보였으므로, DB 비관적 락 단계는 완성된 개선 결과가 아니라 후속 User 조회 병목을 발견한 중간 진단 단계다.

포트폴리오에서는 A→C를 “Redis 락 제거 후 병목 이동을 계측한 과정”으로 제시하고, 최종 성능 개선은 C→D의 원자적 선점·담당자 캐시 누적 효과로 설명하는 것이 정확하다.
