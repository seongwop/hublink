# Delivery Assignment Atomic Reservation Run 03 - 100VU 반복 결과

## 1. 테스트 목적

동일한 100VU 조건에서 이전 실행의 user-service 포화가 일시적인 현상인지 재검증한다. 원자적 담당자 선점의 정합성과 `DELIVERY_004` 재발 여부도 함께 확인한다.

현재 구현은 담당자 2,400명을 한 번만 적재해 재사용하는 구조가 아니다. 배송 생성 요청마다 `DeliveryService.getDeliveryManagers()`가 user-service의 `POST /internal/delivery-managers/search`를 호출한다. user-service도 호출마다 Hub 목록으로 담당자와 사용자 정보를 조회해 전체 DTO를 반환하며 delivery-service의 별도 캐시는 없다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-14 01:26:03 ~ 01:34:07 KST |
| 배포 이미지 | `hublink-delivery-service:251b1d0aef5b0371b98a15563c19a13464040ad0` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| k6 로그 | `/tmp/hublink-k6-100vu-atomic-reservation-repeat-20260713T162603Z.log` |

Run 02와 같은 명령과 seed를 사용했다. 사전 점검에서 담당자 검색은 2,400명, 675,886 bytes를 156ms에 반환했고 배송 생성 1건은 201로 응답했다. 사전 생성 데이터는 baseline reset으로 제거한 뒤 테스트를 시작했다.

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 132,178 |
| 전체 HTTP 요청률 | 275.34 req/s |
| 성공 요청 수 | 12,035 |
| 성공 처리량 | 25.05 req/s |
| 실패 요청 수 | 120,143 |
| 실패율 | 90.89% |
| checks 성공률 | 9.10% |
| 전체 평균 / median | 294.82ms / 78.12ms |
| 전체 p95 / p99 | 2.60s / 3.20s |
| 전체 최대 | 5.63s |
| 성공 응답 평균 / median | 2.28s / 2.66s |
| 성공 응답 p90 / p95 | 3.16s / 3.45s |
| 성공 응답 최대 | 5.63s |

threshold는 `checks`와 `http_req_failed`에서 실패했다. 전체 p95와 p99는 통과했지만 회로 차단 후 빠르게 반환된 502가 포함된 값이므로 정상 처리 성능을 의미하지 않는다.

실패 120,143건은 모두 HTTP 502 `DELIVERY_011`이었고 `DELIVERY_004`는 0건이었다. 첫 실패는 01:26:39 KST로 실제 k6 시작 약 32초 후인 약 55 VU 구간에서 발생했다. 마지막 실패는 ramp-down 중인 01:32:40 KST였다.

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 45,635 | 12,035 |
| `p_delivery_route_histories` | 67,200 | 91,270 | 24,070 |
| `p_delivery_outboxes` | 33,600 | 45,635 | 12,035 |

`created_by=SYSTEM`인 배송 12,035건과 경로 이력 24,070건이 확인됐다. 배송과 Outbox를 조인한 `delivery.create.succeed` 12,035건은 모두 `PUBLISHED`였고 마지막 이벤트도 테스트 종료 약 1초 안에 발행됐다.

| 배정 유형 | 담당자 row | 활성 배정 합계 | 담당자별 최대 |
| --- | ---: | ---: | ---: |
| `COMPANY_DELIVERY` | 1,800 | 15,635 | 11 |
| `HUB_DELIVERY` | 750 | 13,835 | 20 |

두 배정 유형의 활성 배정 합계가 baseline 대비 각각 12,035씩 증가했다. k6 성공, 배송, Outbox, 회사 담당자 배정, 허브 담당자 배정 수가 모두 일치해 원자적 선점의 누락이나 중복 증가는 없었다.

## 5. Prometheus / Grafana 지표

| 지표 | delivery-service | user-service |
| --- | ---: | ---: |
| CPU 최대 | 92.09% | 96.73% |
| JVM heap 최대 | 297.40 MiB | 1,427.17 MiB |
| GC pause 최대 | 42ms | 208ms |
| Hikari active 최대 | 30 | 10 |
| Hikari pending 최대 | 17 | 94 |

| 업무 지표 | 값 |
| --- | ---: |
| user-service 담당자 검색 RPS 최대 | 33.73 req/s |
| user-service 담당자 검색 1분 평균 지연 최대 | 1.60s |
| 담당자 검색 1회 응답 | 2,400명 / 675,886 bytes(약 660 KiB) |
| `company_delivery` 원자적 예약 평균 | 12.46ms |
| `hub_delivery` 원자적 예약 평균 | 14.08ms |

원자적 예약은 평균 12~14ms로 유지됐지만 user-service는 CPU 96.73%, Hikari active 10, pending 94까지 상승했다. 약 660 KiB 응답을 최대 33.73회/초 생성하면서 응답 객체 생성·직렬화와 DB pool 대기가 함께 커졌고, 일부 요청이 delivery-service의 약 2초 client 제한을 넘었다.

## 6. 회로 차단기와 로그 분석

Prometheus 15초 scrape에서 user-service 회로의 OPEN 상태가 다음 구간에 확인됐다.

```text
01:29:03 ~ 01:29:48 KST OPEN 표본
01:31:03 ~ 01:31:18 KST OPEN 표본
```

| 회로 지표 | 증가 |
| --- | ---: |
| failed call | 469 |
| not permitted call | 119,675 |
| successful call | 23,854 |

실패율은 5건 sliding window에서 빠르게 변해 15초 scrape가 임계 순간을 모두 담지는 못했지만, OPEN 상태와 대량의 not permitted 호출은 직접 확인됐다. 회로가 열리면 user-service 호출이 즉시 거절되고 `SLEEP_SECONDS=0`인 k6가 다음 요청을 바로 생성하므로 전체 요청률과 502 수가 증폭된다.

Loki에서 delivery-service WARN은 656건, ERROR는 0건이었다. WARN 표본은 timeout 후 다른 user-service 인스턴스를 찾았지만 단일 인스턴스만 있어 같은 인스턴스를 다시 선택했다는 load balancer 경고였다. user-service WARN과 ERROR는 0건이었다. `DELIVERY_004`와 `DELIVERY_011`은 fallback 응답에서 별도 로그를 남기지 않아 각각 k6 응답과 DB 정합성, 회로 메트릭으로 확인했다.

## 7. Zipkin 병목 분석

테스트 종료부의 완성된 배송 trace 표본을 집계했다.

| span | 표본 수 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| delivery-service 루트 | 971 | 428.99ms | 804.54ms | 1.18s |
| user-service `/internal/delivery-managers/search` | 971 | 322.38ms | 614.62ms | 793.78ms |
| hub-service `/internal/hub-routes/path` | 971 | 1.13ms | 1.51ms | 7.88ms |

회복 구간의 가장 긴 하위 호출도 user-service 담당자 검색이었다. 회로에서 즉시 거절된 요청에는 user-service server span이 없으므로 이 표본은 전체 실패 분포가 아니라 완성된 성공 trace의 분포다.

테스트 종료 후 단건 회복 확인에서 user-service health는 200이었고, 동일한 두 Hub의 담당자 검색도 2,400명, 675,886 bytes를 54.96ms에 반환했다.

## 8. 반복 실행 비교

| 구분 | Run 02 | Run 03 반복 |
| --- | ---: | ---: |
| 첫 실패 시점 | 약 55 VU | 약 55 VU |
| 성공 요청 | 4,796 | 12,035 |
| 실패 요청 | 282,267 | 120,143 |
| 실패율 | 98.32% | 90.89% |
| 성공 p95 | 2.92s | 3.45s |
| user 검색 지연 최대 | 1.98s | 1.60s |
| user Hikari pending 최대 | 88 | 94 |
| `DELIVERY_004` | 0 | 0 |
| company 원자적 예약 평균 | 13.29ms | 12.46ms |
| hub 원자적 예약 평균 | 16.85ms | 14.08ms |

실패 규모와 회로가 열린 시간은 실행마다 달랐지만 두 실행 모두 약 55 VU에서 첫 user-service timeout이 발생했고, 100 VU 유지 구간에서 회로 차단과 `DELIVERY_011`이 반복됐다. 따라서 이전 결과는 일회성 장애가 아니라 현재 구성에서 재현되는 용량 경계다.

담당자 2,400명 조회 구조는 원자적 선점 변경으로 새로 생긴 것이 아니라 기존에도 요청마다 존재했다. 이번 변경은 담당자 목록을 받은 뒤 실행되는 DB 선점 로직이므로 user-service 초기 지연의 직접 원인은 아니다. 다만 선점 구간이 빨라지면서 같은 closed workload에서 다음 요청이 더 빨리 들어가 기존의 대량 조회 병목이 더 쉽게 드러날 수 있다.

## 9. 결론

```text
FAIL 동일 100VU 재테스트에서 user-service 담당자 조회 포화 재현

- 성공 12,035건 / DELIVERY_011 120,143건
- HTTP 실패율 90.89%
- 첫 실패 약 55 VU
- DELIVERY_004 0건
- DB 및 Outbox 성공 반영 12,035건 일치
- 원자적 예약 평균 company 12.46ms / hub 14.08ms
- user-service Hikari pending 최대 94 / CPU 최대 96.73%
- 회로 not permitted 119,675건
```

다음 최적화 대상은 원자적 선점 SQL이 아니라 user-service 담당자 검색 계약이다. 배송 생성마다 2,400명 전체를 반환하지 말고 Hub와 담당자 유형으로 후보를 좁혀 배정에 필요한 최소 필드만 반환해야 한다. 변경 후에는 실패 응답이 요청 수를 증폭하지 않도록 일정 도착률 방식으로 50·60·80·100 VU 또는 동일 RPS를 비교한다.
