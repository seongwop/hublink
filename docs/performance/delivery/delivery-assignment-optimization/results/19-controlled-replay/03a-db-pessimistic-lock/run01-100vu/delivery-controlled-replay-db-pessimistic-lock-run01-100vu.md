# 배송 배정 통제 재현 C 단계 - DB 비관적 락 100VU Run 01

## 1. 테스트 목적

Redis 분산락 범위를 축소한 B 단계와 원자적 선점·담당자 캐시를 적용한 D 단계 사이에서, DB 비관적 락만 적용된 C 단계의 성능과 후속 병목을 측정한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-30 23:58:55 ~ 2026-07-31 00:06:55 KST |
| Cloud Run 실행 | `hublink-k6-load-test-qqcdd` |
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

10VU 30초 워밍업과 5VU 20초 검증을 차례로 수행했다. 각각 382건과 411건이 모두 성공했으며 p95는 1.23초, 267.08ms였다. 이후 DB를 기준선으로 복구하고 워밍된 Redis 비Stream 키 18개를 유지하면서 배송 Stream만 0으로 정리했다. 본 실행 직전 Outbox publishable backlog, Kafka primary consumer lag, Redis 배송 Stream, DB 대기 락, 회로 차단기 open, Hikari pending은 모두 0이었다.

## 3. 판정

**FAIL**

- 성공 처리량은 `11.01 req/s`, 실패율은 `97.96%`였다.
- 실패 254,296건의 주된 유형은 User 서비스 회로 개방에 따른 `DELIVERY_011` 502였다.
- 본 실행 전 두 차례 워밍업은 모두 성공했고 회로 차단기도 닫혀 있었다. 따라서 서버 준비 실패가 아니라 Redis 직렬화 제거 후 급증한 담당자 조회가 User 서비스 병목을 유발한 결과다.
- 평균과 p95가 짧게 보이는 이유는 성공 요청이 빨라진 것이 아니라, 열린 회로가 요청을 빠르게 502로 반환했기 때문이다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 259,583 |
| 성공 / 실패 | 5,287 / 254,296 |
| 총 HTTP TPS | 540.74 req/s |
| 성공 처리량 | 11.01 req/s |
| 실패율 | 97.96% |
| 평균 / median | 148.64ms / 92.39ms |
| p90 / p95 | 186.28ms / 296.99ms |
| 최대 응답 시간 | 20.94s |
| 중단 iteration | 0 |
| 종료 코드 | 99 |

종료 코드 99는 k6 실패율 임계값 위반에 따른 결과이며 Cloud Run이나 컨테이너 장애를 뜻하지 않는다.

## 5. 통제 재현 단계 비교

| 단계 | 성공 처리량 | 실패율 | 평균 응답 | p95 | 해석 |
| --- | ---: | ---: | ---: | ---: | --- |
| A: Redis 분산락 전체 임계 구간 | 15.07 req/s | 27.69% | 2.91s | 3.59s | 회사 Redis 락 3초 타임아웃 |
| B: 집계·벌크·Redis 락 범위 축소 | 16.46 req/s | 45.17% | 2.71s | 3.34s | Hub Redis 락 대기 |
| C: DB 비관적 락 | 11.01 req/s | 97.96% | 148.64ms | 296.99ms | User 회로 개방으로 빠른 실패 |
| D: 원자적 선점·담당자 캐시 | 172.33 req/s | 0.00% | 472.12ms | 778.23ms | 배정과 후속 조회 병목 해소 |

C 단계의 짧은 응답 시간과 높은 총 HTTP TPS는 개선 수치로 사용할 수 없다. 실패 요청이 빠르게 반복된 결과이므로 성공 처리량과 실패율을 우선 해석해야 한다.

## 6. 배정 계측

| 구간 | 횟수 추정 | 평균 | 최대 |
| --- | ---: | ---: | ---: |
| Company 집계 행 `read_for_update` | 4,864 | 11.75ms | 441.55ms |
| Hub 집계 행 `read_for_update` | 4,862.93 | 1,020.07ms | 15,759.91ms |
| Company·Hub 집계 증가 | 4,862.93 | 1.74ms | 310.91ms |

배송 저장은 평균 0.39ms, 경로 이력 `saveAll`은 0.41ms, Outbox 적재는 2.00ms, 생성 트랜잭션 전체는 2.99ms였다. 반면 Hub 후보 집계 행을 비관적 락으로 읽는 구간은 평균 1.02초, 최대 15.76초였다. 실제 쓰기보다 담당자 후보 전체를 잠그는 조회가 훨씬 오래 걸렸으므로 DB 비관적 락 구현 자체에도 직렬화 병목이 남아 있다.

계측 횟수는 Prometheus `increase()`의 경계 보정이 포함된 추정치다. 정확한 성공·실패 건수는 k6와 DB 증가량을 기준으로 삼았다.

## 7. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 76.74% | 98.27% |
| Delivery system CPU | 77.82% | 98.82% |
| User process CPU | 34.31% | 98.70% |
| User system CPU | 57.57% | 99.86% |
| JVM heap 사용량 | 825.17MiB | 1,316.74MiB |
| GC 평균 pause | 26.02ms | 69.05ms |
| GC 최대 pause | 108.30ms | 119ms |
| Hikari active | 14.24 | 60 |
| Hikari pending | 0.61 | 7 |
| Data VM CPU | 16.84% | 31.73% |
| User 담당자 검색 지연 | 782.42ms | 1,578.70ms |
| User 담당자 검색 RPS | 9.79 req/s | 27.20 req/s |

Data VM CPU와 Hikari pending이 낮아 DB 용량이나 connection pool이 이 실행의 1차 실패 원인은 아니다. Delivery와 User CPU는 각각 약 99%에 도달했고 User 담당자 검색 지연도 최대 1.58초로 증가했다. User 회로 차단기는 `15:00:55 UTC`에 처음 열려 약 5분 30초 동안 열린 상태를 유지한 뒤 테스트 종료 구간에 닫혔다.

Heap은 최대 1.29GiB까지 증가했지만 GC 최대 pause는 119ms이며 Hikari timeout과 PostgreSQL deadlock은 0건이다. 따라서 이 실행을 JVM 메모리 또는 DB pool 문제로 해석할 근거는 부족하다.

## 8. 정합성 및 회복

| 항목 | 실행 전 | 실행 후 | 증가 |
| --- | ---: | ---: | ---: |
| 배송 | 33,600 | 38,887 | 5,287 |
| 배송 경로 | 67,200 | 77,774 | 10,574 |
| Outbox | 33,600 | 38,887 | 5,287 |
| 배정 집계 행 | 3,300 | 3,300 | 0 |
| 활성 배정 합계 | 5,400 | 15,974 | 10,574 |

성공 요청 5,287건이 배송과 Outbox 증가량에 정확히 일치하고, 경로와 활성 배정 증가는 그 두 배다. 실패 요청이 부분 저장된 흔적은 없다.

종료 후 publishable Outbox, Kafka 주 소비자 그룹 lag, Redis 배송 Stream, DB 대기 락과 장기 트랜잭션은 모두 0이었다. Delivery 컨테이너 재시작도 0건이고 health는 `UP`이므로 실패 폭증은 서버 재시작이나 미회복 backlog 때문이 아니다. Outbox publishable backlog는 실행 중 최대 288건이었지만 모두 회복됐다.

## 9. 로그 및 Zipkin

Loki에서 Delivery WARN은 234건, ERROR는 0건이었다. WARN은 User 서비스 인스턴스가 하나뿐인 상태에서 재시도 시 이전 인스턴스를 제외하면 대체 인스턴스가 없다는 load balancer 경고다. 첫 WARN은 회로 개방 직전인 `15:00:21.715 UTC`에 발생했다. User 서비스 WARN과 ERROR는 모두 0건이었다.

| Zipkin span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| 성공 Delivery root | 965 | 526.01ms | 1,021.58ms | 1,527.56ms |
| User 담당자 조회 client | 965 | 264.38ms | 648.80ms | 894.20ms |
| User 담당자 조회 server | 965 | 300.69ms | 715.89ms | 976.31ms |
| Hub 경로 조회 client | 965 | 2.10ms | 2.82ms | 21.53ms |

Zipkin 최근 1,000개 trace 중 Delivery root 965개는 모두 성공 201 표본이다. 회로가 열린 뒤 즉시 반환된 대량 502를 대표하지 않으므로 전체 실패율 판정에는 사용하지 않았다. 다만 성공 요청에서도 User 담당자 조회가 Hub 경로 조회보다 압도적으로 오래 걸려 후속 조회 병목을 뒷받침한다.

## 10. 산출물

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 결과 |
| `local-artifacts/warmup-summary.csv` | 2단계 워밍업 결과 |
| `local-artifacts/grafana/` | Grafana 35개 패널, 60개 target 전체 CSV와 dashboard snapshot |
| `local-artifacts/metrics-summary.csv` | JVM·CPU·Hikari·DB·User·Outbox 요약 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 배정 락 조회·증가·생성 단계 계측 |
| `local-artifacts/db-summary.csv` | DB 전후 정합성 |
| `local-artifacts/recovery-summary.csv` | Kafka·Redis·Outbox·회로 회복 |
| `local-artifacts/loki-summary.csv` | WARN·ERROR 분류 |
| `local-artifacts/zipkin-summary.csv` | 성공 trace 표본 분석 |
| `local-artifacts/comparison-a-to-c-timeseries.csv` | A→C 저장 시계열 비교 |
| `local-artifacts/comparison-c-to-d-timeseries.csv` | C→D 저장 시계열 비교 |

Grafana 포트폴리오 대시보드 `pf-redis-lock`은 A→C 병목 이동을, `pf-atomic-cache`는 C→D 누적 개선을 저장 시계열로 표시한다.

## 11. 결론

DB 비관적 락 적용 시점만 재현한 결과, Redis 락 제거 뒤 두 병목이 동시에 드러났다. 성공 요청은 Hub 후보 집계 행 `read_for_update`에서 평균 1.02초 대기했고, 전체 요청에서는 User 담당자 조회 부하가 커져 회로 차단기가 약 5분 30초 동안 열렸다.

따라서 C 단계는 단독 성능 개선 완료 지점이 아니라 병목 이동을 발견한 진단 단계다. 포트폴리오에서는 A→C를 “Redis 락 제거만으로는 충분하지 않았고 새 병목을 계측해 확인한 과정”으로 사용하고, C→D의 원자적 선점·`SKIP LOCKED`·담당자 캐시 누적 적용으로 성공 처리량 `11.01→172.33 req/s`, 실패율 `97.96%→0%`가 된 결과를 개선 완료 지점으로 제시하는 것이 타당하다.
