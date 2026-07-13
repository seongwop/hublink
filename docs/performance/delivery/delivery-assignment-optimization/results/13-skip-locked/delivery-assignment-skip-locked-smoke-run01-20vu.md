# Delivery Assignment SKIP LOCKED Smoke Run 01 - 20VU 결과

## 1. 테스트 목적

`FOR UPDATE SKIP LOCKED` 적용 배포본이 낮은 동시 부하에서도 배송 담당자를 정상 배정하는지 확인한다. 정식 성능 비교 전 기능 회귀를 찾기 위한 짧은 스모크 테스트다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-12 22:48:11 ~ 22:48:35 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 20초 |
| 부하 패턴 | 5초 ramp-up, 10초 유지, 5초 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | 배정 수 집계 조회에 `FOR UPDATE SKIP LOCKED` 적용 |

실행 명령:

```bash
ENV_FILE=/tmp/hublink-no-env PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"5s","target":20},{"duration":"10s","target":20},{"duration":"5s","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

배포 인프라의 정기 종료 이후 서비스가 내려가 있어 필요한 서비스를 먼저 기동했다. 기동 직후 발생한 연결 거부와 Hub 회로 차단기 워밍업 실패는 측정에서 제외하고, 직접 호출로 의존 서비스 정상 응답을 확인한 뒤 위 구간을 최종 측정했다.

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 271 |
| HTTP TPS | 13.50 req/s |
| 성공 요청 수 | 128 |
| 실패 요청 수 | 143 |
| 실패율 | 52.76% |
| checks 성공률 | 47.23% |
| 평균 응답 시간 | 1.13s |
| median | 1.19s |
| p90 | 1.60s |
| p95 | 1.68s |
| p99 | k6 요약 미출력 |
| 최대 응답 시간 | 2.06s |
| max VU | 20 |

`p95 < 3s`, `p99 < 6s`는 통과했지만 checks와 HTTP 실패율 threshold는 실패했다. 실패 143건은 모두 HTTP 404와 `DELIVERY_004`(`배정 가능한 배송 담당자가 없습니다.`)였다.

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 33,728 | 128 |
| `p_delivery_route_histories` | 67,200 | 67,456 | 256 |
| `p_delivery_outboxes` | 33,600 | 33,728 | 128 |

베이스라인 UUID 대역과 분리해 확인한 이번 테스트 생성분은 배송 128건과 `delivery.create.succeed` Outbox 128건이다. 최종 조회 시 128건 모두 `PUBLISHED`였으며 전체 Outbox 33,728건에도 `PENDING`이나 `FAILED`가 남지 않았다. DB 반영 건수는 k6의 HTTP 201 성공 128건과 일치한다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service CPU 최대 | 40.61% |
| JVM heap 최대 | 약 156.4 MiB |
| GC pause 최대 | 96ms |
| Hikari active 최대 | 1 |
| Hikari pending 최대 | 0 |
| Hikari max | 30 |
| `company_delivery` SKIP LOCKED 조회 평균 / 최대 | 24.41ms / 37.40ms |
| `hub_delivery` SKIP LOCKED 조회 평균 / 최대 | 23.20ms / 37.73ms |

DB 커넥션 포화나 GC 정지는 관찰되지 않았다. 낮은 Hikari 사용량에서도 기능 실패가 절반 이상 발생해 자원 부족보다 배정 후보 선택 로직의 동시성 문제가 우선이다.

## 6. 로그 및 원인 분석

최종 측정 구간의 Loki `WARN`, `ERROR`, `LazyInitializationException`, `DELIVERY_004` 검색 결과는 0건이었다. `DELIVERY_004`는 처리된 404 응답이며 현재 로그 레벨에서 별도 오류 로그를 남기지 않아, k6 응답과 Zipkin HTTP 상태로 확인했다.

현재 집계 조회는 요청이 받은 전체 담당자 후보를 `FOR UPDATE SKIP LOCKED`로 조회한다. 다른 트랜잭션이 같은 후보 행을 잠근 동안에는 해당 행들이 결과에서 빠지고, 이후 `assignmentCounts.containsKey(managerId)` 조건이 빠진 행의 담당자를 후보에서 제거한다. 동시 요청이 잠금을 기다리는 대신 정상 담당자를 없는 것으로 판단해 `DELIVERY_004`를 반환하는 구조다.

## 7. Zipkin 병목 분석

최종 구간의 배송 루트 트레이스는 271건으로 k6 요청 수와 일치했다.

| HTTP 상태 | 건수 | 루트 평균 | 루트 p95 |
| --- | ---: | ---: | ---: |
| 201 | 128 | 1.18s | 1.79s |
| 404 | 143 | 1.09s | 1.65s |

가장 느린 하위 호출은 user-service의 `POST /internal/delivery-managers/search`로 전체 평균 856.87ms, 최대 1.60s였다. Hub 경로 조회는 평균 6.88ms였다. 201과 404 모두 동일하게 담당자 검색까지 성공했으므로 404는 하위 서비스 실패가 아니라 그 이후 delivery-service 내부 배정 후보 처리에서 발생한 것으로 판단한다.

## 8. 결론

```text
FAIL 20VU에서도 SKIP LOCKED 적용으로 배송 담당자 미배정 회귀 발생

- 총 요청 271건
- 성공 128건 / 실패 143건
- HTTP 실패율 52.76%
- DB 및 Outbox 성공 반영 128건
- Hikari pending 최대 0
- 핵심 원인: 잠긴 집계 행을 건너뛴 결과를 실제 담당자 부재로 처리
```

현재 구현으로는 100VU 정식 비교 테스트를 진행하면 안 된다. 후보 전체를 한 번에 잠그고 건너뛰는 방식 대신 한 명을 원자적으로 선점하거나, 건너뛴 후보와 실제 미존재 후보를 구분하도록 배정 전략을 수정한 뒤 동일한 20VU 스모크부터 재검증해야 한다.
