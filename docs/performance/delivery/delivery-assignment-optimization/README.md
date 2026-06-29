# Delivery Assignment Optimization

## 최신 실험 요약

- `run06`: mixed bulk upsert로 `COMPANY_DELIVERY`와 `HUB_DELIVERY` 집계 증가 write를 2회에서 1회로 축소했다.
- 집계 증가 계측값은 기존 개별 bulk upsert 합산 약 1.227ms에서 mixed bulk upsert 0.821ms로 줄었다.
- 하지만 100VU end-to-end 결과는 TPS 19.52 req/s, p95 5.99s, p99 9.11s, lock timeout 1건으로 개선되지 않았다.
- 따라서 현재 100VU 병목은 집계 증가 write보다 company lock wait와 Hikari connection pending 영향이 더 큰 것으로 판단한다.
- 상세 결과: `results/03-aggregate-table/delivery-assignment-aggregate-table-run06-100vu-mixed-bulk-upsert-lock-wait-2s.md`

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
5. 집계 테이블 기반 배정 로직 전환
6. Redis 락 버전에서 락 범위 분산
7. 집계 테이블 기반 DB 락 버전 비교
8. pool / bucket 단위 락 분리 비교
9. 구조 변경 후에도 남는 병목 쿼리만 후순위로 최적화

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
|   +-- 04-redis-lock-split/
|   +-- 05-db-lock/
|   +-- 06-pool-bucket-lock/
|   +-- 07-post-optimization-validation/
```

- `00-legacy`: 기존 create 결과 보관
- `01-baseline`: 고정 baseline seed 기준 최적화 전 결과
- `02-flush-save`: flush 최소화 전후 비교
- `03-aggregate-table`: 집계 테이블 도입 후 비교
- `04-redis-lock-split`: Redis 락 범위 분산 비교
- `05-db-lock`: DB 락 대체 버전 비교
- `06-pool-bucket-lock`: pool / bucket 단위 분산 비교
- `07-post-optimization-validation`: distributed, stress, 추가 확인
