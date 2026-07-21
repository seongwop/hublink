# Delivery Assignment DB VM Scale-up Run 04 - 150VU 재측정 결과

### 1. 테스트 목적

4 vCPU Data VM과 Pool 60 조건에서 150VU 부하를 실행해 실제 처리 한계와 병목을 확인한다. 서비스 재시작 없이 완전히 warm한 상태에서 측정한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-21 03:51:21 ~ 03:59:24 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=/opt/hublink/performance/k6/.env.db-scaleup-150vu ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 150 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| DB pool | Delivery Hikari maximum 60 |
| 후보 인덱스 | 미적용 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| k6 로그 | `/var/tmp/hublink-k6/db-scaleup-run04-150vu-repeat-20260721.log` |

테스트 전 delivery/hub/user health가 모두 `UP`이고, Outbox PENDING과 Redis 세 소비자 그룹의 pending·lag가 모두 0임을 확인했다. 서비스 재시작이나 별도 스모크 없이 이전 Run에서 충분히 warm된 상태를 유지했다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 74,848 |
| 성공 요청 수 | 43,200 |
| 실패 요청 수 | 31,648 |
| 전체 HTTP TPS | 155.92 req/s |
| 성공 구간 처리량 | 약 138.02 req/s |
| 실패율 | 42.28% |
| checks 성공률 | 57.71% |
| 전체 평균 / median | 782.28ms / 768.31ms |
| 전체 p90 / p95 / p99 | 1.30s / 1.48s / 1.93s |
| 성공 응답 평균 / p95 | 971.83ms / 1.61s |
| 최대 응답 시간 | 3.41s |
| max VU | 150 |

03:56:34 KST, 시작 약 313초 후 Hub 담당자 1,500명이 모두 30건에 도달했다. 이후 요청은 `DELIVERY_004`로 거절됐으므로 실패율 threshold는 통과하지 못했다. 지연 threshold는 통과했다.

```text
checks: 57.71% < 90%
http_req_failed: 42.28% > 10%
p95: 1.48s < 3s
p99: 1.93s < 6s
```

성공 구간 처리량은 최초 404 발생 전 성공 43,200건을 313초로 나눈 근삿값이다. 용량 소진 뒤 빠르게 반환된 404가 전체 평균과 백분위에 포함되므로, 지연 비교에는 성공 응답 전용 수치를 사용한다.

### 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 76,800 | 43,200 |
| `p_delivery_route_histories` | 67,200 | 153,600 | 86,400 |
| `p_delivery_outboxes` | 33,600 | 76,800 | 43,200 |

k6 성공 43,200건과 배송·Outbox 증가량이 정확히 일치하고 경로 이력은 요청당 2건 증가했다.

| 담당자 유형 | row 수 | 최종 배정 합계 | 최소 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 46,800 | 25 | 30 |
| `HUB_DELIVERY` | 1,500 | 45,000 | 30 | 30 |

Hub 담당자가 전부 30건에 도달해 총 성공 수를 43,200건으로 제한했다. 따라서 31,648건의 404는 서버 장애가 아니라 테스트 데이터 용량 소진이다.

### 5. 비동기 후속 처리

| 처리 단계 | 관찰 최대값 | 최종값 | 회복 시각 |
| --- | ---: | ---: | --- |
| Outbox PENDING | 43,320 | 0 | 04:09:37 KST, 종료 후 10분 13초 |
| AI requested lag | 22,403 | 0 | 04:06:47 KST, 종료 후 7분 23초 이내 |
| Delivery generated lag | 12,592 | 0 | 04:06:47 KST, 종료 후 7분 23초 이내 |
| Slack generated lag | 20,509 | 0 | 04:19:03 KST, 종료 후 19분 39초 |

Redis 세 소비자 그룹의 `entries-read` 기준값은 674,322였고 최종값은 모두 717,522로 43,200건 증가했다. 최종 Outbox 76,800건은 모두 `PUBLISHED`이며 세 소비자 그룹의 pending과 lag도 모두 0이다.

### 6. Grafana 관찰 결과

성공이 계속된 시작 후 313초 구간을 기준으로 비교했다.

| 지표 | 평균 / 최대 |
| --- | ---: |
| Data VM CPU | 69.21% / 91.62% |
| Data VM iowait | 2.40% / 7.84% |
| Data VM load1 | 13.51 / 29.43 |
| delivery-service CPU | 52.18% / 70.06% |
| Hikari active | 49.52 / 60 |
| Hikari pending | 62.19 / 91 |
| Hikari 연결 획득 평균 | 112.40ms |
| Hikari 연결 사용 평균 | 110.61ms |
| PostgreSQL connection | 72.00 / 82 |
| PostgreSQL active | 22.67 / 52 |
| PostgreSQL idle in transaction | 16.00 / 39 |
| PostgreSQL commit TPS | 587.44 / 776.93 |
| 서버 처리 평균 | 794.51ms |
| Delivery heap | 284.81MiB / 396.46MiB |
| GC pause 최대 | 188ms |
| Tomcat busy ratio | 60.41% / 75.50% |

| 주요 단계 | 성공 구간 평균 |
| --- | ---: |
| 회사 담당자 원자 선점 | 75.11ms |
| 허브 담당자 원자 선점 | 81.37ms |
| 배송 저장 트랜잭션 | 23.46ms |
| 배송 저장 | 2.69ms |
| Outbox enqueue | 17.70ms |

4 vCPU에서도 Hikari active가 최대 60이고 pending이 최대 91이므로 풀 대기는 남아 있다. 다만 Data VM CPU는 최대 91.62%로 2 vCPU의 100% 포화보다 여유가 생겼고, 처리량은 크게 증가했다. heap과 GC는 병목 근거가 없다.

### 7. 2 vCPU 비교

| 지표 | 2 vCPU 150VU | 4 vCPU Run 04 |
| --- | ---: | ---: |
| 동일 첫 313초 성공 수 | 약 22,627 | 43,200, 이후 용량 소진 |
| 동일 첫 313초 성공 처리량 | 약 72.29 req/s | 약 138.02 req/s |
| 전체 8분 성공 처리량 | 75.30 req/s | 90.00 req/s, 용량 제한 하한값 |
| 전체 성공 수 | 36,146 | 43,200 |
| 성공 응답 평균 | 1.62s | 971.83ms |
| 성공 p95 | 3.06s | 1.61s |
| Data VM CPU 평균 / 최대 | 83.77% / 100% | 69.21% / 91.62% |
| Hikari pending 평균 / 최대 | 69.14 / 92 | 62.19 / 91 |
| PostgreSQL commit TPS 평균 | 325.01 | 587.44 |
| heap 최대 | 491.19MiB | 396.46MiB |
| GC pause 최대 | 180ms | 188ms |

처음 313초의 같은 구간을 비교하면 Run 04의 성공 처리량은 2 vCPU보다 약 90.9% 높다. 전체 8분 기준으로도 용량에 막힌 성공 처리량 하한값이 90.00 req/s로 2 vCPU보다 19.5% 높다. 성공 응답 평균은 약 40.0%, p95는 약 47.4% 감소했다.

### 8. 로그 및 Zipkin 분석

Loki에서 테스트 구간의 배정 lock timeout, Delivery pending 재처리 실패, DLQ 이동, delivery/hub/user 오류는 모두 0건이었다. 회복 완료까지 확장한 구간에서도 Delivery Outbox·Stream, AI 생성·Stream, Slack 전송·Stream 실패 로그가 모두 0건이었다. k6의 404는 모두 `DELIVERY_004`이며 DB의 Hub 담당자 전원 30건 도달과 일치한다.

Zipkin API에는 성공 구간의 delivery-service trace가 보존되지 않아 정량 근거로 사용하지 않았다. k6 성공 응답, DB 증가량, Prometheus DB·Hikari 지표, Loki 오류 수를 주 근거로 사용했다.

### 9. 결론

```text
WARN - 4 vCPU 스케일업 효과는 확인됐지만 담당자 용량을 먼저 소진

- 동일 첫 313초 성공 처리량 72.29 -> 138.02 req/s, 약 90.9% 증가
- 전체 8분 성공 처리량도 75.30 -> 90.00 req/s, 용량 제한 상태에서 19.5% 증가
- 성공 응답 평균 1.62초 -> 0.97초, 약 40.0% 감소
- 성공 p95 3.06초 -> 1.61초, 약 47.4% 감소
- Data VM CPU 평균 90.10% -> 69.21%, 최대 100% -> 91.62%
- Hub 담당자 1,500명 전원이 30건에 도달해 43,200건 이후 404 발생
- DB·Outbox 증가량은 성공 43,200건과 일치
- Outbox 종료 후 10분 13초, 전체 Redis 소비자 종료 후 19분 39초에 회복
```

다음 150VU 비교부터는 테스트 중 용량이 소진되지 않도록 담당자 한도를 늘리거나 seed의 초기 배정 수를 낮춰야 한다. 현재 결과만으로도 4 vCPU 스케일업 효과는 확인되며, 다음 병목 후보는 Hikari pending과 느린 비동기 Slack 소비자다.
