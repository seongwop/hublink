# Delivery Assignment Candidate Index Run 01 - 100VU 결과

## 1. 테스트 목적

원자적 담당자 선점 쿼리의 후보 조회와 정렬을 다음 복합 인덱스로 대체했을 때 실제 부하에서도 처리량과 지연이 개선되는지 확인한다.

```sql
CREATE INDEX idx_p_delivery_assignment_counts_candidate
ON delivery_service.p_delivery_assignment_counts (
    assignment_type,
    active_assignment_count,
    manager_id
);
```

단건 `EXPLAIN ANALYZE`뿐 아니라 갱신 컬럼인 `active_assignment_count`의 인덱스 유지 비용, HOT UPDATE, WAL, Hikari 대기와 전체 배송 생성 처리량을 함께 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-19 00:43:01 ~ 00:51:02 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| delivery Hikari pool | maximum 60 |
| 후보 인덱스 | `idx_p_delivery_assignment_counts_candidate` |
| k6 로그 | `/tmp/hublink-k6-100vu-assignment-index-run01-20260719T004257.log` |

공식 실행 직전 baseline을 초기화하고 delivery, hub, company, user 서비스의 health와 1VU 스모크 테스트를 확인했다. 서비스 기동 전 실행된 예비 부하와 1VU 스모크 데이터는 다시 초기화해 공식 결과에서 제외했다.

## 3. 단건 실행 계획

동일한 선점 쿼리를 warm 상태에서 비교한 결과다.

| 지표 | 인덱스 적용 전 | 인덱스 적용 후 | 변화 |
| --- | ---: | ---: | ---: |
| 실행 계획 | Seq Scan + Sort | Index Scan, Sort 제거 | 계획 변경 |
| Execution Time | 1.823ms | 중앙값 0.236ms | 87.1% 감소 |
| shared buffer hit | 341 | 17 | 95.0% 감소 |
| WAL bytes | 125B | 397B | 217.6% 증가 |

단건 조회는 빨라졌지만 갱신 대상 컬럼이 인덱스에 포함되면서 쓰기 비용은 증가했다.

## 4. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 23,365 |
| HTTP TPS | 48.61 req/s |
| 성공 요청 수 | 23,365 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 1.67s / 1.63s |
| p90 / p95 / p99 | 2.91s / 3.34s / 4.49s |
| 최대 응답 시간 | 8.68s |
| max VU | 100 |

기능 요청은 모두 성공했고 p99 기준도 통과했다. p95는 목표 3초를 340ms 초과해 k6가 종료 코드 99를 반환했다.

## 5. DB 정합성과 Outbox 회복

| 테이블 | baseline | 테스트 직후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 56,965 | 23,365 |
| `p_delivery_route_histories` | 67,200 | 113,930 | 46,730 |
| `p_delivery_outboxes` | 33,600 | 56,965 | 23,365 |

k6 성공 수, 배송, Outbox 증가 수가 일치하고 경로 이력은 요청당 2건씩 증가했다.

| 배정 유형 | 집계 row | 최종 배정 합계 | 최소 | 최대 | 30건 도달 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 26,965 | 14 | 17 | 0 |
| `HUB_DELIVERY` | 1,500 | 25,165 | 16 | 18 | 0 |

담당자 한도 소진은 없었다. 테스트 종료 3분 11초 뒤 신규 Outbox 10,366건이 대기 중이었고, 23,365건 전체는 종료 5분 22초 뒤인 00:56:24 KST에 발행됐다.

## 6. 인덱스 갱신 비용

공식 구간 전후 PostgreSQL 통계 차이다.

| 지표 | 증가량 |
| --- | ---: |
| 집계 row UPDATE | 46,594 |
| HOT UPDATE | 0 |
| 후보 인덱스 scan | 46,594 |
| 후보 인덱스 tuple read | 6,156,702 |
| 후보 인덱스 tuple fetch | 5,209,541 |
| 전체 DB WAL records | 1,179,806 |
| 전체 DB WAL FPI | 98,710 |
| 전체 DB WAL bytes | 736.35MiB |

요청당 회사와 허브 배정으로 집계 row가 약 2회 갱신됐다. 인덱스가 갱신 컬럼을 포함해 46,594회 모두 non-HOT UPDATE가 됐다. 후보 인덱스는 선점 1회당 평균 132.14개 tuple을 읽고 111.81개를 fetch했다. 동시 요청이 동일한 낮은 배정 수 구간에 집중되면서 `SKIP LOCKED`가 잠긴 앞쪽 후보를 건너뛰기 위해 다수의 인덱스 항목을 확인한 결과다.

후보 인덱스 크기는 테스트 전 224KiB에서 테스트 후 2.36MiB로 약 10.8배 증가했다. 전체 DB WAL은 배송과 Outbox 저장도 포함한 값이므로 인덱스 단독 발생량으로 해석하지 않는다. 다만 HOT UPDATE 0건과 인덱스 크기 증가는 후보 인덱스의 직접적인 쓰기 증폭 근거다.

## 7. Grafana 비교

저장된 인덱스 적용 전 100VU Run과 같은 PromQL로 다시 계산했다. 이전 Run은 Pool 30, 이번 Run은 Pool 60이므로 완전히 동일한 A/B는 아니며 Pool 차이를 함께 표시한다.

| 지표 | 인덱스 전 100VU, Pool 30 | 인덱스 후 100VU, Pool 60 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 38,607 | 23,365 | 39.5% 감소 |
| HTTP TPS | 80.43 | 48.61 | 39.6% 감소 |
| 평균 응답 | 1.01s | 1.67s | 65.3% 증가 |
| p95 | 1.80s | 3.34s | 85.6% 증가 |
| p99 | 2.37s | 4.49s | 89.5% 증가 |
| 서버 평균 지연 | 1,006ms | 1,817ms | 80.6% 증가 |
| delivery CPU 평균 / 최대 | 26.11% / 31.30% | 35.71% / 54.00% | 증가 |
| Hikari active 평균 / 최대 | 27.09 / 30 | 48.30 / 60 | 증가 |
| Hikari pending 평균 / 최대 | 50.79 / 71 | 12.06 / 37 | 감소 |
| PostgreSQL connection 평균 / 최대 | 57 / 61 | 85.88 / 93 | 증가 |
| PostgreSQL commit TPS 평균 | 399.04 | 315.17 | 21.0% 감소 |
| Data VM CPU 평균 / 최대 | 93.78% / 100% | 58.63% / 75.63% | 감소 |
| Data VM disk write 평균 | 7.90MiB/s | 9.84MiB/s | 24.7% 증가 |
| JVM heap 최대 | 317.77MiB | 330.75MiB | 4.1% 증가 |
| GC pause 최대 | 129ms | 229ms | 증가 |

Pool 60으로 Hikari 대기는 줄었지만 처리량과 지연은 악화됐다. Data VM CPU 감소는 같은 시간에 처리한 요청 수가 39.5% 줄어든 결과이므로 용량 개선으로 해석할 수 없다.

배송 생성 단계별 평균 시간은 DB 쓰기 경로의 악화를 직접 보여준다.

| 단계 | 인덱스 전 | 인덱스 후 |
| --- | ---: | ---: |
| 배송 저장 | 0.197ms | 15.736ms |
| 경로 이력 저장 | 0.148ms | 17.220ms |
| Outbox 저장 | 3.433ms | 75.748ms |
| 전체 저장 트랜잭션 | 3.847ms | 114.937ms |

원자적 선점 자체의 평균 시간도 회사 117.72ms에서 132.47ms, 허브 158.51ms에서 160.98ms로 단건 실행 계획만큼 개선되지 않았다.

## 8. Loki / Zipkin 분석

| Loki 지표 | 건수 |
| --- | ---: |
| delivery WARN | 2 |
| delivery ERROR 로그 줄 | 200 |
| `DELIVERY_PENDING_RETRY_FAILED` 이벤트 | 100 |
| `DELIVERY_004` | 0 |
| `DELIVERY_011` | 0 |
| `DELIVERY_013` | 0 |
| 선점 lock timeout | 0 |

ERROR 200줄은 초기화로 삭제된 과거 배송을 Redis 재시도 소비자가 조회하면서 남긴 이벤트 로그 100줄과 `배송을 찾을 수 없습니다` 예외 100줄이다. 이번 배송 생성 요청 실패와는 무관하지만 테스트 격리를 위해 Redis 재시도 데이터 정리가 필요하다.

Zipkin의 종료 구간 최근 배송 trace 989개에서는 배송 root 평균 150.60ms, p95 286.35ms, 최대 1.11s였다. User Hub 조회는 평균 5.31ms, Hub 경로 조회는 평균 1.19ms로 하위 HTTP 호출은 병목이 아니었다.

## 9. 결론

```text
FAIL - 후보 정렬 인덱스는 단건 조회를 개선했지만 100VU 전체 성능을 악화

- 기능 성공 23,365건, 실패율 0%
- TPS 80.43 -> 48.61 req/s, 39.6% 감소
- p95 1.80초 -> 3.34초, 목표 3초 초과
- 선점 UPDATE 46,594회 모두 non-HOT
- 후보 인덱스 224KiB -> 2.36MiB, 약 10.8배 증가
- 전체 저장 트랜잭션 평균 3.85ms -> 114.94ms
- 신규 Outbox 종료 5분 22초 뒤 전체 발행
```

후속 [Run 02](./delivery-assignment-candidate-index-run02-100vu-index-removed.md)에서 후보 인덱스를 제거한 Pool 60·100VU를 동일하게 실행했다. 처리량은 48.61에서 78.27 req/s로 증가하고 p95는 3.34초에서 1.75초로 감소해 후보 인덱스의 악영향을 확정했다. 이후 조회 최적화가 더 필요하면 매 배정마다 변하는 `active_assignment_count`를 정렬 인덱스에 포함하지 않는 구조나 후보를 작은 범위로 먼저 분산하는 방식을 별도 실험한다.
