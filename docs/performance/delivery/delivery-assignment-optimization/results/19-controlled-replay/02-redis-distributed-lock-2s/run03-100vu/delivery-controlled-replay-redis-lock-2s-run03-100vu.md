# 배송 배정 통제 재현 02 - Redis 분산락 2초 100VU Run 03

## 1. 테스트 목적

Redis 분산락 전체 임계 구간 이미지에 최종 단계와 같은 워밍업 절차를 적용하고, 기존 Redis 분산락 Run 02의 결과가 재현되는지 확인한다. 같은 조건으로 측정한 최종 원자적 선점 단계와도 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-29 15:49:17 ~ 15:57:17 KST |
| Cloud Run 실행 | `hublink-k6-load-test-7dfbr` |
| 대상 API | `POST /internal/deliveries` |
| k6 스크립트 | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `3c68e7d849c163d7ff895c8b56d6b40b0d3f5986` |
| 배정 방식 | 회사·허브별 Redis 분산락 전체 임계 구간 |
| 실제 lock wait | 2초 |
| Delivery Hikari | 최대 60 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |
| 초기 Outbox | 전부 `PUBLISHED` |

유효 Run 전에는 서비스 재기동 없이 다음 순서로 워밍업하고 기준선을 복구했다.

1. 10VU 30초: 437건 전부 성공, p95 1.15초
2. 5VU 20초 검증: 265건 전부 성공, p95 579.56ms
3. DB 기준선 복구 및 구버전과 호환되지 않는 기존 Outbox 8,400건을 `PUBLISHED`로 전환
4. Redis의 비Stream 키 20개는 유지하고 배송 Stream 길이만 0으로 정리
5. Outbox backlog·Kafka lag·Hikari pending 0, Hub·User 회로 차단기 `closed` 확인

## 3. 판정

**FAIL**

- 성공 처리량은 `15.88 req/s`다.
- 실패율은 `44.56%`로 기준 10%를 초과했다.
- p95는 `3.70초`로 기준 3초를 초과했다.
- 실패 6,126건 중 6,125건은 Redis 배정 락 2초 타임아웃이고, 1건은 일회성 `DELIVERY_011` 502다.
- Hikari pending, DB waiting lock, 회로 차단기 open, Outbox 미회복은 없었다.

동일 워밍업으로 콜드 상태 영향을 제거해도 Redis 분산락이 요청의 약 45%를 탈락시키는 결과는 바뀌지 않았다. 병목은 JVM·DB 용량보다 넓은 락 범위와 직렬화된 임계 구간이다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 13,746 |
| 성공 / 실패 | 7,620 / 6,126 |
| 총 HTTP TPS | 28.64 req/s |
| 성공 처리량 | 15.88 req/s |
| 실패율 | 44.56% |
| checks 성공률 | 55.43% |
| 평균 / median | 2.84s / 3.08s |
| p90 / p95 | 3.57s / 3.70s |
| 최대 응답 시간 | 4.95s |
| 중단 iteration | 0 |
| 종료 코드 | 99 |

종료 코드 99는 k6 임계값 위반에 따른 결과이며 Cloud Run이나 컨테이너 실행 장애가 아니다.

## 5. 기존 Redis 분산락 Run 02 재현성

| 지표 | 기존 Run 02 | 동일 워밍업 Run 03 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 14,089 | 13,746 | -2.43% |
| 성공 요청 | 7,622 | 7,620 | -0.03% |
| 성공 처리량 | 15.88 req/s | 15.88 req/s | -0.03% |
| 실패율 | 45.90% | 44.56% | -1.34%p |
| 평균 응답 | 2.77s | 2.84s | +2.53% |
| p95 | 3.61s | 3.70s | +2.49% |
| Delivery process CPU 평균 | 49.92% | 53.02% | +6.21% |
| Data VM CPU 평균 | 24.59% | 23.33% | -5.12% |
| Hikari pending 최대 | 0 | 0 | 동일 |
| Outbox backlog 최대 | 24 | 23 | -1 |

성공 수는 2건, 성공 처리량은 반올림 기준 동일하다. 실패율과 지연도 약 1.3%p·2.5% 범위에 있어 기존 Run 02의 결론을 재현했다.

## 6. 최종 원자적 선점 단계 비교

| 지표 | Redis 분산락 Run 03 | 최종 원자적 선점 Run 02 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 7,620 | 82,720 | +985.56% |
| 성공 처리량 | 15.88 req/s | 172.33 req/s | +985.54% |
| 실패율 | 44.56% | 0.00% | -44.56%p |
| 평균 응답 | 2.84s | 472.12ms | -83.38% |
| p95 | 3.70s | 778.23ms | -78.97% |

워밍업 여부를 통제한 비교에서도 원자적 선점·`SKIP LOCKED`·담당자 캐시 누적 적용 후 성공 처리량은 약 10.86배가 됐고, Redis 락 타임아웃은 6,125건에서 0건으로 줄었다.

## 7. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 53.02% | 64.38% |
| Delivery system CPU | 55.77% | 66.51% |
| JVM heap 사용량 | 713.33MiB | 1,295.36MiB |
| GC 평균 pause | 60.47ms | 87.14ms |
| Hikari active | 0.70 | 1 |
| Hikari pending | 0 | 0 |
| Data VM CPU | 23.33% | 28.24% |
| Data VM memory | 14.75% | 15.11% |
| PostgreSQL commit TPS | 165.61 | 189.54 |
| PostgreSQL rollback TPS | 0.01 | 0.24 |
| PostgreSQL cache hit ratio | 99.93% | 99.93% |
| Outbox 발행 TPS | 14.77 | 17.27 |
| Outbox publishable backlog | 13.94 | 23 |

Hikari timeout과 PostgreSQL deadlock은 0건이다. Heap 사용량은 기존 Run 02보다 높았지만 GC pause 급증이나 처리 중단은 없었다. 반대로 DB CPU와 Hikari active도 낮아 자원을 다 사용하기 전에 분산락에서 요청이 탈락한 형태다.

## 8. Downstream·로그·회로 차단기

| 항목 | 평균 | 최대 또는 건수 |
| --- | ---: | ---: |
| User 담당자 검색 | 578.63ms | 813.40ms |
| User 허브 조회 | 308.87ms | 463.35ms |
| Hub 경로 조회 | 1.15ms | 1.21ms |
| Redis 배정 락 타임아웃 | - | 6,125건 |
| HTTP 502 `DELIVERY_011` | - | 1건 |
| Delivery WARN | - | 6,125건 |
| Delivery ERROR | - | 0건 |
| Outbox publish 실패 | - | 0건 |

Hub·User 회로 차단기는 유효 구간 전체에서 `closed=1`, `open=0`, `half_open=0`이었다. 502 1건은 회로 개방이나 연쇄 장애로 이어지지 않았다.

## 9. DB 정합성과 회복

| 테이블 | 기준 | 종료 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 41,220 | 7,620 |
| `p_delivery_route_histories` | 67,200 | 82,440 | 15,240 |
| `p_delivery_outboxes` | 33,600 | 41,220 | 7,620 |

성공 요청, 배송 증가, Outbox 증가가 모두 7,620건으로 일치하고 경로는 성공 요청당 2건 증가했다. 마지막 Outbox는 마지막 배송 생성 후 약 0.36초 뒤 `PUBLISHED`가 됐다.

| 회복 항목 | 결과 |
| --- | --- |
| Outbox `PENDING/FAILED` | 0 |
| Redis requested/generated Stream | 길이 0 |
| Kafka consumer lag | 0 |
| Redis 배정 lock 잔여 | 0 |
| DB 30초 이상 트랜잭션 / waiting lock | 0 / 0 |
| Delivery 상태 | UP, restart 0 |

## 10. Zipkin 표본

테스트 종료 구간 최근 trace 1,000개 중 성공 배송 root span 952개와 Outbox scheduler 41개를 집계했다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| Delivery root | 952 | 1,247.20ms | 2,141.68ms | 2,349.28ms |
| User 담당자 검색 | 952 | 51.70ms | 88.73ms | 283.77ms |
| User 허브 조회 | 952 | 3.55ms | 4.29ms | 16.34ms |
| Hub 경로 조회 | 952 | 1.18ms | 1.47ms | 2.44ms |
| Outbox scheduler | 41 | 277.23ms | 329.72ms | 350.18ms |

Zipkin은 최근 trace 1,000개 제한 때문에 종료 구간의 성공 요청에 치우친 표본이다. 전체 지연과 실패 원인 판정에는 k6·Prometheus·Loki를 우선 사용했다.

## 11. 제외한 실행

첫 본 실행 `hublink-k6-load-test-jrgz7`은 결과에서 제외했다. 워밍업 뒤 기준선을 복구하면서 Redis 전체를 비워 워밍 상태를 유지하지 못했고, 100VU 램프업 중 User 회로 차단기가 열려 `DELIVERY_011` 502가 연속 발생했다. Redis 전체 초기화와 회로 개방의 직접 인과는 확정하지 않았지만, 동일 워밍업 조건이 깨진 교란 실행이므로 성능 비교에는 사용하지 않았다.

실행을 중단하고 회로 복구를 확인한 뒤 10VU·5VU 워밍업을 다시 수행했다. 유효 Run에서는 Redis 전체 초기화 대신 배송 Stream만 정리했고, 회로 차단기 open과 5xx 연쇄가 재발하지 않았다.

## 12. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | 기준 대비 DB 증가분 |
| `local-artifacts/metrics-summary.csv` | JVM·DB·Outbox 지표 |
| `local-artifacts/loki-summary.csv` | 락 타임아웃과 오류 로그 |
| `local-artifacts/zipkin-summary.csv` | Zipkin span 통계 |
| `local-artifacts/recovery-summary.csv` | 종료 후 회복 상태 |
| `local-artifacts/comparison-run02-vs-run03-summary.csv` | 기존 Redis Run 대비 요약 |
| `local-artifacts/comparison-run02-vs-run03-timeseries.csv` | 기존 Redis Run 대비 15초 시계열 |
| `local-artifacts/comparison-redis-vs-final-summary.csv` | 최종 단계 대비 요약 |
| `local-artifacts/comparison-redis-vs-final-timeseries.csv` | 최종 단계 대비 15초 시계열 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panel-manifest.csv` | 패널 query와 수집 상태 |
| `local-artifacts/valid-cloud-run-k6.log` | 유효 실행 원본 로그 |

Grafana 전체 패널은 오류 target 0개로 수집했다.

## 13. 결론

동일 워밍업을 적용해도 Redis 분산락 2초 구조의 성공 처리량은 기존 Run과 같은 `15.88 req/s`였고 실패율은 `44.56%`였다. DB·JVM·Outbox 포화 없이 락 타임아웃만 6,125건 발생했으므로, 이후 원자적 선점 단계의 대폭 개선이 단순 워밍업이나 인프라 편차가 아니라 배정 동시성 제어 구조 변경의 효과라는 근거로 사용할 수 있다.
