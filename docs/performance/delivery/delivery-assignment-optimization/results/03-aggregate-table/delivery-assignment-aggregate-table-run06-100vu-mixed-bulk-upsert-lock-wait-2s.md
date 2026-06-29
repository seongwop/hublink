# Delivery Assignment Aggregate Table + Mixed Bulk Upsert Run 06 - 100VU Distributed No Sleep, Lock Wait 2s 결과

### 1. 테스트 목적

Run 05에서 병목 분석용 Micrometer 계측을 추가한 뒤, 배송 생성 트랜잭션 안에서 `COMPANY_DELIVERY` 집계 증가와 `HUB_DELIVERY` 집계 증가가 각각 별도 bulk upsert로 실행되는 것을 확인했다.

이번 run은 두 집계 증가를 하나의 mixed bulk upsert로 합쳐 DB write 왕복을 `2회 -> 1회`로 줄였을 때 전체 성능에 유의미한 개선이 있는지 확인한다.

확인 포인트는 다음과 같다.

- `assignment_count_mixed_increase` 계측이 정상 수집되는지
- 집계 증가 write 시간이 기존 개별 bulk upsert 합산보다 줄어드는지
- 전체 TPS, p95, p99, lock timeout, Hikari pending이 개선되는지
- DB/outbox 반영이 k6 성공 건수와 일치하는지

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Aggregate Table + Mixed Bulk Upsert Run 06 - 100VU Distributed No Sleep, Lock Wait 2s |
| 테스트 시작 시간 | 2026-06-29 22:44:08 KST |
| 테스트 종료 시간 | 2026-06-29 22:52:38 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 8분 |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | `COMPANY_DELIVERY` + `HUB_DELIVERY` 집계 증가를 mixed bulk upsert 1회로 통합 |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 9371 |
| HTTP TPS | 19.52 req/s |
| 성공 요청 수 | 9370 |
| 실패 요청 수 | 1 |
| 실패율 | 0.01% |
| checks 성공률 | 99.98% |
| 평균 응답 시간 | 4.18s |
| median 응답 시간 | 4.65s |
| p90 응답 시간 | 5.64s |
| p95 응답 시간 | 5.99s |
| p99 응답 시간 | 9.11s |
| 최대 응답 시간 | 17.61s |
| max VU | 100 |

threshold 결과:

```text
checks
✓ rate>0.90

http_req_duration
✗ p(95)<3000
✗ p(99)<6000

http_req_failed
✓ rate<0.10
```

실패 1건은 배송 담당자 배정 lock timeout으로 인한 HTTP 409였다.

```text
status=409
message=DELIVERY_014
배송 기사 배정이 진행 중입니다. 잠시 후 다시 시도해주세요.
```

### 4. DB 처리 결과

| 항목 | baseline | 테스트 후 | 증가량 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33600 | 42970 | 9370 |
| `p_delivery_route_histories` | 67200 | 85940 | 18740 |
| `p_delivery_outboxes` | 33600 | 42970 | 9370 |
| `PUBLISHED` outbox | 33600 | 42970 | 9370 |

- k6 성공 요청 9370건이 모두 DB와 outbox에 반영되었다.
- 실패 1건은 lock timeout으로 DB/outbox에 반영되지 않았다.
- route history는 성공 배송 1건당 2건씩 증가했다.
- 테스트 직후에는 outbox backlog가 있었지만, 약 3~4분 후 전체 outbox가 `PUBLISHED` 상태로 정리되었다.

집계 테이블 상태:

| 항목 | 테스트 후 | baseline 대비 증가 |
| --- | ---: | ---: |
| `COMPANY_DELIVERY` active count 합계 | 12970 | 9370 |
| `HUB_DELIVERY` active count 합계 | 11170 | 9370 |

### 5. 계측 결과

새로 추가한 mixed bulk 계측은 정상 수집되었다.

| 항목 | 값 |
| --- | ---: |
| `assignment_count_mixed_increase` 평균 | 0.864ms |
| `delivery.assignment.count.operation{assignment_type="mixed", operation="increase"}` 평균 | 0.821ms |
| company assignment count read 평균 | 12.699ms |
| hub assignment count read 평균 | 14.107ms |
| outbox exists check 평균 | 0.899ms |
| outbox save and flush 평균 | 3.973ms |
| create transaction 전체 평균 | 6.253ms |

락 계측:

| 항목 | 값 |
| --- | ---: |
| company lock wait 평균 | 339.072ms |
| hub lock wait 평균 | 42.113ms |
| lock hold 평균 | 49.845ms |
| lock timeout | 1건 |

리소스 계측:

| 항목 | 값 |
| --- | ---: |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 92 |
| delivery process CPU 최대 | 69.9% |
| delivery system CPU 최대 | 86.2% |

### 6. 이전 계측 기준선과 비교

비교 기준은 병목 계측 추가 후 mixed bulk upsert 적용 전 100VU 결과다.

| 항목 | bulk upsert 기준선 100VU | mixed bulk upsert 100VU |
| --- | ---: | ---: |
| 총 요청 수 | 9839 | 9371 |
| 성공 요청 수 | 9839 | 9370 |
| 실패 요청 수 | 0 | 1 |
| HTTP TPS | 20.50 req/s | 19.52 req/s |
| 평균 응답 시간 | 3.98s | 4.18s |
| median 응답 시간 | 4.54s | 4.65s |
| p90 | 5.21s | 5.64s |
| p95 | 5.62s | 5.99s |
| p99 | 8.85s | 9.11s |
| 최대 응답 시간 | 13.31s | 17.61s |
| lock timeout | 0 | 1 |
| Hikari pending 최대 | 92 | 92 |
| Hikari active 최대 | 10 | 10 |

집계 증가 write 계측 비교:

| 항목 | bulk upsert 기준선 | mixed bulk upsert |
| --- | ---: | ---: |
| company increase 평균 | 0.727ms | - |
| hub increase 평균 | 0.500ms | - |
| 개별 increase 합산 | 1.227ms | - |
| mixed increase 평균 | - | 0.821ms |

mixed bulk upsert로 집계 증가 write 단계 자체는 약 `1.227ms -> 0.821ms`로 줄었다. 다만 이 차이는 전체 응답 시간과 tail latency를 개선할 만큼 크지 않았다.

### 7. 해석

```text
NO_END_TO_END_IMPROVEMENT

- mixed bulk upsert는 정상 동작
- 집계 증가 write 시간은 감소
- 하지만 100VU 전체 TPS, p95, p99는 개선되지 않음
- lock timeout 1건 발생
- Hikari pending 최대값은 92로 기존과 동일
- company lock wait 평균은 319ms -> 339ms로 오히려 소폭 증가
```

이번 결과는 현재 병목이 집계 증가 write 왕복 횟수에 있지 않다는 것을 보여준다. mixed bulk upsert는 DB write를 더 깔끔하게 줄였지만, 100VU 구간에서는 company lock wait와 Hikari connection pending이 여전히 전체 응답 시간을 지배한다.

따라서 mixed bulk upsert는 다음과 같이 정리한다.

- 코드/DB write 관점에서는 의미 있는 단순화다.
- 성능 실험 관점에서는 end-to-end 개선 효과가 확인되지 않았다.
- 다음 병목 후보는 `company lock wait`, `Hikari pending`, `outbox backlog`다.

### 8. 다음 액션

1. mixed bulk upsert는 성능 개선 근거를 과장하지 않고, "DB write 왕복 축소 실험"으로 문서화한다.
2. 다음 최적화는 복잡한 assignment select 쿼리보다 먼저 다음 후보를 검토한다.
   - Hikari pool size 조정 실험
   - outbox `exists + saveAndFlush`를 `insert on conflict do nothing`으로 축소
   - company lock key 집중 원인 분석
3. 포트폴리오에는 "측정 결과, write 최적화 이후 병목이 lock wait / connection wait로 이동했음을 확인"한 사례로 정리한다.
