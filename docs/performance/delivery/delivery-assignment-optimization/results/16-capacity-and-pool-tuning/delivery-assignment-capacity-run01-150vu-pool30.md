# Delivery Assignment Capacity Run 01 - 150VU Pool 30 결과

## 1. 테스트 목적

담당자 캐시와 원자적 선점이 반영된 현재 상태에서 부하를 100VU에서 150VU로 높여 처리량 한계와 다음 병목을 확인한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-16 22:59:19 ~ 23:07:24 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=/tmp/hublink-k6-150vu-current.env STAGES='[{"duration":"1m","target":150},{"duration":"5m","target":150},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 150 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| delivery Hikari pool | maximum 30 |
| k6 로그 | `/tmp/hublink-k6-150vu-current-20260716.log` |

공식 실행 전 `STAGES` 전달 형식 오류로 k6가 요청을 보내기 전에 한 번 종료됐다. baseline SQL만 실행된 상태에서 같은 SQL로 다시 초기화한 뒤 공식 실행을 시작했으므로 결과에는 영향을 주지 않는다.

## 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 34,742 |
| HTTP TPS | 72.38 req/s |
| 성공 요청 수 | 31,359 |
| 실패 요청 수 | 3,383 |
| 실패율 | 9.73% |
| checks 성공률 | 90.26% |
| 평균 / median | 1.68s / 1.37s |
| p90 / p95 / p99 | 3.44s / 4.51s / 6.23s |
| 성공 응답 p95 | 4.66s |
| 최대 응답 시간 | 11.64s |
| max VU | 150 |

실패 3,383건은 모두 `DELIVERY_013` 502 응답이다. 실패율과 checks threshold는 경계 안에 있었지만 p95와 p99 threshold를 초과했다.

```text
checks: 90.26% > 90%
http_req_failed: 9.73% < 10%
p95: 4.51s > 3s
p99: 6.23s > 6s
```

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 64,959 | 31,359 |
| `p_delivery_route_histories` | 67,200 | 129,918 | 62,718 |
| `p_delivery_outboxes` | 33,600 | 64,959 | 31,359 |

k6 성공 31,359건과 배송 및 성공 Outbox 증가량이 일치하고, 경로 이력은 요청당 2건씩 증가했다. 신규 Outbox는 모두 `delivery.create.succeed`이며 최종적으로 31,359건 전부 `PUBLISHED` 상태가 됐다.

종료 약 2분 뒤에도 신규 Outbox 28,059건이 `PENDING`이었고 마지막 Outbox는 23:15:24 KST에 발행됐다. 테스트 종료 후 전체 회복에는 8분이 걸렸다.

| 담당자 유형 | 집계 row | 최종 배정 합계 | 최소 | 최대 | 30건 도달 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `HUB_DELIVERY` | 1,500 | 33,159 | 21 | 25 | 0 |
| `COMPANY_DELIVERY` | 1,800 | 34,959 | 18 | 23 | 0 |

담당자 최대 30건 한도에는 도달하지 않아 이번 실패 원인은 담당자 고갈이 아니다.

## 5. 캐시 결과

| 지표 | 값 |
| --- | ---: |
| cache hit | 약 62,802 |
| cache miss / put | 약 24 / 24 |
| cache size 최대 | 3 Hub |
| User 담당자 검색 실제 호출 | 약 24 |
| User Hikari pending 최대 | 0 |

Prometheus `increase` 보간값을 반올림한 수치다. 60초 TTL마다 3개 Hub가 다시 적재되는 기존 패턴이 유지됐고 User Service DB pool 포화는 재발하지 않았다.

## 6. Grafana 관찰 결과

| 지표 | 값 |
| --- | ---: |
| Delivery API 1분 RPS 최대 | 93.57 req/s |
| delivery-service CPU 최대 | 67.89% |
| delivery-service heap 최대 | 361.81 MiB |
| delivery-service GC pause 최대 | 206ms |
| delivery Hikari active 최대 | 30 / 30 |
| delivery Hikari pending 최대 | 122 |
| delivery Hikari pending 평균 | 80.12 |
| Tomcat busy ratio 최대 | 75.50% |
| Data VM CPU 최대 / 평균 | 99.98% / 77.02% |
| PostgreSQL connection 최대 | 60 |
| PostgreSQL commit TPS 최대 | 518.53 tx/s |
| Company 원자적 선점 1분 평균 최대 | 306.42ms |
| Hub 원자적 선점 1분 평균 최대 | 204.32ms |

delivery-service는 Pool 30을 모두 사용했고 pending이 최대 122까지 증가했다. 그러나 같은 구간에 Data VM CPU도 100%에 도달했으므로 connection pool만 크게 늘리면 PostgreSQL, Kafka, Redis를 함께 실행하는 2 vCPU Data VM의 과부하가 악화될 가능성이 있다. JVM heap과 GC는 포화 근거가 아니며 이번 한계는 JVM보다 DB 연결 대기와 Data VM 처리 용량에 가깝다.

같은 PromQL로 다시 조회하면 `SKIP LOCKED` 적용 전 100VU의 Data VM CPU는 최대 64.00%, 평균 48.70%였고 적용 후 캐시 전 100VU는 최대 63.48%, 평균 47.34%로 거의 같았다. 캐시 적용 직후에는 최대 99.98%, 평균 91.77%로 상승했다. 캐시 연산이 Data VM CPU를 직접 사용한 것이 아니라 User Service 조회 병목이 제거되면서 `SKIP LOCKED` 기반 원자적 선점과 배송·경로·Outbox 저장이 더 많이 실행된 결과다.

## 7. 오류 및 병목 분석

| 지표 | 값 |
| --- | ---: |
| `DELIVERY_013` | 3,383 |
| Hub circuit 실제 failed call | 11 |
| Hub circuit not permitted | 3,372 |
| Hub route 1분 평균 지연 최대 | 5.63ms |
| hub-service CPU 최대 | 21.82% |

초반 Hub 호출 11건이 실패하면서 circuit breaker가 열렸고 이후 3,372건이 실제 Hub 호출 없이 즉시 거절됐다. Hub route의 유지 구간 지연과 CPU는 낮았으므로 지속적인 Hub 처리량 포화보다는 ramp-up 초반의 일시적 실패가 회로 차단으로 증폭된 형태다. 관련 오류 trace는 Zipkin 조회 구간에서 보존된 표본이 없어 직접 원인까지 확정하지 못했다.

## 8. 100VU 비교

| 지표 | 100VU Pool 30 | 150VU Pool 30 | 변화 |
| --- | ---: | ---: | ---: |
| 총 TPS | 80.43 | 72.38 | 10.01% 감소 |
| 성공 처리량 | 80.43 | 65.33 | 18.77% 감소 |
| 실패율 | 0.00% | 9.73% | 9.73%p 증가 |
| p95 | 1.80s | 4.51s | 150.56% 증가 |
| Hikari pending 최대 | 71 | 122 | 71.83% 증가 |
| delivery CPU 최대 | 31.30% | 67.89% | 36.59%p 증가 |
| Data VM CPU 최대 | 100.00% | 99.98% | 동일한 포화 |
| Outbox 회복 | 9분 37초 | 8분 | 1분 37초 단축 |

150VU는 100VU보다 VU가 50% 늘었지만 총 TPS와 성공 처리량이 모두 감소했다. 처리량 증가는 멈추고 대기시간과 실패만 증가했으므로 현재 Pool 30 조건의 안정 구간은 100VU 이하로 판단한다.

## 9. 결론

```text
WARN - 150VU에서 DB pool 및 DB CPU 포화와 Hub circuit 실패 발생

- 총 34,742건, 성공 31,359건, 실패 3,383건
- 실패율 9.73%, p95 4.51초, p99 6.23초
- delivery Hikari active 30/30, pending 최대 122
- DB VM CPU 최대 99.98%
- JVM heap·GC 포화 근거 없음
- 담당자 한도 고갈 없음
- Outbox 최종 정합성 일치, 종료 후 8분에 회복
```

다음 비교는 Pool 확대 한계를 확인하기 위해 delivery Hikari maximum pool size를 30에서 60으로 늘려 같은 150VU 조건을 반복한다. TPS가 증가하지 않거나 Data VM CPU와 p95가 악화되면 Pool 확대가 해결책이 아니며, 같은 Pool 60에서 Outbox publisher만 중지한 분리 실험으로 요청 경로와 후처리 부하를 구분한다.
