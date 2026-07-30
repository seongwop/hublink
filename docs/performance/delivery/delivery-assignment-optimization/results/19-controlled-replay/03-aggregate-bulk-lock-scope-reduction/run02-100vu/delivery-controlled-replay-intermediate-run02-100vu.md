# 배송 배정 통제 재현 03 - 집계·벌크 처리와 Redis 락 범위 축소 100VU Run 02

## 1. 테스트 목적

중간 단계 이미지 `4a7663202857272091422c00f3012144f481eb62`를 표준 워밍업한 뒤 100VU로 재측정한다. 과거 같은 이미지 Run 01의 재현성을 확인하고, 동일 워밍업을 적용한 Redis 분산락 단계와 최종 원자적 선점 단계를 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-29 23:22:30 ~ 23:30:31 KST |
| Cloud Run 실행 | `hublink-k6-load-test-kd2zk` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `4a7663202857272091422c00f3012144f481eb62` |
| 적용 내용 | 배정 집계 테이블, 벌크 처리, Redis 락 범위 축소 |
| 실제 lock wait | 2초 |
| Delivery Hikari | 최대 60 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |
| 초기 Outbox | 전부 `PUBLISHED` |

서비스를 재시작하지 않고 10VU 30초 워밍업과 5VU 20초 검증을 차례로 수행했다. 검증은 267건 전부 성공했고 p95는 424.27ms였다. 이후 DB를 기준선으로 복구하고 Redis의 워밍된 비Stream 키 20개는 유지하되 배송 Stream을 0으로 정리했다. 본 실행 직전 Outbox backlog·Kafka lag·Hikari pending은 모두 0이고 회로 차단기는 `closed`였다.

## 3. 판정

**FAIL**

- 성공 처리량은 `16.46 req/s`, 실패율은 `45.17%`, p95는 `3.34초`다.
- 실패 6,509건은 전부 `DELIVERY_ASSIGNMENT_LOCK_TIMEOUT` 409이며 5xx는 없다.
- 과거 같은 이미지 Run 01 대비 성공 TPS는 5.10% 낮지만 평균과 p95 차이는 1.88%, 0.60%에 그쳐 같은 병목을 재현했다.
- Hikari pending·DB waiting lock·회로 차단·Outbox 미회복은 없다.

집계·벌크 처리와 락 범위 축소로 성공 요청 내부의 처리 시간은 줄었지만, 동일 hub를 직렬화하는 Redis 락이 남아 요청의 약 45%가 2초 안에 락을 얻지 못했다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 14,408 |
| 성공 / 실패 | 7,899 / 6,509 |
| 총 HTTP TPS | 30.02 req/s |
| 성공 처리량 | 16.46 req/s |
| 실패율 | 45.17% |
| checks 성공률 | 54.82% |
| 평균 / median | 2.71s / 3.00s |
| p90 / p95 / p99 | 3.24s / 3.34s / 3.58s |
| 최대 응답 시간 | 4.28s |
| 중단 iteration | 0 |
| 종료 코드 | 99 |

종료 코드 99는 k6 임계값 위반에 따른 결과이며 Cloud Run이나 컨테이너 장애가 아니다.

## 5. 같은 이미지 Run 01 재현성

| 지표 | 과거 Run 01 | 표준 워밍업 Run 02 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 14,695 | 14,408 | -1.95% |
| 성공 요청 | 8,325 | 7,899 | -5.12% |
| 성공 처리량 | 17.34 req/s | 16.46 req/s | -5.10% |
| 실패율 | 43.35% | 45.17% | +1.82%p |
| 평균 응답 | 2.66s | 2.71s | +1.88% |
| p95 | 3.32s | 3.34s | +0.60% |
| p99 | 3.57s | 3.58s | +0.28% |
| Delivery process CPU 평균 | 71.86% | 74.76% | +4.03% |
| Data VM CPU 평균 | 24.52% | 19.30% | -21.31% |

Run 01에는 이번의 표준 2단계 워밍업이 없었지만 지연 분포와 실패 형태가 거의 같다. 성공 TPS의 5.1% 변동은 있으나 구현의 성능 등급과 병목 결론은 재현됐다.

## 6. 동일 워밍업 3단계 비교

| 지표 | Redis 분산락 2초 Run 03 | 중간 단계 Run 02 | 최종 원자적 Run 02 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 7,620 | 7,899 | 82,720 |
| 성공 처리량 | 15.88 req/s | 16.46 req/s | 172.33 req/s |
| 실패율 | 44.56% | 45.17% | 0.00% |
| 평균 응답 | 2.84s | 2.71s | 472.12ms |
| p95 | 3.70s | 3.34s | 778.23ms |
| 최대 응답 | 4.95s | 4.28s | 2.91s |

중간 단계는 Redis 단계보다 성공 처리량이 3.66% 증가하고 평균과 p95가 각각 4.58%, 9.73% 감소했다. 다만 실패율은 0.61%p 높아 Redis 락 직렬화 자체는 해결하지 못했다. 최종 원자적 단계는 중간 단계보다 성공 처리량이 947.21% 증가하고 p95가 76.70% 감소했으며 락 실패를 제거했다.

## 7. 배정 계측

| 계측 | 평균 | 최대 |
| --- | ---: | ---: |
| Company 락 성공 대기 | 0.58ms | 105.23ms |
| Hub 락 성공 대기 | 1,777.54ms | 2,010.29ms |
| Hub 락 타임아웃 대기 | 2,003.05ms | 2,324.58ms |
| 성공 혼합 락 보유 | 55.95ms | 708.98ms |
| Company 집계 read | 11.88ms | - |
| Hub 집계 read | 19.61ms | - |
| 집계 벌크 increase | 1.08ms | - |
| 배송 트랜잭션 내부 | 12.26ms | - |

Company 락 대기는 거의 없지만 Hub 락 성공 요청도 평균 1.78초를 기다린다. 집계 증가와 배송 트랜잭션은 각각 약 1.08ms, 12.26ms이므로 지연의 주원인은 집계 쿼리가 아니라 Hub Redis 락 경합이다. 계측 건수는 Prometheus `increase()`의 경계 보정으로 소수점 추정치이며, 정확한 성공·실패 건수는 k6와 Loki를 기준으로 삼았다.

## 8. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 74.76% | 93.78% |
| Delivery system CPU | 76.48% | 96.43% |
| JVM heap 사용량 | 825.80MiB | 1,436.36MiB |
| GC 평균 pause | 73.22ms | 102.00ms |
| Hikari active | 1.21 | 2 |
| Hikari pending | 0 | 0 |
| Tomcat busy ratio | 40.11% | 50.50% |
| Data VM CPU | 19.30% | 25.66% |
| PostgreSQL commit TPS | 182.34 | 221.98 |
| Outbox 발행 TPS | 15.22 | 22.90 |
| Outbox publishable backlog | 15.33 | 31 |

Delivery CPU는 높지만 Hikari active가 최대 2이고 DB CPU도 최대 25.66%다. JVM heap은 회수되며 GC pause 최대도 102ms여서, 이 Run의 3초대 지연을 JVM·DB pool 부족으로 설명할 근거는 없다.

## 9. 정합성 및 회복

| 검증 항목 | 결과 |
| --- | --- |
| 배송 증가 | +7,899건 |
| 경로 증가 | +15,798건 |
| Outbox 증가 | +7,899건 |
| 배정 활성 건수 증가 | +15,798건 |
| Outbox 최종 상태 | `PUBLISHED` 41,499건, `PENDING/FAILED` 0건 |
| Kafka 최종 lag | `delivery.create.succeed`·`delivery.create.failed` 모두 0 |
| Redis 배송 락 / Stream | 잔여 락 0, requested 0, generated 0 |
| DB waiting lock / 장기 트랜잭션 | 0 / 0 |
| Delivery 상태 | UP, restart 0 |

성공 7,899건이 배송·Outbox 증가량과 정확히 일치하고 경로와 배정 활성 건수는 그 두 배다. 실패 요청의 부분 저장이나 Outbox·Kafka·Redis 잔여 backlog는 발견되지 않았다.

## 10. 로그 및 Zipkin

Loki의 WARN 6,511건은 배정 락 타임아웃 6,509건과 Zipkin reporter 경고 2건이다. Delivery ERROR, HTTP 5xx, `DELIVERY_OUTBOX_PUBLISH_FAILED`, `DELIVERY_011`은 모두 0건이다. Zipkin reporter가 연결 재설정으로 321개 span을 버렸으므로 trace는 보조 표본으로만 사용한다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery root | 961 | 904.28ms | 1,612.96ms | 2,058.32ms |
| User 담당자 조회 client | 961 | 44.24ms | 51.69ms | 87.47ms |
| User hub 조회 client | 961 | 4.27ms | 7.26ms | 12.68ms |
| Hub 경로 조회 client | 961 | 2.28ms | 2.83ms | 37.02ms |
| Outbox scheduler | 33 | 382.55ms | 440.42ms | 441.24ms |

Zipkin은 보존된 성공 trace 위주의 최근 1,000개 표본이라 전체 k6 지연을 대표하지 않는다. 전체 부하 병목 판정은 k6와 Redis 락 계측을 우선한다.

## 11. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/warmup-summary.csv` | 2단계 워밍업 결과 |
| `local-artifacts/db-summary.csv` | 기준 대비 DB 증가분 |
| `local-artifacts/metrics-summary.csv` | JVM·HTTP·DB·Outbox 지표 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 락·집계·생성 단계 계측 |
| `local-artifacts/loki-summary.csv` | 실패와 로그 분류 |
| `local-artifacts/zipkin-summary.csv` | Zipkin span 통계 |
| `local-artifacts/recovery-summary.csv` | 종료 후 회복 상태 |
| `local-artifacts/comparison-run01-vs-run02-summary.csv` | 같은 이미지 재현성 비교 |
| `local-artifacts/comparison-three-stage-summary.csv` | 동일 워밍업 3단계 비교 |
| `local-artifacts/comparison-*-timeseries.csv` | 15초 간격 비교 그래프 원본 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/valid-cloud-run-k6.log` | 유효 실행 원본 로그 |

Grafana 60개 target은 오류 0개로 수집됐다.

## 12. 결론

표준 워밍업으로 재측정한 중간 단계는 과거 결과와 같은 성능 등급과 실패 구조를 재현했다. Redis 분산락 단계보다 성공 처리량은 3.66%, p95는 9.73% 개선됐지만 요청의 45.17%가 Hub 락 2초 타임아웃으로 실패했다.

따라서 집계·벌크 처리와 락 범위 축소는 유효한 지연 최적화지만 성공률 문제의 해결책은 아니다. 이 통제 비교에서 가장 큰 개선은 Redis 직렬화를 제거한 최종 원자적 선점·`SKIP LOCKED` 단계에서 발생했다.
