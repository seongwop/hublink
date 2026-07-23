# Delivery Assignment DB VM Scale-up Run 07 - 150VU 한도 60 결과

### 1. 테스트 목적

4 vCPU Data VM, Pool 60, 담당자 배정 한도 60 조건에서 150VU의 처리 한계와 비동기 후속 처리 회복을 확인한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-22 13:05:01 ~ 13:13:01 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=.env.db-scaleup-150vu ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 150 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | `SLEEP_SECONDS=0` |
| DB pool | Delivery Hikari 최대 60 |
| 담당자 배정 한도 | 60건 |
| 후보 인덱스 | 미적용 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| k6 로그 | `/var/tmp/hublink-load-final150/run07-150vu-limit60-20260722T040457.log` |

테스트 전 delivery, hub, user health 200과 Outbox 미발행 0을 확인했다. Redis Stream은 소비자 그룹을 유지한 채 이전 처리 완료 항목 54,101건을 제거했으며, 세 그룹의 pending과 lag가 0인 상태에서 시작했다. Data VM CPU는 약 3.0%, Domain B CPU는 약 10.6%, Hikari pending은 0이었다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 79,941 |
| 성공 요청 수 | 79,941 |
| 실패 요청 수 | 0 |
| HTTP TPS | 166.54 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 732.63ms / 720.44ms |
| p90 / p95 / p99 | 1.10s / 1.23s / 1.54s |
| 최대 응답 시간 | 2.70s |
| max VU | 150 |
| interrupted iteration | 0 |

모든 threshold를 통과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 1.23s < 3s
p99: 1.54s < 6s
```

### 4. DB / Outbox 결과

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 113,541 | 79,941 |
| `p_delivery_route_histories` | 67,200 | 227,082 | 159,882 |
| `p_delivery_outboxes` | 33,600 | 113,541 | 79,941 |

k6 성공 수와 배송·Outbox 증가량이 일치하고 경로 이력은 요청당 2건 증가했다. 이번 Run이 만든 Outbox 79,941건은 모두 `delivery.create.succeed / PUBLISHED`이며 failed와 DLQ 증가는 0건이다.

| 배정 유형 | row 수 | 최종 배정 합계 | 최소 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 83,541 | 46 | 49 |
| `HUB_DELIVERY` | 1,500 | 81,741 | 54 | 56 |

허브 담당자 최대는 56건으로 한도 60에 도달하지 않았다. 따라서 이전 150VU처럼 담당자 용량 때문에 처리량이 잘린 결과가 아니다.

### 5. 비동기 후속 처리

| 처리 단계 | 최대 또는 최종값 | lag 최종 | 회복 시간 |
| --- | ---: | ---: | ---: |
| Outbox | PUBLISHED 79,941 | 0 | 종료 후 18분 5초 이내 최초 확인 |
| AI requested | lag 최대 61,562 | 0 | 종료 후 16분 11초 |
| Delivery generated | 79,941건 처리 | 0 | AI 생성과 함께 회복 |
| Slack generated | 수동 관측 lag 42,098 이상 | 0 | 종료 후 약 40분 6초 |

requested와 generated Stream 길이는 모두 79,941이며 AI, Delivery, Slack 그룹의 최종 pending과 lag는 모두 0이다. Slack 메시지도 테스트 시작 이후 79,941건이 생성돼 전부 `SKIPPED` 상태로 저장됐다. 테스트 환경은 Slack 외부 호출이 비활성화되어 있어 `SKIPPED`가 정상 처리 상태다.

Slack 마지막 메시지 생성 시각은 13:53:07 KST로 테스트 종료 후 40분 6초다. Slack 전용 lag 시계열이 없어 정확한 lag 0 순간은 보존하지 못했지만, 13:38:01에 lag 26,441을 관측했고 약 31건/초로 감소했으며 14:57:04 최종 확인에서 pending과 lag가 모두 0이었다.

### 6. Grafana 관찰 결과

| 지표 | 평균 / 최대 |
| --- | ---: |
| Delivery 성공 RPS | 156.09 / 188.63 req/s |
| Data VM CPU | 89.30% / 99.82% |
| Data VM iowait | 0.38% / 1.58% |
| Data VM load1 | 37.52 / 50.32 |
| Domain B system CPU | 77.77% / 95.57% |
| Delivery process CPU | 49.68% / 62.55% |
| Hikari active | 51.70 / 60 |
| Hikari pending | 61.33 / 92 |
| PostgreSQL connection | 78.33 / 84 |
| PostgreSQL active | 35.06 / 56 |
| PostgreSQL idle in transaction | 10.97 / 23 |
| PostgreSQL commit TPS | 758.76 / 890.50 |
| Tomcat busy ratio | 59.42% / 75.50% |
| JVM heap | 349.69MiB / 546.91MiB |
| GC pause 최대 | 99ms |

Data VM CPU가 최대 99.82%에 도달하고 Hikari active 60, pending 92가 동시에 나타났다. heap과 GC는 안정적이므로 150VU의 병목은 JVM 메모리가 아니라 DB CPU와 connection 대기다.

### 7. 기존 결과 비교

#### 7.1 대표 100VU 비교

비교 기준은 [Run 06 대표 A](delivery-assignment-db-vm-scale-up-run06-100vu-stability-validation.md)다. 두 Run 모두 4 vCPU, Pool 60, 한도 60, 후보 인덱스 미적용, 8분 조건이다.

| 지표 | 100VU 대표 A | 150VU Run 07 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 83,981 | 79,941 | 4.8% 감소 |
| HTTP TPS | 174.95 req/s | 166.54 req/s | 4.8% 감소 |
| 평균 응답 | 464.83ms | 732.63ms | 57.6% 증가 |
| p95 | 765ms | 1.23s | 60.8% 증가 |
| p99 | 909.75ms | 1.54s | 69.3% 증가 |
| Data VM CPU 평균 / 최대 | 90.01% / 99.89% | 89.30% / 99.82% | 같은 포화 |
| Hikari pending 평균 / 최대 | 25.76 / 41 | 61.33 / 92 | 대기 급증 |
| PostgreSQL commit TPS 평균 | 794.01 | 758.76 | 4.4% 감소 |

VU를 100에서 150으로 늘렸지만 처리량은 증가하지 않고 지연과 connection 대기만 악화됐다. 현재 구성의 동기 API 처리 한계는 100VU 부근이며 150VU는 포화 이후 구간이다.

#### 7.2 한도 30의 기존 150VU 비교

[Run 04](delivery-assignment-db-vm-scale-up-run04-150vu-repeat.md)는 같은 4 vCPU와 Pool 60에서 한도 30으로 실행돼 43,200건 이후 `DELIVERY_004`가 발생했다.

| 지표 | 한도 30 Run 04 | 한도 60 Run 07 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 43,200 | 79,941 | 85.0% 증가 |
| 실패율 | 42.28% | 0.00% | 용량 실패 제거 |
| 성공 처리율 | 90.00 req/s | 166.54 req/s | 85.0% 증가 |
| 성공 평균 응답 | 971.83ms | 732.63ms | 24.6% 감소 |
| 성공 p95 | 1.61s | 1.23s | 23.6% 감소 |

한도 60은 쿼리를 빠르게 만드는 최적화가 아니라 150VU 측정을 방해하던 업무 용량 상한을 제거한 변경이다.

### 8. 로그 및 Zipkin 분석

테스트 구간의 delivery, hub, user 오류와 delivery WARN, 배정 timeout, failed/DLQ enqueue는 모두 0건이다. AI도 전체 회복 구간까지 ERROR와 WARN이 0건이었다.

Slack에서는 실제 이벤트 기준 `SLACK_STREAM_PROCESSING_FAILED` 31건과 `SLACK_PENDING_RETRY_FAILED` 29건이 발생했다. 일반 소비자와 5분 주기의 pending 재처리기가 같은 consumer 이름으로 메시지를 동시에 처리하면서 `findByIdempotencyKey -> insert` 사이에 경쟁이 생겨 unique constraint가 충돌한 결과다.

충돌한 idempotency key 60개는 Slack DB에 모두 존재하며 전부 정상 상태인 `SKIPPED`였다. 최종 Slack 메시지 수도 generated 79,941건과 일치해 이번 Run의 유실은 없지만, pending 재처리기가 예외에도 ACK하는 구조는 별도 개선이 필요하다.

Zipkin에는 ramp-down 후반의 root trace 121개만 보존됐다. 이 표본에서 root p95는 75.31ms였고 가장 느린 외부 호출은 `UserClient /hubs/{hubId}`로 평균 5.14ms, p95 8.49ms였다. 전체 k6 p95보다 훨씬 짧은 후반 표본이므로 정량 대표값으로 쓰지 않으며, 외부 HTTP보다 DB/Hikari 지표를 병목 근거로 사용한다.

### 9. 결론

```text
WARN - 150VU 기능 성공, 동기 DB 포화와 Slack 후속 처리 경쟁 확인

- 79,941건 전부 성공, 실패율 0%, 166.54 req/s
- 평균 732.63ms, p95 1.23초, p99 1.54초
- DB·Outbox 증가량과 k6 성공 수 일치, failed/DLQ 0
- 담당자 최대 56건으로 한도 60 미소진
- 100VU보다 TPS 4.8% 감소, p95 60.8% 증가
- Data VM CPU 최대 99.82%, Hikari pending 최대 92
- JVM heap 최대 546.91MiB, GC 최대 99ms로 JVM 병목 근거 없음
- AI 종료 후 16분 11초, Slack 메시지 처리 종료 후 약 40분 6초
- Slack 멱등성 insert 경쟁 60건 발생, 최종 DB 유실은 0
```

현재 구성에서 150VU는 더 높은 처리량을 만들지 못하므로 Pool이나 VU를 추가로 늘릴 근거가 없다. 다음 개선 우선순위는 동기 경로의 DB 처리량보다 먼저 Slack pending 재처리 경쟁을 제거하고, Outbox와 Slack 소비 속도를 높여 약 40분의 후속 처리 회복 시간을 줄이는 것이다.
