# Delivery Assignment Optimization

## Latest Run Note

- `redis-lock-order run04`: 같은 hub-first lock 순서로 100VU를 측정했다.
- 결과는 TPS 21.86 req/s, p95 4.99s, p99 5.91s, 실패 16건(`DELIVERY_014`, 0.15%)이었다.
- HTTP 실패율 기준은 통과했지만 p95가 3초 threshold를 넘어 k6 실행은 실패 종료됐고, p99도 6초 threshold에 근접했다.
- timeout failedKey는 20VU, 50VU, 80VU와 동일하게 hub key 1개로 관측됐다.
- DB 반영량은 k6 성공 10,478건과 일치했다.
- delivery-service Hikari pending 최대는 82까지 증가했다.
- 80VU 대비 TPS 증가는 21.31 -> 21.86으로 작았고, p95는 3.83s -> 4.99s, Hikari pending은 62 -> 82로 악화됐다.
- 상세 결과: `results/07-redis-lock-order/delivery-assignment-redis-lock-order-run04-100vu-hub-first-lock-wait-2s.md`

- `redis-lock-order run03`: 같은 hub-first lock 순서로 80VU를 측정했다.
- 결과는 TPS 21.31 req/s, p95 3.83s, p99 4.34s, 실패 7건(`DELIVERY_014`, 0.06%)이었다.
- HTTP 실패율 기준은 통과했지만 p95가 3초 threshold를 넘어 k6 실행은 실패 종료됐다.
- timeout failedKey는 20VU, 50VU와 동일하게 hub key 1개로 관측됐다.
- DB 반영량은 k6 성공 10,221건과 일치했지만, 조회 시점 기준 outbox pending 9,821건이 남았다.
- delivery-service Hikari pending 최대는 62까지 증가했다.
- 50VU 대비 TPS 증가는 20.75 -> 21.31로 작았고, p95는 2.49s -> 3.83s, Hikari pending은 32 -> 62로 악화됐다.
- 상세 결과: `results/07-redis-lock-order/delivery-assignment-redis-lock-order-run03-80vu-hub-first-lock-wait-2s.md`

- `redis-lock-order run02`: 같은 hub-first lock 순서로 50VU를 측정했다.
- 결과는 TPS 20.75 req/s, p95 2.49s, p99 3.01s, 실패 9건(`DELIVERY_014`, 0.09%)이었다.
- timeout failedKey는 20VU와 동일하게 hub key 1개로 관측됐다.
- DB 반영량은 k6 성공 9,951건과 일치했지만, 테스트 종료 직후 outbox pending 11,311건, 60초 뒤 pending 9,651건이 남아 outbox publisher backlog가 커졌다.
- delivery-service Hikari pending 최대도 32까지 증가했다.
- 상세 결과: `results/07-redis-lock-order/delivery-assignment-redis-lock-order-run02-50vu-hub-first-lock-wait-2s.md`

- `redis-lock-order run01`: Redis lock 획득 순서를 `hub -> company -> unknown`으로 변경한 뒤 20VU를 측정했다.
- 테스트 전 Config Server, Eureka, company/hub/delivery health, Redis lock 잔여, outbox backlog를 확인했고 서버 상태 문제 없이 정상 실행됐다.
- 결과는 TPS 14.40 req/s, p95 1.79s, 실패 86건(`DELIVERY_014`, 1.24%)이었다.
- timeout failedKey는 기존 company key에서 `lock:delivery:hub:10000000-0000-0000-0000-000000000001` 1개로 이동했다.
- 따라서 기존 company lock 병목 해석은 lock 획득 순서의 영향을 받은 면이 있으며, 현재 입력에서는 공통 hub key도 강한 직렬화 지점으로 판단한다.
- 상세 결과: `results/07-redis-lock-order/delivery-assignment-redis-lock-order-run01-20vu-hub-first-lock-wait-2s.md`

## Redis Lock Order 전후 비교

50VU는 Redis lock scope reduction 단계에서 같은 VU 결과가 없어서, lock 순서 변경 전 대표값은 `pool-tuning run03`으로 비교한다. 20VU는 직전 단계인 `redis-lock-scope run02`와 안정적으로 재측정된 `pool-tuning run02`를 함께 둔다.

| VU | 구분 | 비교 run | 총 요청 | 성공 | 실패 | 실패율 | TPS | p95 | p99 | timeout key | Hikari pending 최대 |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| 20 | 변경 전 대표값 | `pool-tuning run02` | 6,609 | 6,525 | 84 | 1.27% | 13.77 | 2.03s | 2.20s | company | 2 |
| 20 | 변경 전 직전값 | `redis-lock-scope run02` | 6,629 | 6,424 | 205 | 3.09% | 13.81 | 2.12s | 2.36s | company | 2 |
| 20 | 변경 후 | `redis-lock-order run01` | 6,916 | 6,830 | 86 | 1.24% | 14.40 | 1.79s | - | hub | 2 |
| 50 | 변경 전 대표값 | `pool-tuning run03` | 9,097 | 9,054 | 43 | 0.47% | 18.95 | 3.01s | 3.86s | company | 32 |
| 50 | 변경 후 | `redis-lock-order run02` | 9,960 | 9,951 | 9 | 0.09% | 20.75 | 2.49s | 3.01s | hub | 32 |
| 80 | 변경 전 대표값 | `pool-tuning run04` | 9,322 | 9,250 | 72 | 0.77% | 19.41 | 4.61s | 7.12s | company | 62 |
| 80 | 변경 후 | `redis-lock-order run03` | 10,228 | 10,221 | 7 | 0.06% | 21.31 | 3.83s | 4.34s | hub | 62 |
| 100 | 변경 전 대표값 | `pool-tuning run05` | 9,650 | 9,620 | 30 | 0.31% | 20.05 | 5.54s | 8.54s | company | 82 |
| 100 | 변경 후 | `redis-lock-order run04` | 10,494 | 10,478 | 16 | 0.15% | 21.86 | 4.99s | 5.91s | hub | 82 |

비교 결과만 보면 50VU에서는 lock 순서 변경 후 TPS가 18.95에서 20.75로 증가했고, p95는 3.01s에서 2.49s로 감소했으며, 실패도 43건에서 9건으로 줄었다. 80VU와 100VU에서도 TPS, p95, p99, 실패 건수는 이전 대표값보다 좋아졌다. 20VU는 대표값 대비 실패 건수는 거의 같지만 p95와 TPS가 개선됐고, 직전 `redis-lock-scope run02` 대비로는 실패도 205건에서 86건으로 줄었다.

다만 이 비교의 핵심은 단순 성능 개선보다 timeout key가 company에서 hub로 이동했다는 점이다. 즉 기존 company 병목 판단은 lock 획득 순서의 영향을 받았고, 현재 부하 입력에서는 공통 hub key가 먼저 잡히면서 실제 대기 지점으로 드러난다.

- `redis-lock-scope run01/run02`: Redis 분산락을 유지하되 락 안에서는 담당자 선택과 집계 증가 예약만 수행하도록 줄인 뒤 20VU를 2회 측정했다.
- run01은 TPS 12.96 req/s, p95 2.09s, 실패 126건(`DELIVERY_014`, 2.02%)이었다.
- run02는 TPS 13.81 req/s, p95 2.12s, 실패 205건(`DELIVERY_014`, 3.09%)이었다.
- DB 반영량은 두 run 모두 k6 성공 건수와 일치했다.
- 새 계측 기준 lock hold 평균은 run01 73ms, run02 69ms로 짧았지만 company lock wait는 평균 0.9~1.0s 수준이고 timeout은 2s까지 도달했다.
- 따라서 현재 병목은 mixed bulk upsert나 배송 저장 트랜잭션보다 company delivery manager lock key 집중으로 판단한다. 락 범위 축소만으로는 20VU 실패율 개선이 확인되지 않았다.
- 상세 결과: `results/09-redis-lock-scope-reduction/delivery-assignment-redis-lock-scope-run01-20vu-lock-wait-2s.md`
- 상세 결과: `results/09-redis-lock-scope-reduction/delivery-assignment-redis-lock-scope-run02-20vu-lock-wait-2s.md`

- `pool-tuning run02/run03/run04/run05`: fallback 접근자를 `public`으로 수정한 뒤 20VU, 50VU, 80VU, 100VU를 재측정했다.
- 네 run 모두 `DELIVERY_011`, `DELIVERY_013`, `IllegalAccessException`은 0건이었다. 즉 fallback 접근 오류와 hub/user 502 폭주는 재현되지 않았다.
- 20VU는 재측정 대표값 기준 TPS 13.77 req/s, p95 2.03s, 실패 84건(`DELIVERY_014`)이었다.
- 50VU는 재측정 대표값 기준 TPS 18.95 req/s, p95 3.01s, 실패 43건(`DELIVERY_014`)이었다.
- 80VU는 TPS 19.41 req/s, p95 4.61s, p99 7.12s, 실패 72건(`DELIVERY_014`)이었다.
- 100VU는 TPS 20.05 req/s, p95 5.54s, p99 8.54s, 실패 30건(`DELIVERY_014`)이었다.
- 100VU는 80VU 대비 TPS가 19.41에서 20.05로 소폭만 증가했고 Hikari pending은 62에서 82로 증가해, 현재 구조의 처리량 한계가 50VU 이후부터 뚜렷하게 드러난다.
- 현재 재현 가능한 병목은 외부 서비스 통신보다 company delivery manager 배정 lock timeout과 Hikari pending 쪽이다.
- 상세 결과: `results/06-pool-tuning/delivery-assignment-pool-tuning-run02-20vu-fallback-public-lock-wait-2s.md`
- 상세 결과: `results/06-pool-tuning/delivery-assignment-pool-tuning-run03-50vu-fallback-public-lock-wait-2s.md`
- 상세 결과: `results/06-pool-tuning/delivery-assignment-pool-tuning-run04-80vu-fallback-public-lock-wait-2s.md`
- 상세 결과: `results/06-pool-tuning/delivery-assignment-pool-tuning-run05-100vu-fallback-public-lock-wait-2s.md`

- `pool-tuning run01`: Hikari pool 설정 적용은 확인했지만 100VU 테스트는 연속 502로 51.6초 만에 중단했다.
- 중단 원인은 `DeliveryExternalService`의 Resilience4j fallback 메서드가 `private`이라 fallback 호출 시 `IllegalAccessException`이 발생한 것이다.
- 이 run은 성능 비교 결과로 사용하지 않고, fallback 접근 오류 수정 후 같은 100VU 조건으로 재측정한다.
- 상세 결과: `results/06-pool-tuning/delivery-assignment-pool-tuning-run01-100vu-aborted-fallback-access.md`

## 최신 실험 요약

- `run06`: mixed bulk upsert로 `COMPANY_DELIVERY`와 `HUB_DELIVERY` 집계 증가 write를 2회에서 1회로 축소했다.
- 집계 증가 계측값은 기존 개별 bulk upsert 합산 약 1.227ms에서 mixed bulk upsert 0.821ms로 줄었다.
- 하지만 100VU end-to-end 결과는 TPS 19.52 req/s, p95 5.99s, p99 9.11s, lock timeout 1건으로 개선되지 않았다.
- 따라서 현재 100VU 병목은 집계 증가 write보다 company lock wait와 Hikari connection pending 영향이 더 큰 것으로 판단한다.
- 상세 결과: `results/05-mixed-bulk-upsert/delivery-assignment-aggregate-table-run06-100vu-mixed-bulk-upsert-lock-wait-2s.md`

배송 기사 배정 성능 개선 작업 공간이다.  
최적화 전 baseline, 구조 변경 단계별 결과, 최종 검증 결과를 누적한다.

## 목표

- 배송 기사 배정 구간의 응답 지연 원인을 락 대기, DB connection 대기, 내부 로직 비용으로 분리
- 동일한 baseline seed와 부하 조건으로 구조 변경 전후 차이를 비교

## 고정 Baseline

기준 seed는 `db/seed/14-reset-delivery-perf-baseline.sql` 로 고정한다.

이 baseline은 다음 성격을 가진다.

- delivery history 30,000건
- active delivery 3,600건
- route history 67,200건
  - history 60,000건
  - active 7,200건
- outbox 33,600건
- delivery manager pool
  - Seoul `HUB_DELIVERY` 1,500명
  - Busan `COMPANY_DELIVERY` 900명
  - Incheon `COMPANY_DELIVERY` 900명

이 seed는 내부 쿼리 비용과 활성 배정 집계 비용이 드러나도록 유지하면서, 평균 부하 비교용 baseline으로 사용한다.

## 최적화 순서

1. baseline 고정
   - seed: `14-reset-delivery-perf-baseline.sql`
   - think time: `SLEEP_SECONDS=0`
   - 동일한 stage 사용
2. 현재 구조 baseline 측정
   - distributed 20VU
   - distributed 50VU
   - distributed 80VU 또는 100VU
   - concentrated 50VU hotspot 확인
3. `flush/saveAndFlush` 최소화
4. 집계 테이블 도입
5. 집계 테이블 기반 bulk upsert 전환
6. `COMPANY_DELIVERY` / `HUB_DELIVERY` mixed bulk upsert 전환
7. Hikari pool 조정과 fallback 접근자 수정 후 재측정
8. Redis lock 획득 순서 조정으로 실제 병목 key 확인
9. 집계 테이블 기반 DB row lock 버전 비교
10. 구조 변경 후에도 남는 병목 쿼리와 outbox polling 최적화

## Baseline Test Plan

최적화 전 비교 기준으로 아래 4개 run을 우선 만든다.

### Run 01

- 목적: 현재 구조의 기본 응답 시간과 평균 자원 사용량 확인
- 조건: distributed 20VU, `SLEEP_SECONDS=0`

### Run 02

- 목적: 최적화 전후 비교의 대표 기준값
- 조건: distributed 50VU, `SLEEP_SECONDS=0`

### Run 03

- 목적: 한계 구간과 실패 모드 확인
- 조건: distributed 80VU 또는 100VU, `SLEEP_SECONDS=0`

### Run 04

- 목적: 집중 입력에서의 hotspot / pool exhaustion 확인
- 조건: concentrated 50VU, `SLEEP_SECONDS=0`

## 필수 관측값

Grafana 기준으로 관측한다.

- k6
  - total requests
  - TPS
  - avg / p95 / p99 / max
  - fail rate
- delivery-service
  - process CPU
  - JVM memory
  - GC pause
  - Hikari active / idle / pending / max / timeout
  - repository latency
  - downstream internal API latency
  - HTTP status
  - lock timeout logs
  - warn / error logs
- data-vm host
  - host CPU
  - host memory
  - host load average
  - disk throughput
  - root filesystem usage
- PostgreSQL
  - connections by state
  - locks
  - TPS
  - cache hit ratio
  - database size
  - deadlocks

## Grafana

사용 대시보드

- `Delivery Create Logic Bottleneck`
- `Delivery Assignment Optimization Baseline`

실제 기준 대시보드는 `Delivery Create Logic Bottleneck` 로 사용한다.  
`Delivery Assignment Optimization Baseline` 은 실험 전용 복사본으로 유지한다.

## Baseline 실행 명령

공통 조건:

- `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql`
- `SLEEP_SECONDS=0`
- script: `delivery-create-logic-load.js`

공통 분산 입력:

- `SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001'`
- `RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027'`

### Run 01 - distributed 20VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 02 - distributed 50VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 03 - distributed 80VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

100VU까지 바로 확인할 경우:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 04 - concentrated 50VU hotspot 확인

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 디렉터리 구조

```text
delivery-assignment-optimization/
+-- README.md
+-- results/
|   +-- 00-legacy/
|   +-- 01-baseline/
|   +-- 02-flush-save/
|   +-- 03-aggregate-table/
|   +-- 04-aggregate-bulk-upsert/
|   +-- 05-mixed-bulk-upsert/
|   +-- 06-pool-tuning/
|   +-- 07-redis-lock-order/
|   +-- 08-post-optimization-validation/
|   +-- 09-redis-lock-scope-reduction/
```

- `00-legacy`: 기존 create 결과 보관
- `01-baseline`: 고정 baseline seed 기준 최적화 전 결과
- `02-flush-save`: flush 최소화 전후 비교
- `03-aggregate-table`: 집계 테이블 도입 직후 결과
- `04-aggregate-bulk-upsert`: 집계 테이블 기반 bulk upsert 적용 결과
- `05-mixed-bulk-upsert`: `COMPANY_DELIVERY`와 `HUB_DELIVERY` 집계 write를 한 번으로 합친 결과
- `06-pool-tuning`: Hikari pool 조정과 fallback 접근자 수정 후 재측정 결과
- `07-redis-lock-order`: Redis lock 획득 순서 변경 후 실제 timeout key 확인
- `08-post-optimization-validation`: distributed, stress, 추가 확인
- `09-redis-lock-scope-reduction`: Redis 락 내부 작업을 배정 예약 구간으로 축소한 결과
