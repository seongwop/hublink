# Delivery Assignment Manager Cache After Run 02 - 100VU 결과

## 1. 테스트 목적

활성 배정이 없는 서울 Hub 담당자 750명을 집계 테이블에 0건으로 추가한 뒤, 원자적 선점이 전체 1,500명을 사용하고 `DELIVERY_004` 조기 실패가 제거되는지 확인한다. 캐시 효과와 다음 병목도 동일한 100VU 조건에서 다시 측정한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-15 01:17:04 ~ 01:25:04 KST |
| 배포 이미지 | `hublink-delivery-service:571e7d2425baa3fd55214e1651f6901ee5e08162` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=/tmp/hublink-k6-run02.env ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| k6 로그 | `/tmp/hublink-k6-100vu-cache-after-run02-20260715.log` |

공식 실행 직전에 잘못 이스케이프된 `STAGES` 값으로 k6가 시작 전 종료됐지만, 요청은 한 건도 발생하지 않았다. 공식 실행에서 baseline SQL을 다시 실행해 영향을 제거했다.

## 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 38,607 |
| HTTP TPS | 80.43 req/s |
| 성공 요청 수 | 38,607 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 1.01s / 976.47ms |
| p90 / p95 / p99 | 1.56s / 1.80s / 2.37s |
| 최대 응답 시간 | 4.43s |
| max VU | 100 |

모든 threshold를 통과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 1.80s < 3s
p99: 2.37s < 6s
```

## 4. 담당자 집계 수정 검증

초기화 SQL은 `p_delivery_assignment_counts`에 총 3,300행을 생성했다. 구성은 서울 Hub 담당자 1,500명과 부산·인천 Company 담당자 1,800명이다.

| 담당자 유형 | 집계 row | 테스트 후 배정 합계 | 최소 | 최대 | 0건 row | 30건 도달 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `HUB_DELIVERY` | 1,500 | 40,407 | 26 | 29 | 0 | 0 |
| `COMPANY_DELIVERY` | 1,800 | 42,207 | 22 | 27 | 0 | 0 |

Hub 담당자 분포는 26건 293명, 27건 1,015명, 28건 184명, 29건 8명이다. 기존 실패 기준이었던 성공 20,700건을 4분 16초에 넘어선 뒤에도 처리 속도가 유지됐고 최종 38,607건까지 `DELIVERY_004`가 발생하지 않았다.

초기 Hub 활성 배정 1,800건과 신규 성공 38,607건의 합이 최종 집계 40,407건과 정확히 일치한다. 전체 1,500명이 원자적 선점 후보로 사용됐고 누락 row 수정이 의도대로 동작했다.

## 5. 캐시 효과

| 지표 | 값 |
| --- | ---: |
| cache get | 77,214 |
| cache hit | 77,190 |
| cache miss / put | 24 / 24 |
| cache hit ratio | 99.97% |
| cache size 최대 | 3 Hub |
| User 담당자 검색 실제 호출 | 24 |
| User 담당자 검색 최대 RPS | 0.10 req/s |
| User 담당자 검색 1분 평균 지연 최대 | 258.71ms |
| User 회로 failed / not permitted | 0 / 0 |

요청당 필요한 두 Hub를 조회해 cache get은 `38,607 × 2 = 77,214`건이다. 60초 TTL마다 3개 Hub가 다시 적재돼 실제 User Service 검색은 24회만 발생했다.

## 6. DB / Outbox 정합성

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 72,207 | 38,607 |
| `p_delivery_route_histories` | 67,200 | 144,414 | 77,214 |
| `p_delivery_outboxes` | 33,600 | 72,207 | 38,607 |

k6 성공 38,607건과 배송 및 성공 Outbox 증가량이 일치하고, 경로 이력은 요청당 2건씩 증가했다. 신규 failed·DLQ Outbox는 0건이다.

테스트 종료 직후 성공 Outbox backlog는 38,607건이었고 마지막 Outbox는 2026-07-15 01:34:41 KST에 발행됐다. 회복 시간은 테스트 종료 후 9분 37초다.

## 7. Grafana 관찰 결과

| 지표 | delivery-service | user-service | hub-service |
| --- | ---: | ---: | ---: |
| CPU 최대 | 31.30% | 10.47% | 6.46% |
| JVM heap 최대 | 317.77 MiB | 167.14 MiB | 미측정 |
| GC pause 최대 | 129ms | 67ms | 미측정 |
| Hikari active 최대 | 30 | 5 | 미측정 |
| Hikari pending 최대 | 71 | 0 | 미측정 |

| 처리 지표 | 값 |
| --- | ---: |
| Delivery API 1분 RPS 최대 | 92.27 req/s |
| Hub 경로 조회 완료 호출 | 38,607 |
| Hub 경로 조회 1분 RPS 최대 | 92.10 req/s |
| Hub 경로 조회 1분 평균 지연 최대 | 19.32ms |
| User 담당자 검색 완료 호출 | 24 |
| Company 원자적 선점 전체 평균 | 117.72ms |
| Hub 원자적 선점 전체 평균 | 158.51ms |

User Service 병목은 해소됐다. delivery-service Hikari pending은 여전히 71까지 올라가고 원자적 선점 평균도 100ms를 넘으므로 다음 병목은 delivery-service DB connection 대기와 선점 SQL이다.

## 8. Loki / Zipkin 분석

| Loki 지표 | 건수 |
| --- | ---: |
| delivery-service WARN / ERROR | 100 / 0 |
| user-service WARN / ERROR | 0 / 0 |
| hub-service WARN / ERROR | 248 / 0 |
| `DELIVERY_DOWNSTREAM_CALL_FAILED` | 0 |

delivery-service WARN 100건은 기존 Redis Stream의 `DELIVERY_PENDING_DLQ_MOVED`로 이번 배송 생성 요청과 무관하다. Hub WARN 248건은 `CompanyClient`에 별도 TimeLimiter 설정이 없어 기본 2초 설정을 사용한다는 경고다. Hub와 User 회로의 failed·not permitted 증분은 모두 0건이므로 이번 테스트 실패로 이어지지 않았다.

Zipkin의 종료 구간 최근 성공 trace 1,000개를 집계했다.

| span | 표본 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| delivery-service `/internal/deliveries` | 1,000 | 147.05ms | 260.81ms | 525.17ms |
| UserClient Hub 정보 조회 | 1,000 | 7.21ms | 14.35ms | 170.86ms |
| user-service `/internal/hubs/{hubId}` | 1,000 | 5.39ms | 10.80ms | 41.29ms |
| HubClient 경로 조회 | 1,000 | 3.03ms | 5.64ms | 130.73ms |
| hub-service `/internal/hub-routes/path` | 1,000 | 1.18ms | 1.75ms | 5.44ms |

최근 trace는 ramp-down 구간 위주라 전체 k6 지연보다 짧다. 표본에서 가장 느린 하위 호출도 p95 14.35ms로 작으므로 전체 p95 1.80초의 원인을 하위 HTTP 호출로 보기는 어렵다. Hikari pending과 원자적 선점 metric이 DB 대기를 다음 관찰 대상으로 가리킨다.

## 9. 이전 실행 비교 및 결론

| 지표 | row 누락 run01 | row 보완 run02 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 요청 | 20,700 | 38,607 | 86.51% 증가 |
| 성공 처리량 | 43.13 req/s | 80.43 req/s | 86.48% 증가 |
| 실패 요청 | 37,227 | 0 | 제거 |
| `DELIVERY_004` | 37,227 | 0 | 제거 |
| p95 | 1.80s | 1.80s | 동일 |
| User 검색 호출 | 24 | 24 | 동일 |
| delivery Hikari pending 최대 | 72 | 71 | 유사 |
| Outbox 회복 | 5분 21초 | 9분 37초 | backlog 증가 영향 |

run01의 전체 요청 57,927건은 용량 소진 후 404가 빠르게 반환되며 증폭된 값이므로 정상 처리량 비교에 사용하지 않는다.

```text
PASS - 집계 row 누락 수정 후 100VU 전체 흐름 정상

- 총 38,607건 전부 성공, 실패율 0%
- DELIVERY_004 / DELIVERY_011 / DELIVERY_013 0건
- Hub 담당자 1,500명 전체 사용, 최종 26~29건 분포
- 캐시 hit ratio 99.97%, User 담당자 검색 24회
- DB 및 Outbox 성공 반영 38,607건 일치
- Outbox backlog 회복 9분 37초
- 다음 병목은 delivery-service DB pool과 원자적 선점 SQL
```

현재 Hub 총 용량은 45,000건이고 테스트 종료 시 활성 배정은 40,407건이므로 4,593건의 여유가 남았다. 더 긴 테스트나 처리량 추가 개선 시에는 실제 30건 한도 소진으로 정상적인 `DELIVERY_004`가 발생할 수 있으므로, 다음 비교는 동일 8분 조건을 유지하거나 도착률 기반으로 목표 요청량을 고정해야 한다.
