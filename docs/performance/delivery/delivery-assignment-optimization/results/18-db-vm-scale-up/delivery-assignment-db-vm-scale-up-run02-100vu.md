# Delivery Assignment DB VM Scale-up Run 02 - 100VU 재측정 결과

### 1. 테스트 목적

4 vCPU Data VM의 warm 상태에서 100VU를 재측정하고, 2 vCPU 기준 Run과 동일 구간을 비교해 DB 스케일업 효과를 검증한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-21 00:12:40 ~ 00:20:40 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=/opt/hublink/performance/k6/.env.db-scaleup-100vu ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| DB pool | Delivery Hikari 최대 60 |
| 후보 인덱스 | 미적용 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| k6 로그 | `/var/tmp/hublink-k6/db-scaleup-run02-100vu-20260721T001236.log` |

테스트 전 Outbox와 Redis 소비자 그룹의 pending·lag 0, 관련 서비스 health 200, Hub 경로 18개 warm 상태를 확인했다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 55,158 |
| 전체 HTTP 요청률 | 114.91 req/s |
| 성공 요청 수 | 43,200 |
| 실패 요청 수 | 11,958 |
| 실패율 | 21.67% |
| checks 성공률 | 78.32% |
| 전체 평균 응답 시간 | 708.06ms |
| 전체 p95 / p99 | 1.54s / 1.96s |
| 성공 응답 평균 / p95 | 834.97ms / 1.61s |
| 최대 응답 시간 | 2.98s |
| max VU | 100 |

응답 시간 threshold는 통과했지만 checks와 실패율 threshold는 실패했다.

```text
checks: 78.32% < 90%
http_req_failed: 21.67% > 10%
p95: 1.54s < 3s
p99: 1.96s < 6s
```

실패 11,958건은 서버 장애가 아니라 `DELIVERY_004`다. 허브 담당자 1,500명의 최대 30건 수용량 45,000건에서 baseline 배정 1,800건을 제외한 43,200건을 모두 사용한 뒤 발생했다.

### 4. 2 vCPU 대비 핵심 비교표

비교 기준은 [후보 인덱스 제거 2 vCPU Run](../17-assignment-candidate-index/delivery-assignment-candidate-index-run02-100vu-index-removed.md)이다. 두 Run 모두 Pool 60, 후보 인덱스 미적용, 100VU, 동일 부하 패턴이다.

전체 8분 비교는 Run 02가 6분 36초에 담당자 용량을 소진해 이후 요청이 빠르게 거절되는 영향을 받는다. 따라서 용량 도달 전 동일 396초 구간을 주 비교값으로 사용한다.

| 동일 396초 지표 | 2 vCPU / 8GB | 4 vCPU / 16GB | 변화 |
| --- | ---: | ---: | ---: |
| 성공 처리 수 | 31,725 | 42,844 | 35.0% 증가 |
| 성공 처리율 | 80.11 req/s | 108.19 req/s | 35.0% 증가 |
| 성공 서버 평균 지연 | 1,194.48ms | 854.49ms | 28.5% 감소 |
| Data VM CPU 평균 / 최대 | 93.21% / 100% | 56.58% / 77.70% | 포화 해소 |
| Data VM load1 평균 / 최대 | 37.96 / 57.79 | 6.84 / 15.88 | 감소 |
| Hikari active 평균 / 최대 | 54.68 / 60 | 49.50 / 60 | 감소 |
| Hikari pending 평균 / 최대 | 32.55 / 42 | 19.81 / 41 | 평균 39.1% 감소 |
| PostgreSQL commit TPS 평균 / 최대 | 374.05 / 438.37 | 555.68 / 735.47 | 평균 48.6% 증가 |

전체 부하 패턴 기준으로도 성공 요청은 37,570건에서 용량 상한인 43,200건으로 15.0% 증가했다. 8분 전체 성공 처리율은 78.27 req/s에서 90.00 req/s로 증가했고, 성공 응답 평균은 1.04초에서 0.835초, p95는 1.75초에서 1.61초로 감소했다.

### 5. DB 단계별 결과

| 동일 396초 지표 | 2 vCPU / 8GB | 4 vCPU / 16GB | 변화 |
| --- | ---: | ---: | ---: |
| 회사 담당자 원자적 선점 | 234.21ms | 82.00ms | 65.0% 감소 |
| 허브 담당자 원자적 선점 | 357.13ms | 102.06ms | 71.4% 감소 |
| 배송 저장 트랜잭션 | 6.87ms | 41.34ms | 증가 |
| Outbox 저장 | 6.04ms | 30.92ms | 증가 |
| 물리 블록 읽기 | 29,968 | 50,640 | 69.0% 증가 |
| 요청당 물리 블록 읽기 | 0.94 | 1.18 | 25.1% 증가 |
| PostgreSQL 캐시 적중률 | 99.988% | 99.983% | 유사 |

원자적 선점 쿼리의 동시 처리 성능이 크게 개선되면서 전체 처리량이 증가했다. 저장 단계는 2 vCPU Run보다 느리지만, 선점 지연 감소 효과가 더 커 성공 서버 평균 지연은 최종 감소했다.

### 6. DB / Outbox 결과

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 76,800 | 43,200 |
| `p_delivery_route_histories` | 67,200 | 153,600 | 86,400 |
| `p_delivery_outboxes` | 33,600 | 76,800 | 43,200 |

k6 성공 수와 배송·Outbox 증가 수가 일치하고 경로 이력은 요청당 2건씩 생성됐다. 이번 Run에서 `delivery.create.failed`와 `delivery.create.dlq` 증가는 0건이다.

| 배정 유형 | row 수 | 최종 배정 합계 | 최소 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 46,800 | 25 | 30 |
| `HUB_DELIVERY` | 1,500 | 45,000 | 30 | 30 |

허브 담당자 전원이 30건에 도달해 성공 수가 정확히 43,200건에서 멈췄다. 회사 담당자는 일부 여유가 있지만 허브 담당자 용량이 먼저 소진됐다.

테스트 종료 직후 Outbox는 `PENDING 40,500`, `PUBLISHED 36,300`이었다. 마지막 Outbox는 00:29:41 KST에 발행되어 종료 후 9분 2초에 전체 회복했다. 발행 실패 로그는 0건이다.

### 7. 비동기 후속 처리

| 처리 단계 | 최대 또는 직후 값 | 최종값 | 종료 후 회복 시간 |
| --- | ---: | ---: | ---: |
| AI requested Stream lag | 최대 23,235 | 0 | 5분 20초 |
| Delivery generated group | 관측 lag 10,048 | 0 | AI 처리 완료 후 안정화 |
| Slack generated group | 관측 최대 lag 21,327 | 0 | 18분 41초 |
| Outbox PENDING | 직후 40,500 | 0 | 9분 2초 |

AI, Delivery, Slack 소비자 모두 최종 43,200건을 처리했고 pending·lag 0으로 회복했다. Slack 소비자는 한 번에 100건을 읽고 배치 종료 후 3초를 대기하므로 이론상 최대 약 33.3건/초다. 실제 회복 속도도 약 30건/초로 나타나 가장 늦게 회복했다.

### 8. Grafana 관찰 결과

| 항목 | 값 |
| --- | ---: |
| Data VM CPU 평균 / 최대 | 56.55% / 77.70% |
| Data VM iowait 평균 / 최대 | 2.67% / 3.80% |
| Data VM load1 평균 / 최대 | 7.82 / 15.88 |
| Delivery process CPU 평균 / 최대 | 47.70% / 68.82% |
| Delivery API 1분 RPS 최대 | 161.73 req/s |
| Hikari active 평균 / 최대 | 45.30 / 60 |
| Hikari pending 평균 / 최대 | 16.58 / 41 |
| PostgreSQL connection 평균 / 최대 | 75.26 / 82 |
| PostgreSQL active 평균 / 최대 | 5.52 / 21 |
| PostgreSQL commit TPS 평균 / 최대 | 558.38 / 735.47 |
| JVM heap 최대 | 503.74MiB |
| GC pause 최대 | 230ms |

DB CPU 포화는 해소됐고 JVM CPU·heap·GC도 주 병목 근거가 없다. Hikari는 순간적으로 최대 60과 pending 41에 도달했지만 평균 pending은 2 vCPU 대비 감소했다.

### 9. 로그 및 Zipkin 분석

테스트부터 전체 비동기 회복까지 delivery, hub, user, AI, Slack 서비스의 WARN·ERROR와 Outbox 발행 실패는 0건이었다. `DELIVERY_004`는 처리된 404 응답이며 별도 애플리케이션 오류 로그를 남기지 않아 k6 응답과 배정 집계 테이블로 확인했다.

Zipkin API에서는 테스트 구간의 `POST /internal/deliveries` root trace가 보존되지 않아 하위 HTTP span을 정량 집계하지 못했다. Loki의 Hub·User 오류는 0건이고, 동일 구간 Prometheus 단계별 지표에서 원자적 선점 시간이 가장 크게 감소했으므로 외부 호출보다 DB 선점 처리 개선을 주 근거로 사용한다.

### 10. 결론

```text
WARN - DB 스케일업 효과 확인, 담당자 용량과 비동기 소비 속도 한계 노출

- 용량 도달 전 동일 구간 성공 처리율 80.11 -> 108.19 req/s, 35.0% 증가
- 성공 서버 평균 지연 1.19초 -> 0.85초, 28.5% 감소
- Data VM CPU 평균 93.21% -> 56.58%, 최대 100% -> 77.70%
- 회사/허브 원자적 선점 지연 65.0% / 71.4% 감소
- 성공 43,200건, DB 증가 수 일치, failed/DLQ 증가 0건
- 허브 담당자 1,500명 전원이 30건에 도달해 이후 11,958건 거절
- Outbox 9분 2초, 전체 Stream 18분 41초 후 pending/lag 0
- JVM 병목 근거 없음
```

스케일업으로 동기 배송 생성 처리량과 응답 시간이 개선됐다. 다음 부하 테스트는 담당자 최대 배정 수에 막히지 않도록 용량 조건을 별도로 정한 뒤 진행해야 하며, 처리량 증가로 드러난 Outbox와 Slack 소비 속도를 다음 최적화 대상으로 보는 것이 적절하다.
