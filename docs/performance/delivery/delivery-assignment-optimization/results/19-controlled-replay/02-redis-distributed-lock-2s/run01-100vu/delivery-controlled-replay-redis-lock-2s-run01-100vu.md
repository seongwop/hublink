# 배송 배정 통제 재현 02 - Redis 분산락 2초 100VU 결과

### 1. 테스트 목적

현재 고정 인프라와 테스트 데이터를 유지한 상태에서 Redis 분산락 대기 시간을 3초에서 2초로 줄였을 때 성공 처리량과 락 타임아웃이 어떻게 변하는지 확인했다.

비교 기준은 동일 환경에서 먼저 측정한 [Redis 분산락 3초 100VU 결과](../../01-redis-distributed-lock/run01-100vu/delivery-controlled-replay-redis-lock-run01-100vu.md)다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 테스트 시간 | 2026-07-27 12:37:19 ~ 12:45:21 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `3c68e7d849c163d7ff895c8b56d6b40b0d3f5986` |
| 배정 방식 | 회사·허브별 Redis 분산락 안에서 담당자 조회와 배송 저장 수행 |
| 실제 lock wait | **2초** |
| Delivery Hikari / 담당자 한도 | 60 / 60건 |
| 배정 집계 행 | HUB 1,500명, COMPANY 1,800명 |
| Delivery VM | 전용 `hublink-delivery-vm`, `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| Outbox | polling 1,000ms, publishable 부분 인덱스 없음 |

테스트 직전에 배송 33,600건, 경로 이력 67,200건, Outbox 33,600건으로 초기화했다. 구버전 이미지의 Outbox `@Lob` 매핑과 충돌하지 않도록 기존 Outbox는 모두 PUBLISHED 상태로 맞췄다.

Loki에서 모든 락 타임아웃 로그가 `waitMillis=2000`으로 남은 것을 확인했다. 따라서 Config Server 설정이 아니라 배포 이미지의 2초 설정이 실제로 적용된 결과다.

### 3. 판정

**FAIL**

- 성공 처리량: `13.16 req/s`
- 실패율: `49.29%`, 기준 10% 초과
- p95: `2.54초`, 기준 3초 통과
- 실패 6,153건은 모두 Redis 배정 락 2초 타임아웃
- HTTP 5xx, Hikari pending, Kafka 최종 lag는 모두 0

p95가 통과한 이유는 성능 개선이 아니라 실패 요청이 2초에 빠르게 종료됐기 때문이다. 총 요청 회전은 빨라졌지만 실제 배송 생성 수와 성공 처리량은 3초보다 감소했다.

### 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 12,481 |
| 성공 / 실패 | 6,328 / 6,153 |
| 총 HTTP TPS | 25.96 req/s |
| 성공 처리량 | 13.16 req/s |
| 실패율 | 49.29% |
| checks 성공률 | 50.70% |
| 평균 / median | 2.13s / 2.23s |
| p90 / p95 / p99 | 2.47s / 2.54s / 2.66s |
| 최대 응답 시간 | 2.94s |
| 성공 응답 평균 | 2.01s |

`checks > 90%`와 `http_req_failed < 10%`를 통과하지 못해 k6 종료 코드는 99다. `p95 < 3s`, `p99 < 6s`만 통과했다.

### 5. 같은 환경의 lock wait 3초 비교

| 지표 | 3초 | 2초 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 | 10,013 | 12,481 | +24.65% |
| 성공 요청 | 7,240 | 6,328 | -12.60% |
| 성공 처리량 | 15.07 req/s | 13.16 req/s | -12.66% |
| 실패율 | 27.69% | 49.29% | +21.60%p |
| 평균 응답 | 2.91s | 2.13s | -26.80% |
| p95 | 3.59s | 2.54s | -29.25% |
| 락 타임아웃 | 2,773 | 6,153 | +121.89% |

2초 설정은 빠른 실패를 늘려 총 요청 수와 표면적인 지연 수치를 개선했다. 그러나 성공 요청은 912건 줄었고 성공 처리량도 12.66% 감소했다. 현재 구조에서는 3초와 2초 모두 100VU를 감당하지 못하며, 2초는 처리량 최적화가 아니라 더 공격적인 부하 차단 정책에 가깝다.

### 6. 락 경합 분석

| 지표 | 결과 |
| --- | ---: |
| HTTP 409 / Loki 락 타임아웃 | 6,153 / 6,153 |
| 회사 2번 락 실패 | 3,081건 |
| 회사 3번 락 실패 | 3,072건 |
| HTTP 5xx | 0건 |
| 종료 후 Redis 배송 락 키 | 0개 |

모든 실패 로그의 `failedKey`는 회사별 Redis 락이었다. 두 receiver 회사의 실패가 50.07%와 49.93%로 고르게 분포해 특정 데이터 이상이 아니라 회사 단위 임계 구간의 직렬화가 원인임을 보여준다.

2초로 줄이면 대기 요청이 더 빨리 반환되어 VU가 다음 요청을 빨리 시작한다. 그 결과 총 TPS는 증가하지만 같은 락을 다시 경쟁하는 요청도 늘어나 성공 처리량은 오히려 감소했다.

### 7. DB와 Outbox 정합성

| 테이블 | 초기 | 최종 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 39,928 | 6,328 |
| `p_delivery_route_histories` | 67,200 | 79,856 | 12,656 |
| `p_delivery_outboxes` | 33,600 | 39,928 | 6,328 |

- 배송과 Outbox 증가량이 k6 성공 요청 6,328건과 일치한다.
- 경로 이력은 배송당 2건으로 정확히 증가했다.
- 최종 Outbox는 PUBLISHED 39,928건이며 PENDING·FAILED는 0건이다.
- 실패 요청은 DB와 Outbox에 부분 반영되지 않았다.

### 8. 자원과 복구 상태

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 56.98% | 90.41% |
| Delivery system CPU | 60.28% | 90.94% |
| Data VM CPU | 32.26% | 47.68% |
| Hikari active | 0.85 | 3 |
| Hikari pending | 0 | 0 |
| JVM heap | 691.16MiB | 1,128.43MiB |
| GC 평균 pause 1m | 56.90ms | 78.17ms |
| Tomcat busy ratio | 27.44% | 38.00% |
| PostgreSQL commit TPS | 204.57 | 259.32 |
| PostgreSQL cache hit | 99.869% | 99.869% |
| Outbox 발행 TPS | 12.40 msg/s | 16.87 msg/s |
| Outbox publishable backlog | 11.52건 | 24건 |

Delivery CPU는 순간 90%까지 올랐지만 Hikari pending이 전 구간 0이고 Data VM CPU도 최대 47.68%였다. Heap 최대치는 약 1.10GiB로 최대 Heap 1.94GiB의 약 56.8%이며 OOM, 재시작, Hikari timeout은 없었다. 따라서 실패 원인은 DB pool, Data VM CPU, JVM 메모리 부족이 아니라 Redis 임계 구간이다.

테스트 종료 후 확인 결과는 다음과 같다.

- Delivery health UP, 컨테이너 재시작 0회
- Outbox publishable backlog 0
- 관찰한 Delivery·Order Kafka consumer group lag 모두 0
- Redis 배송 락 키 0
- Hikari pending 0

### 9. Zipkin 보조 분석

테스트 종료 구간에서 조회한 최근 100개 trace 중 성공 배송 경로 84개를 확인했다.

| span | 표본 | 평균 | 최대 |
| --- | ---: | ---: | ---: |
| `POST /internal/deliveries` | 84 | 110.91ms | 134.49ms |
| 담당자 검색 | 84 | 42.96ms | 51.58ms |
| 허브 경로 조회 | 84 | 1.28ms | 2.93ms |
| 허브 조회 | 84 | 3.58ms | 6.69ms |

이 표본은 빠르게 성공한 일부 trace이므로 전체 응답 시간의 대표값으로 쓰지는 않는다. 다만 downstream 호출 자체가 수초 단위 실패를 만든 것이 아니라는 보조 근거로 사용할 수 있다.

### 10. 과거 lock wait 2초 결과와의 차이

[과거 Baseline Run 08](../../../01-baseline/delivery-assignment-baseline-run08-100vu-distributed-lock-wait-2s.md)은 같은 2초 설정에서 총 6,915건, 성공 6,910건, 실패율 0.07%, 성공 처리량 약 14.39 req/s였다.

과거에는 Hikari pool 10이 먼저 포화되어 pending이 평균 53.83, 최대 83까지 쌓였다. 반면 현재는 pool 60과 전용 Delivery VM으로 Hikari pending이 0이며 더 많은 요청이 동시에 Redis 락으로 진입한다. 담당자 후보 데이터 규모도 달라서 두 결과는 같은 timeout 값만 공유할 뿐 직접 A/B 비교 대상이 아니다.

현재 통제 재현 결과는 기존 DB pool 병목이 사라진 뒤 Redis 락 병목이 전면에 드러난 상태로 해석해야 한다.

### 11. 원본 패키지

`local-artifacts/`는 Git에서 제외하고 로컬에만 보관한다.

| 경로 | 내용 |
| --- | --- |
| `local-artifacts/raw/k6-delivery-replay-redis-lock-2s-run01-100vu.log` | k6 전체 출력 |
| `local-artifacts/raw/run.meta` | 시작 시각과 프로세스 정보 |
| `local-artifacts/raw/zipkin-delivery-service-test-window.json` | 테스트 구간 Zipkin 원본 |
| `local-artifacts/k6-summary.csv` | k6 핵심 수치 |
| `local-artifacts/db-summary.csv` | DB 초기·최종·증가량 |
| `local-artifacts/metrics-summary.csv` | Grafana 핵심 지표 |
| `local-artifacts/loki-summary.csv` | 락 실패와 오류 집계 |
| `local-artifacts/recovery-summary.csv` | Outbox·Kafka·Redis·Hikari 복구 |
| `local-artifacts/comparison-lock-wait-3s-vs-2s.csv` | 동일 환경 3초·2초 비교 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 전체 35개 패널·60개 target 통합 CSV |
| `local-artifacts/grafana/panels/` | target별 CSV 60개 |
| `local-artifacts/grafana/panel-manifest.csv` | query와 수집 상태 |
| `local-artifacts/grafana/dashboard-snapshot.json` | 테스트 당시 대시보드 정의 |

Grafana 수집 범위는 12:36:19부터 12:46:21 KST, step은 15초다. 35개 패널의 60개 target을 모두 수집했으며 오류 target은 0개다.

### 12. 결론

Redis 락 대기를 3초에서 2초로 줄이는 것은 현재 100VU 환경의 해결책이 아니다. 실패 응답이 빨라져 총 TPS와 p95는 좋아 보이지만 성공 처리량은 12.66% 감소하고 실패율은 49.29%까지 상승했다.

이 결과는 timeout 조정만으로는 긴 Redis 임계 구간의 직렬화 문제를 해결할 수 없음을 보여준다. 이후 단계에서는 같은 환경을 유지한 채 집계 테이블과 락 범위 축소, 최종적으로 원자적 선점과 `SKIP LOCKED`를 순서대로 재현해 성공 처리량과 실패율을 비교해야 한다.
