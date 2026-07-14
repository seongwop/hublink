# Delivery Assignment Atomic Reservation Smoke Run 01 - 20VU 결과

## 1. 테스트 목적

배송 담당자 선택과 배정 수 증가를 단일 쿼리로 원자화한 뒤, 기존 `SKIP LOCKED` 단독 적용에서 발생한 거짓 담당자 미배정 오류가 해소됐는지 확인한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-13 22:51:57 ~ 22:52:21 KST |
| 배포 이미지 | `hublink-delivery-service:251b1d0aef5b0371b98a15563c19a13464040ad0` |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 20 VU / 20초 |
| 부하 패턴 | 5초 ramp-up, 10초 유지, 5초 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| 변경 사항 | 담당자 1명 선택과 `active_assignment_count + 1`을 단일 SQL로 처리 |
| k6 로그 | `/tmp/hublink-k6-20vu-atomic-select-update-final-20260713T135157Z.log` |

실행 명령:

```bash
ENV_FILE=/tmp/hublink-no-env PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"5s","target":20},{"duration":"10s","target":20},{"duration":"5s","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

Company, Hub, User 서비스의 Eureka 등록과 회로 차단기를 복구하고 실제 배송 생성 1건이 201로 응답하는 것을 확인한 뒤 baseline을 다시 초기화했다. 복구 전 외부 서비스 통신 실패 구간은 원자적 배정 로직에 도달하지 못했으므로 측정에서 제외했다.

## 3. k6 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 223 |
| HTTP TPS | 11.07 req/s |
| 성공 요청 수 | 222 |
| 실패 요청 수 | 1 |
| 실패율 | 0.44% |
| checks 성공률 | 99.55% |
| 평균 응답 시간 | 1.38s |
| median | 1.46s |
| p90 | 2.02s |
| p95 | 2.18s |
| p99 | 2.45s |
| 최대 응답 시간 | 2.67s |
| max VU | 20 |

모든 threshold를 통과했다.

```text
checks: rate=99.55% > 90%
http_req_failed: rate=0.44% < 10%
http_req_duration: p95=2.18s < 3s, p99=2.45s < 6s
```

실패 1건은 HTTP 502 `DELIVERY_011`(`사용자 서비스와 통신할 수 없습니다.`)였으며 `DELIVERY_004`는 발생하지 않았다.

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 33,822 | 222 |
| `p_delivery_route_histories` | 67,200 | 67,644 | 444 |
| `p_delivery_outboxes` | 33,600 | 33,822 | 222 |

베이스라인 UUID 대역과 분리한 이번 테스트 생성분은 배송 222건, 경로 이력 444건, `delivery.create.succeed` Outbox 222건이다. k6 성공 222건과 정확히 일치한다.

테스트 직후 Outbox 222건은 `PENDING`이었고, 2026-07-13 22:54:20 KST에 모두 `PUBLISHED`로 확인됐다. 테스트 종료 후 1분 59초 이내에 이번 테스트 건과 전체 Outbox 33,822건의 backlog가 모두 해소됐다.

## 5. Prometheus / Grafana 지표

| 지표 | 값 |
| --- | ---: |
| delivery-service CPU 최대 | 39.08% |
| JVM heap 최대 | 약 136.5 MiB |
| GC pause 최대 | 34ms |
| Hikari active 최대 | 0 |
| Hikari pending 최대 | 0 |
| `company_delivery` 원자적 예약 평균 / 최대 | 9.85ms / 100.07ms |
| `hub_delivery` 원자적 예약 평균 / 최대 | 11.87ms / 100.30ms |

스크레이프 시점에는 DB connection active와 pending이 모두 0이었고, CPU·heap·GC 포화도 관찰되지 않았다. 원자적 예약 평균은 이전 `SKIP LOCKED` 단독 조회의 company 24.41ms, hub 23.20ms보다 각각 약 60%, 49% 감소했다.

## 6. 로그 및 오류 분석

최종 구간의 Loki 조회에서 delivery-service와 user-service의 `WARN`, `ERROR`, `DELIVERY_004`, `DELIVERY_011` 로그는 검색되지 않았다. 처리된 502 응답이 별도 애플리케이션 오류 로그를 남기지 않아 k6 응답과 Zipkin HTTP 상태로 확인했다.

`DELIVERY_004`가 0건이므로 잠긴 후보 전체를 건너뛰어 정상 담당자를 없다고 판단하던 기능 회귀는 해소됐다. 남은 실패 1건은 배정 SQL이 아니라 User 담당자 검색 구간의 일시적 통신 timeout이다.

## 7. Zipkin 병목 분석

최종 구간의 배송 루트 트레이스는 223건으로 k6 요청 수와 일치했다.

| HTTP 상태 | 건수 | 루트 평균 | 루트 p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| 201 | 222 | 1.38s | 2.18s | 2.67s |
| 502 | 1 | 2.13s | 2.13s | 2.13s |

가장 느린 하위 호출은 user-service의 `POST /internal/delivery-managers/search`로 평균 1.03s, 최대 2.06s였다. Hub 경로 조회는 평균 2.94ms, 최대 11.58ms였다. 실패 요청에서도 user-service server span은 남았지만 delivery-service UserClient 완료 span은 없어, 약 2초의 client 제한을 넘긴 timeout으로 판단한다.

## 8. 이전 SKIP LOCKED 스모크와 비교

| 구분 | 성공률 | 실패 | `DELIVERY_004` | TPS | p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| SKIP LOCKED 단독 | 47.23% | 143 / 271 | 143 | 13.50 req/s | 1.68s |
| 원자적 선택 + 증가 | 99.55% | 1 / 223 | 0 | 11.07 req/s | 2.18s |

이전 실행은 절반 이상이 빠르게 404로 종료됐기 때문에 TPS와 latency를 처리 성능 개선으로 직접 비교할 수 없다. 이번 실행은 222건이 실제 DB 저장과 Outbox 생성까지 완료된 조건이다.

## 9. 결론

```text
PASS 원자적 담당자 선점으로 SKIP LOCKED 기능 회귀 해소

- 총 요청 223건
- 성공 222건 / User 통신 실패 1건
- HTTP 실패율 0.44%
- checks 성공률 99.55%
- DELIVERY_004 0건
- DB 및 Outbox 성공 반영 222건
- Outbox 전체 발행 확인 1분 59초 이내
- 원자적 예약 평균 company 9.85ms / hub 11.87ms
```

20VU 스모크 기준으로 원자적 선점 변경은 정상이다. 다음 정식 부하 전에 user-service 담당자 검색의 약 2초 timeout 1건을 관찰 대상으로 유지하고, 동일 구현으로 100VU 비교 테스트를 진행할 수 있다.
