# Delivery Assignment Candidate Index Run 02 - 100VU 인덱스 제거 결과

### 1. 테스트 목적

후보 정렬 인덱스를 제거하고 Pool 60·100VU 동일 조건으로 재측정해 인덱스 적용 Run과 정확한 A/B 비교를 수행한다.

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-19 01:27:57 ~ 01:35:59 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 조건 | Pool 60, 후보 인덱스 제거 |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| k6 로그 | `/tmp/hublink-k6-100vu-no-assignment-index-run01-20260718T162757.log` |

테스트 전 `idx_p_delivery_assignment_counts_candidate`를 제거하고 기존 PK와 `(assignment_type, manager_id)` 인덱스만 남은 상태를 확인했다. baseline SQL의 `TRUNCATE`로 이전 인덱스 실험에서 발생한 집계 테이블 팽창도 함께 제거했다.

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 37,570 |
| HTTP TPS | 78.27 req/s |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 1.04s / 1.05s |
| p90 / p95 / p99 | 1.53s / 1.75s / 2.29s |
| 최대 응답 시간 | 3.81s |
| max VU | 100 |

모든 threshold를 통과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 1.75s < 3s
p99: 2.29s < 6s
```

### 4. 인덱스 적용/제거 A/B 비교

두 Run 모두 Pool 60, 100VU, 8분, 동일 supplier와 receiver 조건이다.

| 지표 | 후보 인덱스 적용 | 후보 인덱스 제거 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 23,365 | 37,570 | 60.8% 증가 |
| HTTP TPS | 48.61 | 78.27 | 61.0% 증가 |
| 평균 응답 | 1.67s | 1.04s | 37.7% 감소 |
| p95 | 3.34s | 1.75s | 47.6% 감소 |
| p99 | 4.49s | 2.29s | 49.0% 감소 |
| 최대 응답 | 8.68s | 3.81s | 56.1% 감소 |
| Outbox 회복 | 5분 22초 | 7분 43초 | 생성량 60.8% 증가 영향 |
| threshold | p95 실패 | 전체 통과 | 회복 |

인덱스 제거 결과는 기존 Pool 30·100VU의 38,607건, 80.43 req/s, p95 1.80초와도 유사하다. Pool 차이를 제거한 비교에서도 후보 인덱스가 실제 성능을 악화시킨 사실을 확인했다.

### 5. DB / Outbox 결과

| 테이블 | baseline | 테스트 직후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 71,170 | 37,570 |
| `p_delivery_route_histories` | 67,200 | 142,340 | 75,140 |
| `p_delivery_outboxes` | 33,600 | 71,170 | 37,570 |

k6 성공 수와 배송·Outbox 증가 수가 일치하고 경로 이력은 요청당 2건씩 증가했다.

| 배정 유형 | 집계 row | 최종 배정 합계 | 최소 | 최대 | 30건 도달 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 41,170 | 22 | 27 | 0 |
| `HUB_DELIVERY` | 1,500 | 39,370 | 26 | 28 | 0 |

담당자 한도 소진은 없었다. 테스트 종료 25초 뒤 신규 Outbox 32,870건이 대기 중이었고, 37,570건 전체는 종료 7분 43초 뒤인 01:43:42 KST에 발행됐다. 인덱스 적용 Run보다 회복 시간은 2분 21초 길지만 생성량이 60.8% 많았고 최종 유실은 0건이다.

### 6. PostgreSQL 쓰기 비용 비교

| 지표 | 후보 인덱스 적용 | 후보 인덱스 제거 | 변화 |
| --- | ---: | ---: | ---: |
| 집계 row UPDATE | 46,594 | 75,140 | 요청 수 비례 증가 |
| HOT UPDATE | 0 | 67,038 | 89.2% HOT 복구 |
| 전체 DB WAL | 736.35MiB | 365.70MiB | 50.3% 감소 |
| 요청당 WAL | 32.27KiB | 9.97KiB | 69.1% 감소 |
| Data VM disk write 평균 | 9.84MiB/s | 7.89MiB/s | 19.8% 감소 |

인덱스 제거 Run은 요청이 60.8% 많았는데도 전체 WAL이 절반으로 감소했다. 갱신 컬럼을 후보 인덱스에서 제거하면서 HOT UPDATE가 복구된 효과다.

### 7. Grafana 관찰 결과

| 지표 | 후보 인덱스 적용 | 후보 인덱스 제거 |
| --- | ---: | ---: |
| Delivery API 1분 RPS 최대 | 86.03 | 90.20 |
| 서버 평균 지연 | 1,816.92ms | 1,044.29ms |
| delivery CPU 평균 / 최대 | 35.71% / 54.00% | 31.16% / 53.69% |
| Domain B system CPU 평균 / 최대 | 98.93% / 99.98% | 62.08% / 98.52% |
| Hikari active 평균 / 최대 | 48.30 / 60 | 51.82 / 60 |
| Hikari pending 평균 / 최대 | 12.06 / 37 | 26.85 / 42 |
| Hikari timeout | 0 | 0 |
| PostgreSQL connection 평균 / 최대 | 85.88 / 93 | 79.33 / 85 |
| PostgreSQL commit TPS 평균 / 최대 | 315.17 / 496.57 | 393.50 / 541.27 |
| Data VM CPU 평균 / 최대 | 58.63% / 75.63% | 94.51% / 100% |
| Data VM load1 평균 / 최대 | 3.78 / 10.31 | 40.63 / 58.63 |
| JVM heap 최대 | 330.75MiB | 330.14MiB |
| GC pause 최대 | 229ms | 119ms |

인덱스를 제거하면 DB CPU와 load가 크게 올라가지만 commit TPS와 API 처리량도 함께 증가한다. 현재 다음 병목은 2 vCPU Data VM의 CPU이며 JVM heap이나 Hikari timeout은 병목 근거가 없다.

| 처리 단계 | 후보 인덱스 적용 | 후보 인덱스 제거 |
| --- | ---: | ---: |
| 회사 담당자 선점 평균 | 132.47ms | 224.39ms |
| 허브 담당자 선점 평균 | 160.98ms | 332.40ms |
| 배송 저장 평균 | 15.736ms | 0.339ms |
| 경로 이력 저장 평균 | 17.220ms | 0.283ms |
| Outbox 저장 평균 | 75.748ms | 5.694ms |
| 전체 저장 트랜잭션 평균 | 114.937ms | 6.437ms |

후보 인덱스는 선점 조회 자체는 단축했다. 그러나 인덱스 유지 비용이 모든 DB 쓰기를 느리게 만들어 전체 요청 성능은 악화됐다.

### 8. 로그 및 Zipkin 분석

| Loki 지표 | 건수 |
| --- | ---: |
| delivery WARN | 0 |
| delivery ERROR 로그 줄 | 400 |
| `DELIVERY_PENDING_RETRY_FAILED` 이벤트 | 200 |
| `DELIVERY_004` / `011` / `013` | 0 / 0 / 0 |
| 선점 lock timeout | 0 |

ERROR 400줄은 baseline 초기화로 삭제된 과거 배송을 Redis 재시도 소비자가 조회하면서 남긴 이벤트 로그와 예외 스택 각 200줄이다. 이번 배송 생성 요청 실패와는 무관하다.

Zipkin 최근 배송 trace 990개에서 배송 root 평균은 105.36ms, p95는 200.25ms로 인덱스 적용 Run의 평균 150.60ms, p95 286.35ms보다 감소했다. User Hub 조회 p95는 9.70ms, Hub 경로 조회 p95는 1.38ms로 하위 HTTP 호출은 병목이 아니었다.

### 9. 결론

```text
PASS - 후보 인덱스 제거 후 100VU 처리량과 지연 회복

- 총 요청 37,570건, 실패율 0%
- TPS 48.61 -> 78.27 req/s, 61.0% 증가
- p95 3.34초 -> 1.75초, 47.6% 감소
- HOT UPDATE 0% -> 89.2%
- 요청당 WAL 32.27KiB -> 9.97KiB, 69.1% 감소
- DB 정합성 일치, 담당자 한도 소진 없음
- Outbox 종료 7분 43초 뒤 전체 발행, 유실 0건
- 하위 HTTP와 JVM 병목 근거 없음
- 다음 병목은 Data VM CPU 포화
```

`active_assignment_count`를 포함한 후보 정렬 인덱스는 폐기한다. 다음 최적화는 갱신 컬럼 인덱스가 아니라 1,500개 후보 UUID 조회와 정렬 범위를 줄이는 데이터 구조를 검토하고, 동일 부하에서 Data VM CPU와 처리량을 함께 비교한다.
