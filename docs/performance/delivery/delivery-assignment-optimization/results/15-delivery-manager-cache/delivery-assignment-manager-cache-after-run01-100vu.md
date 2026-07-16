# Delivery Assignment Manager Cache After Run 01 - 100VU 결과

## 1. 테스트 목적

Hub별 배송 담당자 Caffeine 캐시 적용 후 User Service 반복 조회와 자원 사용량이 감소하는지 확인한다. 동일한 100VU 조건에서 전체 배송 처리량과 다음 병목도 함께 측정한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-14 23:12:01 ~ 23:20:05 KST |
| 배포 이미지 | `hublink-delivery-service:571e7d2425baa3fd55214e1651f6901ee5e08162` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| k6 로그 | `/tmp/hublink-k6-100vu-cache-after-run01-20260714T141201Z.log` |

```bash
ENV_FILE=/tmp/hublink-no-env \
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql \
SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' \
RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' \
PRODUCT_NAME='k6-test-product' \
SLEEP_SECONDS=0 \
STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' \
./run-k6.sh delivery-create-logic-load.js
```

23:08:52 KST에 시작한 사전 실행은 Hub 회로가 열린 상태를 확인하고 76초에 중단했다. 회로 복구와 서비스 UP을 확인한 뒤 누적 metric 기준값을 기록하고 baseline SQL을 다시 실행했으므로 정식 실행의 DB와 metric 증분에는 사전 실행이 포함되지 않는다.

## 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 57,927 |
| 전체 HTTP 요청률 | 120.68 req/s |
| 성공 요청 수 | 20,700 |
| 성공 처리량 | 43.13 req/s |
| 실패 요청 수 | 37,227 |
| 실패율 | 64.26% |
| checks 성공률 | 35.73% |
| 전체 평균 / median | 673.88ms / 569.62ms |
| 전체 p90 / p95 / p99 | 1.25s / 1.49s / 2.01s |
| 전체 최대 | 4.42s |
| 성공 응답 평균 / median | 1.07s / 1.02s |
| 성공 응답 p90 / p95 | 1.59s / 1.80s |
| 성공 응답 최대 | 4.42s |
| max VU | 100 |

응답 시간 threshold는 통과했지만 `checks > 90%`와 `http_req_failed < 10%`는 실패했다.

| 오류 코드 | 건수 |
| --- | ---: |
| `DELIVERY_004` | 37,227 |
| `DELIVERY_011` | 0 |
| `DELIVERY_013` | 0 |

첫 실패는 23:16:18 KST로 시작 4분 17초 후 발생했고 마지막 실패는 테스트 종료 시점까지 이어졌다. 모든 실패는 HTTP 404 `DELIVERY_004`(`배정 가능한 배송 담당자가 없습니다.`)였다.

## 4. 캐시 효과

정식 실행 시작 직전 누적값을 기준으로 종료 후 값을 차감했다.

| 지표 | 값 |
| --- | ---: |
| cache get | 115,854 |
| cache hit | 115,830 |
| cache miss / put | 24 / 24 |
| cache hit ratio | 99.98% |
| cache size 최대 | 3 Hub |
| User 담당자 검색 실제 호출 | 24 |
| User 담당자 검색 최대 RPS | 0.10 req/s |
| User 담당자 검색 1분 평균 지연 최대 | 605.91ms |
| User 회로 not permitted | 0 |

요청마다 경로에 필요한 두 Hub를 캐시에서 조회하므로 cache get은 총 요청의 정확히 2배다. 60초 TTL이 만료될 때 서울, 부산, 인천 3개 key만 다시 적재됐으며 `sync=true`가 동시 miss를 병합했다. User Service의 `/internal/delivery-managers/search` 실제 호출 24건은 cache miss와 정확히 일치한다.

| 비교 지표 | 캐시 전 | 캐시 후 | 변화 |
| --- | ---: | ---: | ---: |
| User 담당자 검색 호출 | 4,144 | 24 | 99.42% 감소 |
| User 담당자 검색 최대 RPS | 28.90 | 0.10 | 99.65% 감소 |
| User CPU 최대 | 96.97% | 21.30% | 78.04% 감소 |
| User heap 최대 | 721.01 MiB | 160.74 MiB | 77.71% 감소 |
| User GC pause 최대 | 348ms | 96ms | 72.41% 감소 |
| User Hikari pending 최대 | 87 | 0 | 포화 해소 |
| User 회로 not permitted | 312,929 | 0 | 차단 해소 |
| 성공 요청 | 3,808 | 20,700 | 5.44배 |
| 성공 처리량 | 7.93 req/s | 43.13 req/s | 5.44배 |
| 성공 응답 p95 | 2.71s | 1.80s | 33.58% 감소 |

캐시 목표는 달성됐다. 기존 병목이던 대용량 담당자 목록 반복 조회, User CPU와 DB pool 포화, User 회로 차단이 모두 제거됐다.

## 5. DB / Outbox 정합성

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 54,300 | 20,700 |
| `p_delivery_route_histories` | 67,200 | 108,600 | 41,400 |
| `p_delivery_outboxes` | 33,600 | 54,300 | 20,700 |

k6 성공 20,700건과 배송, 경로 이력, 성공 Outbox 증가량이 정확히 일치했다. failed와 DLQ Outbox 증가는 없었다.

테스트 종료 직후 성공 Outbox 19,500건이 `PENDING`이었고, 마지막 Outbox는 23:25:25 KST에 `PUBLISHED`로 전환됐다. 전체 backlog 회복에는 테스트 종료 후 5분 21초가 걸렸다.

## 6. `DELIVERY_004` 원인

| Hub / 담당자 유형 | User 담당자 | 집계 row | 누락 row | 테스트 후 배정 수 |
| --- | ---: | ---: | ---: | ---: |
| 서울 / `HUB_DELIVERY` | 1,500 | 750 | 750 | 750명 모두 30건 |
| 부산 / `COMPANY_DELIVERY` | 900 | 900 | 0 | 13~17건 |
| 인천 / `COMPANY_DELIVERY` | 900 | 900 | 0 | 12~17건 |

서울 Hub 담당자 1,500명 중 기존 배정 이력이 있는 750명만 `p_delivery_assignment_counts` row를 가지고 있었다. 원자적 선점 SQL은 집계 테이블에 존재하는 row만 조회하므로 나머지 750명은 User Service 후보 목록에 있어도 선택되지 않는다.

정식 실행 전 서울 Hub 배정 합계는 1,800건이었다. 집계 row 750명의 최대 용량은 `750명 × 30건 = 22,500건`이고 남은 용량 `22,500 - 1,800 = 20,700건`은 k6 성공 수와 정확히 일치한다. 따라서 이번 `DELIVERY_004`는 동시성에 의한 거짓 미배정이 아니라 집계 row가 없는 담당자를 원자적 SQL이 처리하지 못한 기능 누락이다.

## 7. Prometheus / Grafana 분석

| 지표 | delivery-service | user-service | hub-service |
| --- | ---: | ---: | ---: |
| CPU 최대 | 66.57% | 21.30% | 14.14% |
| JVM heap 최대 | 325.33 MiB | 160.74 MiB | 143.26 MiB |
| GC pause 최대 | 128ms | 96ms | 22ms |
| Hikari active 최대 | 30 | 9 | 0 |
| Hikari pending 최대 | 72 | 0 | 0 |

| 처리 지표 | 값 |
| --- | ---: |
| Delivery API 1분 RPS 최대 | 181.13 req/s |
| Hub 경로 조회 1분 RPS 최대 | 180.60 req/s |
| Hub 경로 조회 1분 평균 지연 최대 | 1.81ms |
| User 담당자 검색 1분 RPS 최대 | 0.10 req/s |
| 회사 담당자 원자적 선점 1분 평균 지연 최대 | 117.55ms |
| Hub 담당자 원자적 선점 1분 평균 지연 최대 | 148.94ms |

User Service 포화는 해소됐지만 성공 처리량이 5.44배 증가하면서 delivery-service Hikari pending이 캐시 전 28에서 72로 상승했다. 다음 성능 병목은 User 조회가 아니라 delivery-service DB pool, 원자적 선점 SQL과 Outbox publisher 처리량이다.

정식 실행에서 Hub와 User 회로의 failed와 not permitted 증분은 모두 0건이다. 사전 중단 실행의 Hub 회로 수치는 시작 기준값에서 차감했다.

## 8. Loki / Zipkin 분석

| Loki 지표 | 건수 |
| --- | ---: |
| delivery-service WARN | 0 |
| delivery-service ERROR | 100 |
| user-service WARN / ERROR | 0 / 0 |
| hub-service WARN / ERROR | 2 / 0 |
| `DELIVERY_DOWNSTREAM_CALL_FAILED` | 0 |

delivery-service ERROR 100건은 기존 Redis Stream의 `DELIVERY_PENDING_RETRY_FAILED`로 이번 `/internal/deliveries` 요청 실패와 무관한 백그라운드 로그다. Hub WARN 2건은 Zipkin 전송 connection reset과 span drop 경고였다. `DELIVERY_004`는 예외 응답에서 별도 애플리케이션 로그를 남기지 않아 k6 원본 응답과 DB 집계로 확인했다.

높은 trace 유입과 Hub span drop으로 성공 구간 trace는 조회 시점에 남아 있지 않았다. 담당자 소진 후 실패 구간에서 최근 trace 1,000개를 분석했다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| delivery-service `/internal/deliveries` | 1,000 | 58.43ms | 112.84ms | 215.80ms |
| HubClient 경로 조회 | 1,000 | 4.98ms | 14.20ms | 54.28ms |
| hub-service `/internal/hub-routes/path` | 1,000 | 1.08ms | 1.55ms | 5.22ms |
| UserClient Hub 정보 조회 | 1,000 | 9.77ms | 22.84ms | 47.92ms |
| user-service `/internal/hubs/{hubId}` | 1,000 | 6.12ms | 14.05ms | 40.61ms |

실패 trace에는 `/internal/delivery-managers/search` span이 없었고 Hub 경로 조회도 최대 5.22ms로 짧았다. 외부 호출이 아니라 집계 row가 없는 담당자를 제외한 채 기존 750명의 최대 배정 수에 도달한 로컬 선점 단계에서 실패했다는 DB 분석과 일치한다.

## 9. 결론

```text
FAIL - 캐시 목표는 달성했지만 집계 row 누락으로 100VU 전체 흐름 실패

- cache hit 115,830건 / miss 24건 / hit ratio 99.98%
- User 담당자 검색 4,144건 -> 24건, 99.42% 감소
- User CPU 96.97% -> 21.30%, Hikari pending 87 -> 0
- 성공 3,808건 -> 20,700건, 성공 처리량 7.93 -> 43.13 req/s
- DELIVERY_004 37,227건 / DELIVERY_011 0건 / DELIVERY_013 0건
- DB와 Outbox 성공 반영 20,700건 일치
- Outbox backlog 회복 5분 21초
- 서울 Hub 담당자 1,500명 중 집계 row가 있는 750명만 사용
```

캐시는 유지할 가치가 충분하다. 다음 수정은 테스트 seed만 보완하는 방식이 아니라 신규 또는 미배정 담당자도 운영 중 안전하게 처리하도록 `p_delivery_assignment_counts` row를 초기화하거나, 원자적 선점 SQL이 집계 row가 없는 후보를 0건으로 취급해 생성과 증가를 함께 처리하도록 해야 한다.

수정 후 같은 100VU 조건으로 다시 실행해 `DELIVERY_004`가 사라지는지 확인하고, delivery-service Hikari pending 72와 Outbox 회복 5분 21초를 다음 병목 기준선으로 사용한다.
