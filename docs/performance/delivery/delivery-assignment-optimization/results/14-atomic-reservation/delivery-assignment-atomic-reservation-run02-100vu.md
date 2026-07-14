# Delivery Assignment Atomic Reservation Run 02 - 100VU 결과

## 1. 테스트 목적

배송 담당자 선택과 배정 수 증가를 단일 SQL로 원자화한 구현을 100VU 정식 부하에서 검증한다. `SKIP LOCKED` 단독 적용에서 발생했던 거짓 담당자 미배정(`DELIVERY_004`) 재발 여부와 DB 선점 시간, 하위 서비스 포화 지점을 함께 확인한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-14 00:05:38 ~ 00:13:42 KST |
| 배포 이미지 | `hublink-delivery-service:251b1d0aef5b0371b98a15563c19a13464040ad0` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | 담당자 1명 선택과 `active_assignment_count + 1`을 단일 SQL로 처리 |
| k6 로그 | `/tmp/hublink-k6-100vu-atomic-reservation-20260713T150538Z.log` |

실행 명령:

```bash
ENV_FILE=/tmp/hublink-no-env PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

Company, Hub, User, Delivery 서비스의 Eureka 등록과 health를 확인하고, 실제 Hub 경로 조회, User 담당자 조회, 배송 생성 1건이 각각 200/201로 응답한 뒤 baseline을 초기화했다.

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 287,063 |
| 전체 HTTP 요청률 | 597.99 req/s |
| 성공 요청 수 | 4,796 |
| 성공 처리량 | 9.99 req/s |
| 실패 요청 수 | 282,267 |
| 실패율 | 98.32% |
| checks 성공률 | 1.67% |
| 전체 평균 / median | 135.31ms / 91.39ms |
| 전체 p95 / p99 | 272.04ms / 1.75s |
| 전체 최대 | 5.32s |
| 성공 응답 평균 / median | 1.55s / 1.47s |
| 성공 응답 p90 / p95 | 2.64s / 2.92s |
| 성공 응답 최대 | 5.20s |

threshold 결과는 실패했다.

```text
checks: rate=1.67% <= 90%                                      FAIL
http_req_failed: rate=98.32% >= 10%                            FAIL
http_req_duration: p95=272.04ms < 3s, p99=1.75s < 6s           PASS
```

실패 282,267건은 모두 HTTP 502 `DELIVERY_011`(`사용자 서비스와 통신할 수 없습니다.`)였고 `DELIVERY_004`는 0건이었다. 첫 502는 00:06:16 KST, 마지막 502는 00:12:46 KST에 기록됐다.

전체 요청률과 전체 latency는 회로 차단 후 502가 빠르게 반환되면서 요청이 증폭된 결과다. 따라서 597.99 req/s와 전체 p95 272.04ms를 정상 처리 성능으로 해석할 수 없으며, 성공 건수 기준 처리량과 성공 응답 latency를 따로 봐야 한다.

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 38,396 | 4,796 |
| `p_delivery_route_histories` | 67,200 | 76,792 | 9,592 |
| `p_delivery_outboxes` | 33,600 | 38,396 | 4,796 |

감사 컬럼 `created_by=SYSTEM`으로 분리한 이번 테스트 생성분은 배송 4,796건과 경로 이력 9,592건이다. 배송의 `order_id`와 Outbox `event_key`를 조인한 결과 `delivery.create.succeed` 4,796건이 모두 `PUBLISHED`였으며, k6 성공 건수와 정확히 일치했다. 이번 테스트의 마지막 Outbox는 종료 약 1초 후인 00:13:43 KST에 발행됐다.

| 배정 유형 | 담당자 row | 활성 배정 합계 | 담당자별 최대 |
| --- | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 8,396 | 7 |
| `HUB_DELIVERY` | 750 | 6,596 | 10 |

baseline 대비 두 배정 유형의 합계가 각각 4,796씩 증가해 성공 배송마다 회사·허브 담당자 배정 수가 한 번씩 증가했다. 원자적 선점 이후 DB 반영 누락이나 중복 증가는 확인되지 않았다.

## 5. Prometheus / Grafana 지표

| 지표 | delivery-service | user-service |
| --- | ---: | ---: |
| CPU 최대 | 96.86% | 96.18% |
| JVM heap 최대 | 325.04 MiB | 1,464.43 MiB |
| GC pause 최대 | 51ms | 276ms |
| Hikari active 최대 | 30 | 10 |
| Hikari pending 최대 | 39 | 88 |

| 업무 지표 | 값 |
| --- | ---: |
| user-service 담당자 검색 RPS 최대 | 30.77 req/s |
| user-service 담당자 검색 1분 평균 지연 최대 | 1.98s |
| 담당자 검색 1회 응답 | 2,400명 / 675,886 bytes(약 660 KiB) |
| `company_delivery` 원자적 예약 평균 / 1분 평균 최대 / 단건 최대 | 13.29ms / 23.99ms / 143.81ms |
| `hub_delivery` 원자적 예약 평균 / 1분 평균 최대 / 단건 최대 | 16.85ms / 27.31ms / 279.22ms |

원자적 예약은 평균 13~17ms로 유지됐지만 user-service는 connection pool 10개를 모두 사용하고 pending이 88까지 쌓였다. 담당자 검색의 1분 평균이 최대 1.98초까지 늘어 delivery-service의 약 2초 client 제한과 맞물렸다.

동일한 두 Hub를 조회한 단일 응답은 담당자 2,400명과 약 660 KiB였다. 최대 30.77 req/s에서는 JSON 응답 본문만 초당 약 19.8 MiB를 생성하는 크기다. user-service heap이 약 1.46GiB, GC pause가 276ms까지 증가한 결과와 함께 보면 DB connection 대기뿐 아니라 대량 조회 결과의 객체 생성과 직렬화도 직접적인 부하 요인이다.

delivery-service Hikari pending도 39까지 증가했지만 원자적 예약 시간은 수십 ms 수준이었다. 이번 실패의 선행 병목은 배정 row lock이 아니라 user-service 담당자 조회 경로다.

## 6. 회로 차단기와 로그 분석

user-service 회로는 Prometheus scrape 기준 다음과 같이 반복해서 열렸다.

```text
00:06:53 ~ 00:08:53 KST OPEN
00:09:23 ~ 00:10:23 KST OPEN
00:10:53 ~ 00:12:23 KST OPEN
```

| 회로 지표 | user-service | hub-service |
| --- | ---: | ---: |
| failure rate 최대 | 100% | 0% |
| failed call 증가 | 540 | 0 |
| not permitted call 증가 | 281,731 | 0 |

회로가 열려 있을 때 user-service 호출이 즉시 거절되면서 k6가 같은 VU로 더 많은 요청을 생성했다. `SLEEP_SECONDS=0`인 closed workload 특성 때문에 실패 상태에서 요청률이 정상 처리 구간보다 더 커졌고, 이 값은 서비스 수용량이 아니다.

Loki에서는 `DELIVERY_004`, `DELIVERY_011`, delivery/user `ERROR` 로그가 0건이었다. 처리된 fallback 응답이 오류 로그를 남기지 않아 `DELIVERY_011` 건수는 k6 원본 로그와 회로 메트릭으로 확인했다.

delivery-service WARN 297건은 timeout 후 다른 user-service 인스턴스를 찾았지만 단일 인스턴스만 있어 같은 인스턴스를 다시 선택했다는 load balancer 경고였다. user-service WARN 2건은 Zipkin 전송 오류와 span 280개 유실이었다.

## 7. Zipkin 병목 분석

회로 포화 구간에는 조회 가능한 배송 trace가 없었다. user-service 로그에 span 유실이 명시되어 있어 해당 구간을 trace 전체 표본으로 분석할 수 없다.

하강 마지막 1분에는 배송 루트 trace 1,000건을 표본으로 조회했다.

| span | 표본 수 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| delivery-service 루트 | 1,000 | 539.11ms | 973.40ms | 1.33s |
| user-service `/internal/delivery-managers/search` | 1,000 | 404.75ms | 706.00ms | 974.63ms |
| hub-service `/internal/hub-routes/path` | 1,000 | 1.15ms | 1.48ms | 17.78ms |

회복 구간에서도 가장 긴 하위 호출은 user-service 담당자 검색이었고 Hub 경로 조회는 밀리초 수준이었다. Zipkin API 제한과 포화 구간 span 유실 때문에 이 1,000건은 전체 287,063건의 분포가 아니라 마지막 회복 구간 표본이다.

테스트 종료 후 3분 51초 시점에 Delivery/User health, Hub 관리자 조회와 담당자 검색이 모두 200으로 응답해 외부 호출 회복을 확인했다.

## 8. 이전 실행과 비교

| 구분 | 성공률 | 실패 | 성공 처리량 | 성공 p95 | `DELIVERY_004` | delivery pending 최대 | user pending 최대 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| OSIV 비활성화 100VU | 100.00% | 0 / 12,938 | 26.95 req/s | 4.75s | 0 | 72 | 미측정 |
| 원자적 예약 20VU | 99.55% | 1 / 223 | 11.07 req/s | 2.18s | 0 | 0 | 미측정 |
| 원자적 예약 100VU | 1.67% | 282,267 / 287,063 | 9.99 req/s | 2.92s | 0 | 39 | 88 |

기존 100VU 실행에서 평균 약 1초였던 `hub_delivery read_for_update`는 원자적 예약 평균 16.85ms로 크게 줄었다. `DELIVERY_004`도 재발하지 않아 담당자 선점 로직의 기능 회귀와 row lock 병목은 해소됐다.

반면 더 빨라진 배정 경로를 따라 동시 요청이 user-service로 진입하면서 담당자 검색의 pool과 heap이 먼저 포화됐다. 20VU에서 이미 관찰된 단일 User timeout이 100VU에서는 전체 실패를 유발하는 다음 병목으로 확대됐다.

## 9. 결론

```text
FAIL 원자적 선점은 정상이나 100VU에서 user-service 담당자 조회 포화

- 총 요청 287,063건
- 성공 4,796건 / DELIVERY_011 282,267건
- HTTP 실패율 98.32%
- checks 성공률 1.67%
- DELIVERY_004 0건
- DB 및 Outbox 성공 반영 4,796건 일치
- 원자적 예약 평균 company 13.29ms / hub 16.85ms
- user-service Hikari pending 최대 88 / heap 최대 1.46GiB
- user-service 회로 not permitted 281,731건
```

다음 최적화 대상은 user-service의 담당자 전체 목록 조회다. 요청마다 2,400명과 약 660 KiB를 반환하지 않도록 Hub와 담당자 유형 조건을 DB 쿼리에 적용하고, 배정에 필요한 ID·Hub·유형만 반환해야 한다. 변경 빈도가 낮은 후보 목록은 delivery-service에서 짧게 캐시할 수 있다. user-service가 단일 인스턴스인 동안에는 같은 인스턴스로 향하는 Feign retry가 부하를 증폭하지 않도록 재시도 정책도 조정해야 한다.

수정 후에는 50·60·80·100VU 순서로 포화 지점을 다시 찾고, 실패 시 요청률이 폭증하지 않도록 `constant-arrival-rate` 또는 요청 pacing을 사용해 동일 RPS에서 전후를 비교한다.
