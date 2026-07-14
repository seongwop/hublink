# Delivery Assignment Manager Cache Before Run 01 - 100VU 결과

## 1. 테스트 목적

배송 담당자 목록의 로컬 캐시를 배포하기 전에 같은 조건의 기준선을 다시 측정했다. 전체 배송 생성 성능을 확인하되, 캐시 효과를 직접 비교할 수 있도록 다음 지표를 우선 수집했다.

- user-service `POST /internal/delivery-managers/search` 호출 수, RPS, 지연
- 담당자 조회 1회의 인원 수와 응답 크기
- user-service CPU, heap, GC, Hikari active/pending
- delivery-service의 user-service 회로 차단 호출 수
- k6 성공 건수와 DB, Outbox 반영 정합성
- Zipkin의 담당자 조회 span 지연

이번 결과는 캐시 미적용 기준선이다. 로컬 작업 중인 `deliveryManagersByHub` Caffeine 캐시는 아직 배포하지 않았다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-14 14:51:34 ~ 14:59:37 KST |
| 배포 이미지 | `hublink-delivery-service:251b1d0aef5b0371b98a15563c19a13464040ad0` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| k6 로그 | `/tmp/hublink-k6-100vu-cache-before-20260714T055134Z.log` |

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

VM 동시 기동 직후 Config Server가 아직 준비되지 않아 company-service가 기본 Eureka 주소로 시작된 상태를 사전 점검에서 발견했다. company-service만 재시작하고 Eureka 등록과 Hub 경로 조회 200 응답을 확인한 뒤 baseline SQL을 다시 실행했다. 그 전에 중단한 38초 실행은 이 결과에서 제외했다.

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 317,659 |
| 전체 HTTP 요청률 | 661.78 req/s |
| 성공 요청 수 | 3,808 |
| 성공 처리량 | 7.93 req/s |
| 실패 요청 수 | 313,851 |
| 실패율 | 98.80% |
| 전체 평균 / median | 122.36ms / 82.75ms |
| 전체 p90 / p95 / p99 | 158.93ms / 268.18ms / 1.59s |
| 전체 최대 | 5.22s |
| 성공 응답 평균 / median | 1.47s / 1.46s |
| 성공 응답 p90 / p95 | 2.45s / 2.71s |
| 성공 응답 최대 | 5.22s |

`checks`와 `http_req_failed` threshold는 실패했다. 전체 p95와 p99가 threshold를 통과한 것은 회로 차단 후 즉시 반환된 502가 대부분을 차지했기 때문이며 정상 처리 성능이 좋다는 의미가 아니다.

실패 313,851건은 모두 HTTP 502 `DELIVERY_011`이었다. `DELIVERY_004`와 `DELIVERY_013`은 0건이다. 첫 실패는 14:52:07 KST로 시작 33초 후, 약 55 VU 구간에서 발생했다.

## 4. 캐시 적용 전 담당자 조회 비용

| 지표 | 값 |
| --- | ---: |
| user-service 담당자 검색 완료 호출 수 | 4,144회 |
| 담당자 검색 1분 RPS 최대 | 28.90 req/s |
| 담당자 검색 1분 평균 지연 최대 | 3.39s |
| 대표 조회 응답 | 2,400명 |
| 대표 조회 응답 크기 | 675,886 bytes, 약 660 KiB |
| 무부하 대표 조회 지연 | 52.30ms |
| 반복 응답 전송량 추정 | 약 2.61 GiB |

대표 조회는 실제 배송 경로와 같은 서울 Hub와 부산 Hub 조합으로 테스트 종료 후 직접 측정했다. receiver 경로는 부산 또는 인천이며 두 목적지 모두 회사 배송 담당자 900명, 서울 Hub 담당자 1,500명을 사용하므로 호출당 2,400명 조건은 같다. 2.61 GiB는 4,144회가 대표 응답과 같은 크기라고 가정한 추정치다.

서버에서 완료된 담당자 검색은 배송 성공보다 336회 많았다. user-service에서는 처리를 마쳤지만 delivery-service의 client 제한을 넘겨 이미 timeout 처리된 늦은 응답이 포함된 것으로 해석한다.

## 5. Prometheus / Grafana 분석

| 지표 | delivery-service | user-service |
| --- | ---: | ---: |
| CPU 최대 | 97.49% | 96.97% |
| JVM heap 최대 | 320.21 MiB | 721.01 MiB |
| GC pause 최대 | 48ms | 348ms |
| Hikari active 최대 | 30 | 10 |
| Hikari pending 최대 | 28 | 87 |

user-service는 CPU와 DB pool이 모두 포화됐다. 특히 Hikari active가 최대치 10에 도달한 상태에서 pending이 87까지 증가했다. 담당자 2,400명 조회와 DTO 생성·직렬화를 요청마다 반복한 비용이 DB connection 대기와 CPU 사용량에 함께 반영됐다.

| user-service 회로 지표 | 테스트 구간 증가 |
| --- | ---: |
| successful call | 8,183 |
| failed call | 922 |
| not permitted call | 312,929 |

`user-service` 회로는 담당자 목록 조회뿐 아니라 Hub 담당자 조회도 함께 사용하므로 successful과 failed 값은 담당자 검색 호출 수와 일대일로 대응하지 않는다. 다만 not permitted 312,929건은 회로가 열린 뒤 대부분의 배송 요청이 user-service 호출 전에 빠르게 거절됐음을 보여준다.

원자적 담당자 예약 SQL은 관측 구간 평균 company 10.00ms, hub 12.39ms였다. 기존에 최적화한 DB 선점 구간보다 user-service 담당자 목록 조회가 훨씬 큰 병목이라는 판단은 유지된다.

## 6. DB / Outbox 정합성

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 37,408 | 3,808 |
| `p_delivery_route_histories` | 67,200 | 74,816 | 7,616 |
| `p_delivery_outboxes` | 33,600 | 37,408 | 3,808 |

k6 성공 3,808건과 배송, 경로 이력, Outbox 증가량이 정확히 일치했다. 새 Outbox 3,808건은 모두 `delivery.create.succeed`, `PUBLISHED` 상태였고 마지막 이벤트는 테스트 종료 약 1초 뒤인 14:59:38 KST에 발행됐다.

| 배정 유형 | 담당자 row | 활성 배정 합계 | 담당자별 최대 |
| --- | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 7,408 | 6 |
| `HUB_DELIVERY` | 750 | 5,608 | 9 |

두 배정 유형 모두 baseline 대비 정확히 3,808씩 증가했다. 원자적 선점 결과의 누락이나 중복 증가는 관측되지 않았다.

## 7. Loki / Zipkin 분석

| Loki 지표 | 건수 |
| --- | ---: |
| delivery-service WARN | 662 |
| delivery-service ERROR | 0 |
| user-service WARN | 2 |
| user-service ERROR | 0 |

delivery-service WARN 대부분인 660건은 timeout 후 이전 인스턴스를 제외하면 선택할 다른 user-service 인스턴스가 없어 같은 단일 인스턴스를 다시 사용했다는 load balancer 경고였다. 나머지 WARN과 user-service WARN은 Zipkin 전송 오류로 delivery-service span 1,213개, user-service span 184개가 유실됐다는 내용이다. `DELIVERY_011`은 fallback 응답에서 별도 애플리케이션 로그를 남기지 않아 k6 응답과 회로 메트릭으로 확인했다.

Zipkin에서 user-service 담당자 검색 span이 포함된 trace 1,000개를 표본으로 집계했다.

| span | 표본 수 | 평균 | p95 | p99 | 최대 |
| --- | ---: | ---: | ---: | ---: | ---: |
| delivery-service `/internal/deliveries` | 1,000 | 581.03ms | 1.11s | 1.32s | 1.56s |
| user-service `/internal/delivery-managers/search` | 1,000 | 426.12ms | 758.72ms | 901.80ms | 993.39ms |
| hub-service `/internal/hub-routes/path` | 1,000 | 1.02ms | 1.28ms | 1.67ms | 9.65ms |

완료 trace에서 가장 느린 하위 호출은 user-service 담당자 검색이었다. Zipkin 표본은 전송 실패로 일부 span이 유실됐고 완료된 trace 위주이므로, 포화 구간 전체를 나타내는 값은 Prometheus의 1분 평균 지연 최대 3.39초를 우선 기준으로 사용한다.

## 8. 이전 100VU 반복 실행과 비교

| 지표 | Atomic Run 03 | Cache Before Run 01 |
| --- | ---: | ---: |
| 성공 요청 | 12,035 | 3,808 |
| 성공 처리량 | 25.05 req/s | 7.93 req/s |
| 실패율 | 90.89% | 98.80% |
| 첫 실패 시점 | 약 55 VU | 약 55 VU |
| user 검색 RPS 최대 | 33.73 req/s | 28.90 req/s |
| user 검색 1분 평균 지연 최대 | 1.60s | 3.39s |
| user CPU 최대 | 96.73% | 96.97% |
| user Hikari pending 최대 | 94 | 87 |
| 회로 not permitted | 119,675 | 312,929 |

회로가 열린 시간에 따라 성공 건수와 전체 실패 규모의 편차는 크지만, 두 실행 모두 약 55 VU부터 실패가 시작됐고 user-service CPU 약 97%, Hikari pending 87 이상이 재현됐다. 담당자 목록 조회 포화가 일회성 현상이 아니라 현재 구조의 반복 가능한 용량 한계라는 근거로 본다.

## 9. 캐시 적용 후 비교 계획

현재 로컬 구현은 Hub ID를 키로 사용하는 `deliveryManagersByHub` Caffeine 캐시다.

- TTL: 60초
- 최대 key: 32개 Hub
- 현재 seed에서 사용하는 key: 서울, 부산, 인천 3개
- 동시 miss 병합: `@Cacheable(sync = true)`
- 통계 기록: Caffeine `recordStats()`

동일한 8분 테스트에서 캐시가 계속 사용되고 eviction이 없다면, user-service 실제 load는 대략 `3개 Hub × 약 8회 TTL 구간`, 즉 20여 회 수준이 예상된다. 실제 결과는 추정치로 판정하지 않고 다음 지표로 검증한다.

| 비교 지표 | 캐시 전 기준 | 캐시 후 확인 기준 |
| --- | ---: | --- |
| user 검색 완료 호출 | 4,144회 | 실제 호출 수와 감소율, 목표 99% 이상 감소 |
| user 검색 RPS 최대 | 28.90 req/s | TTL 경계의 순간 load 포함 확인 |
| cache load | 미적용 | `cache_loads_total` 증가량 |
| cache hit / miss | 미적용 | `cache_gets_total`과 hit ratio |
| cache size | 미적용 | 최대 3개 key인지 확인 |
| user CPU 최대 | 96.97% | 캐시 전 대비 감소율 |
| user Hikari pending 최대 | 87 | 0 또는 유의미한 감소 여부 |
| 회로 not permitted | 312,929 | 0 또는 유의미한 감소 여부 |
| 성공 처리량 | 7.93 req/s | 동일 100VU에서 증가율 |

캐시 후에는 최초 cold load, 60초 TTL 재적재 구간, 그 사이 warm 구간을 분리해 본다. Zipkin에서는 warm trace에 `/internal/delivery-managers/search` span이 사라지는지, Prometheus에서는 실제 user 검색 호출 수가 cache load 수와 일치하는지 교차 검증한다.

## 10. 결론

```text
FAIL - 캐시 미적용 100VU에서 담당자 목록 조회 포화 재현

- 성공 3,808건 / DELIVERY_011 313,851건 / 실패율 98.80%
- 첫 실패 약 55 VU
- user 검색 완료 4,144회 / 최대 28.90 req/s / 1분 평균 지연 최대 3.39s
- 대표 응답 2,400명 / 675,886 bytes / 반복 전송량 약 2.61 GiB
- user CPU 96.97% / Hikari pending 87 / 회로 not permitted 312,929건
- DB 및 Outbox 성공 반영 3,808건 일치
```

이번 실행은 캐시 적용 전 비교 기준으로 유효하다. 다음 실행은 캐시만 배포한 뒤 이미지, seed, supplier·receiver, 100VU 단계와 실행 시간을 그대로 유지해야 한다. 핵심 판정 기준은 전체 k6 수치만이 아니라 user 검색 호출 수가 수천 회에서 TTL 기반 수십 회로 줄고, cache hit과 load 수가 그 감소를 설명하는지 여부다.
