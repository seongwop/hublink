# Delivery Assignment DB VM Scale-up Run 06 - 100VU 안정성 재검증

### 1. 테스트 목적

4 vCPU Data VM과 배송 담당자 배정 한도 60이 반영된 상태에서 100VU를 다시 측정한다. 기존 정상 100VU와 재측정 결과 중 처리량뿐 아니라 tail latency, 자원 여유, DB 정합성을 함께 만족하는 Run을 대표 결과로 선정하고, 스케일업 전 2 vCPU 100VU와 비교한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| client sleep | `SLEEP_SECONDS=0` |
| DB pool | Delivery Hikari 최대 60 |
| 후보 인덱스 | 미적용 |
| 담당자 배정 한도 | 60건 |
| Data VM | `e2-standard-4`, 4 vCPU, 16GB |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |

재측정 전 배송 서비스 재기동으로 Redis 소비자를 복구했다. 첫 10VU 워밍업에서 재기동 직후 Hub 통신 502가 2건 발생했지만, 이어진 5VU 확인은 560/560건 성공했다. 이후 워밍업 데이터를 모두 소진하고 DB를 다시 baseline으로 복원했으며, Redis Stream은 소비자 그룹을 유지한 채 길이만 0으로 정리했다.

최종 시작 상태는 다음과 같다.

```text
p_deliveries                 33,600
p_delivery_route_histories   67,200
p_delivery_outboxes          33,600 (미발행 0)
COMPANY_DELIVERY             1,800 row / 합계 3,600
HUB_DELIVERY                 1,500 row / 합계 1,800
Redis requested/generated    XLEN 0 / pending 0 / lag 0
```

### 3. 100VU 반복 측정 결과

| 항목 | 후보 A | 후보 B |
| --- | ---: | ---: |
| 테스트 시간 | 2026-07-22 00:26:05 ~ 00:34:05 KST | 2026-07-22 12:18:19 ~ 12:26:19 KST |
| k6 로그 | `/var/tmp/hublink-load-final100/isolated100-20260721T152605.log` | `/var/tmp/hublink-load-final100/repeat100-20260722T031819.log` |
| 총 요청 / 성공 | 83,981 / 83,981 | 54,101 / 54,101 |
| 실패율 | 0.00% | 0.00% |
| HTTP TPS | 174.95 req/s | 112.71 req/s |
| 평균 / median | 464.83ms / 479.79ms | 721.90ms / 614.49ms |
| p90 / p95 / p99 | 698.37ms / 765ms / 909.75ms | 1.21s / 1.71s / 2.88s |
| 최대 응답 시간 | 2.40s | 4.51s |
| threshold | 전체 통과 | 전체 통과 |

두 Run 모두 단일 k6 프로세스로 실행됐고 HTTP 실패와 interrupted iteration은 없었다. 후보 A는 B보다 성공 처리량과 TPS가 55.2% 높고, 평균 응답은 35.6%, p95는 55.3%, p99는 68.4% 낮았다.

### 4. 대표 결과 선정

대표 결과는 **후보 A**로 선정한다.

| 지표 | 후보 A | 후보 B | 판단 |
| --- | ---: | ---: | --- |
| Domain B system CPU 평균 / 최대 | 74.99% / 83.02% | 99.01% / 99.98% | A가 자원 여유 확보 |
| Delivery process CPU 평균 / 최대 | 52.71% / 61.91% | 57.21% / 78.42% | A가 낮음 |
| AI process CPU 평균 / 최대 | 4.29% / 10.77% | 11.56% / 42.00% | A가 낮음 |
| Slack process CPU 평균 / 최대 | 2.37% / 3.62% | 6.41% / 12.28% | A가 낮음 |
| Data VM CPU 평균 / 최대 | 90.01% / 99.89% | 54.78% / 78.90% | B는 Domain B가 먼저 포화 |
| Data VM iowait 평균 / 최대 | 0.42% / 2.23% | 2.62% / 4.51% | A가 낮음 |
| Hikari active 평균 / 최대 | 49.88 / 60 | 48.67 / 60 | 유사 |
| Hikari pending 평균 / 최대 | 25.76 / 41 | 17.09 / 40 | B가 낮지만 처리량도 낮음 |
| JVM heap 최대 | 467.67MiB | 522.08MiB | A가 낮음 |
| GC pause 최대 | 52ms | 201ms | A가 낮음 |

후보 B는 기능적으로는 정상이나 배송 서비스 재기동 뒤의 cold 상태를 포함했다. 부하가 진행되며 누적 처리율이 계속 상승했고, Domain B system CPU가 거의 전 구간 포화됐다. Delivery뿐 아니라 AI·Slack·Order 프로세스 CPU도 A보다 높았다. 따라서 B는 재기동 직후 민감도를 보여주는 유효한 Run이지만, warm steady-state 성능 대표값으로는 A가 더 적합하다.

이 차이는 향후 비교 테스트에서 `서비스 재기동 여부`, `워밍업 시간과 요청 수`, `Outbox 및 Stream 완전 회복`을 반드시 동일하게 고정해야 한다는 근거이기도 하다.

### 5. 수정 전 100VU 비교

#### 5.1 배정 한도 수정 전 4 vCPU 비교

바로 이전 4 vCPU 100VU인 [Run 02](delivery-assignment-db-vm-scale-up-run02-100vu.md)는 담당자 한도 30에서 실행됐다. 허브 담당자 1,500명의 남은 수용량 43,200건을 모두 사용한 뒤 `DELIVERY_004`가 발생했으므로, 전체 요청률이 아니라 성공 처리량과 성공 응답 지연을 비교한다.

| 지표 | 한도 30 Run 02 | 한도 60 대표 A | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 43,200 | 83,981 | 94.4% 증가 |
| 실패 요청 / 실패율 | 11,958 / 21.67% | 0 / 0.00% | 용량 실패 제거 |
| 성공 처리율 | 90.00 req/s | 174.95 req/s | 94.4% 증가 |
| 성공 평균 응답 | 834.97ms | 464.83ms | 44.3% 감소 |
| 성공 p95 | 1.61s | 765ms | 52.5% 감소 |
| Data VM CPU 평균 / 최대 | 56.55% / 77.70% | 90.01% / 99.89% | 실제 처리량 증가로 상승 |
| Hikari pending 평균 / 최대 | 16.58 / 41 | 25.76 / 41 | 평균 상승 |

한도 증가는 쿼리 자체를 빠르게 만든 최적화가 아니라, 4 vCPU에서 증가한 처리량을 끝까지 측정할 수 있게 용량 제약을 제거한 변경이다. 대표 A에서는 허브 담당자 최대가 59로 한도 60에 도달하지 않았고 8분 전체가 실패율 0%로 끝났다.

#### 5.2 DB VM 스케일업 전 2 vCPU 비교

비교 기준은 [후보 인덱스 제거 2 vCPU Run](../17-assignment-candidate-index/delivery-assignment-candidate-index-run02-100vu-index-removed.md)이다. 두 Run 모두 Pool 60, 후보 인덱스 미적용, 100VU, 8분, 동일 supplier/receiver 조건이다.

| 지표 | 수정 전 2 vCPU | 대표 A 4 vCPU | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 37,570 | 83,981 | 123.5% 증가 |
| HTTP TPS | 78.27 req/s | 174.95 req/s | 123.5% 증가 |
| 평균 응답 | 1.04s | 464.83ms | 55.3% 감소 |
| median | 1.05s | 479.79ms | 54.3% 감소 |
| p90 | 1.53s | 698.37ms | 54.4% 감소 |
| p95 | 1.75s | 765ms | 56.3% 감소 |
| p99 | 2.29s | 909.75ms | 60.3% 감소 |
| 최대 응답 | 3.81s | 2.40s | 37.0% 감소 |
| 실패율 | 0.00% | 0.00% | 동일 |

| 인프라 지표 | 수정 전 2 vCPU | 대표 A 4 vCPU | 변화 |
| --- | ---: | ---: | ---: |
| Data VM CPU 평균 / 최대 | 94.51% / 100% | 90.01% / 99.89% | 평균 4.50%p 감소 |
| Data VM load1 평균 / 최대 | 40.63 / 58.63 | 38.54 / 52.90 | 감소 |
| Hikari active 평균 / 최대 | 51.82 / 60 | 49.88 / 60 | 평균 감소 |
| Hikari pending 평균 / 최대 | 26.85 / 42 | 25.76 / 41 | 평균·최대 감소 |
| PostgreSQL connection 평균 / 최대 | 79.33 / 85 | 89.58 / 92 | 증가 |
| PostgreSQL commit TPS 평균 / 최대 | 393.50 / 541.27 | 794.01 / 919.97 | 평균 101.8% 증가 |
| Delivery process CPU 평균 / 최대 | 31.16% / 53.69% | 52.71% / 61.91% | 처리량 증가에 따라 상승 |
| JVM heap 최대 | 330.14MiB | 467.67MiB | 증가, 메모리 압박 없음 |
| GC pause 최대 | 119ms | 52ms | 감소 |

4 vCPU에서는 두 배가 넘는 요청을 처리하면서도 Data VM 평균 CPU와 Hikari pending이 소폭 감소했고 PostgreSQL commit TPS는 약 두 배가 됐다. 다만 Data VM CPU 최대가 여전히 99.89%여서, 대표 A의 174.95 req/s 부근에서는 DB CPU가 다시 상한에 접근한다.

수정 전 Run의 허브 담당자 최대 배정은 28건으로 당시 30건 한도에 도달하지 않았다. 대표 A는 증가된 60건 한도에서 최대 59건까지 사용했다. 따라서 수정 전 Run의 성능 수치는 용량 거절로 오염되지 않았지만, 현재 60건 한도는 100VU 대표 A보다 높은 부하를 8분간 검증하기에는 여유가 4,219건뿐이다.

전체 8분의 123.5% 증가는 스케일업 이후 warm steady-state에서 관찰된 end-to-end 차이다. DB 스케일업 자체만의 보수적인 효과는 기존 Run 02의 용량 도달 전 동일 396초 비교인 성공 처리율 35.0% 증가와 서버 평균 지연 28.5% 감소를 기준으로 보는 것이 안전하다.

### 6. DB 정합성 및 비동기 처리

대표 A의 DB 증가량은 k6 성공 수와 정확히 일치했다.

| 테이블 | baseline | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 117,581 | 83,981 |
| `p_delivery_route_histories` | 67,200 | 235,162 | 167,962 |
| `p_delivery_outboxes` | 33,600 | 117,581 | 83,981 |

| 배정 유형 | row 수 | 최종 배정 합계 | 최대 |
| --- | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 87,581 | 51 |
| `HUB_DELIVERY` | 1,500 | 85,781 | 59 |

최종 확인에서 Outbox는 전부 `PUBLISHED`, requested/generated Stream은 각각 83,981건이며 AI·Delivery·Slack 그룹 pending과 lag는 모두 0, DLQ는 0이었다. Prometheus 기준 AI requested 최대 lag는 68,258건이고 종료 20분 뒤 0으로 회복했다. Outbox와 Slack의 정확한 0 도달 시각은 별도로 보존되지 않았으나 최종 유실은 없었다.

재측정 B도 배송 54,101건, 경로 이력 108,202건, Outbox 54,101건 증가가 k6 성공 수와 일치했다. 종료 직후 Outbox 미발행 40,106건, AI lag 26,231건, Slack lag 14,253건이 남았으며 DLQ는 0이었다. AI lag는 종료 7분 45초 뒤 0으로 회복했다. 종료 10분 18초 뒤에는 Outbox 미발행과 Delivery lag가 0이었고 generated Stream 54,101건 생성이 완료됐으며, Slack lag 24,434건만 정상 감소 중이었다.

### 7. 로그 및 Zipkin 분석

| 항목 | 후보 A | 후보 B |
| --- | ---: | ---: |
| k6 HTTP 실패 | 0 | 0 |
| delivery 실제 요청 오류 | 0 | 0 |
| hub ERROR | 0 | 0 |
| user ERROR | 0 | 0 |
| Zipkin span drop WARN | 1 | 2 |

후보 B 구간의 delivery ERROR 3건은 부하 요청이 아니라 측정 중 잘못 호출한 `/actuator/metrics/hikaricp.*` 운영 확인 요청이 만든 `NoResourceFoundException`이다. k6 요청과 DB 반영에는 영향이 없다. Zipkin 전송 WARN은 span 일부가 유실됐음을 의미하므로 trace를 전체 구간 정량 근거로 사용하지 않았다.

후보 B 종료 구간의 최근 trace 1,000개 중 배송 root 992개의 평균은 65.46ms, p95는 129.44ms였다. Hub 경로 조회 p95는 10.19ms, User Hub 조회 p95는 14.93ms였고, 담당자 검색 span은 캐시가 warm해 0건이었다. 이 표본은 ramp-down 후반의 낮은 부하 trace 중심이므로 k6 전체 p95 1.71초보다 작으며, 병목 판단은 Prometheus의 Domain B CPU와 Hikari 지표를 우선한다.

### 8. 결론

```text
PASS - 4 vCPU warm steady-state 100VU 대표 결과 확정

- 대표 A: 83,981건 전부 성공, 실패율 0%, 174.95 req/s
- 평균 464.83ms, p95 765ms, p99 909.75ms
- 배정 한도 30 Run 대비 용량 실패 11,958건 제거, 성공 처리량 94.4% 증가
- 수정 전 2 vCPU 대비 TPS 123.5% 증가, p95 56.3% 감소
- DB 증가량과 k6 성공 수 일치, 최종 Outbox/Stream 유실 및 DLQ 0
- PostgreSQL commit TPS 101.8% 증가, Data VM 평균 CPU는 4.50%p 감소
- Data VM CPU 최대 99.89%로 다음 고부하에서는 DB 포화 재발 가능
- 후보 B는 재기동 직후 Domain B CPU 99% 포화로 112.71 req/s에 머묾
- JVM 자체 병목은 없지만 warm-up 조건에 따른 처리량 편차가 큼
```

현재 100VU의 대표 성능은 후보 A로 사용한다. 다음 150VU 이상 비교 전에는 워밍업 절차를 고정하고, 60건 담당자 한도가 먼저 소진되지 않도록 예상 성공 수보다 큰 용량을 확보해야 한다. Outbox와 Slack 회복 시간은 동기 API와 별개로 다음 최적화 대상으로 관리한다.
