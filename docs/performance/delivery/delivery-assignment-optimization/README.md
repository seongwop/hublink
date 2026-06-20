# Delivery Assignment Optimization

배송 기사 배정 성능 개선 전용 작업 공간이다.  
최적화 전 baseline, 구조 변경 단계별 결과, 최종 검증 결과를 한곳에 모은다.

## 목표

- 배송 기사 배정 구간의 응답 지연 원인을 락 대기, DB connection 대기, 외부 연동, 저장 비용으로 분리
- 동일한 baseline seed와 부하 조건으로 구조 변경 전후 차이를 비교

## 확정 Baseline

기준 seed는 `db/seed/14-reset-delivery-perf-baseline.sql` 로 고정한다.

이 baseline은 다음 성격을 가진다.

- delivery history 30,000건
- active delivery 3,600건
- route history 67,200건
  - history 60,000건
  - active 7,200건
- outbox 33,600건
- delivery manager pool
  - Seoul HUB_DELIVERY 300명
  - Busan COMPANY_DELIVERY 300명
  - Incheon COMPANY_DELIVERY 300명

이 seed는 집중 요청에서도 즉시 담당자 고갈로 무너지지 않으면서, 저장 비용과 활성 배정 집계 비용이 함께 드러나도록 유지하는 기준 seed로 사용한다.

## 최적화 순서

1. baseline 고정
   - seed: `14-reset-delivery-perf-baseline.sql`
   - think time: `SLEEP_SECONDS=0`
   - 동일 stage 재사용
2. 현재 구조 baseline 측정
   - concentrated 20VU
   - concentrated 50VU
   - concentrated 80VU 또는 100VU
   - distributed 50VU
3. `flush/saveAndFlush` 최소화
4. 집계 테이블 도입
5. 집계 테이블 기준 배정 로직 전환
6. Redis 락 유지 버전에서 락 범위 분산
7. 집계 테이블 기반 DB 락 버전 비교
8. pool / bucket 단위 락 분리 비교
9. 구조 변경 후에도 남는 병목 쿼리만 후순위 튜닝

## Baseline Test Plan

최적화 전 비교 기준은 아래 4개 run을 우선 만든다.

### Run 01

- 목적: 현재 구조의 기본 응답시간과 경미한 락 경합 확인
- 조건: concentrated 20VU, `SLEEP_SECONDS=0`

### Run 02

- 목적: 최적화 전후 비교의 대표 기준선
- 조건: concentrated 50VU, `SLEEP_SECONDS=0`

### Run 03

- 목적: 한계 구간과 실패 모드 확인
- 조건: concentrated 80VU 또는 100VU, `SLEEP_SECONDS=0`

### Run 04

- 목적: 현실적인 입력 분산 조건 검증
- 조건: distributed 50VU, `SLEEP_SECONDS=0`

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
  - Hikari active / idle / pending / max
  - repository latency
  - downstream internal API latency
  - HTTP status
  - lock timeout logs
  - warn / error logs
- data-vm host
  - host CPU
  - host memory
  - host load average

## Grafana

사용 대시보드

- `Delivery Assignment Optimization Baseline`
- `Delivery Create Logic Bottleneck`

새 baseline 대시보드는 기존 로직 대시보드에 아래를 보강한 버전이다.

- Hikari idle
- data-vm host CPU / memory
- data-vm host load average

## Baseline 실행 명령

공통 조건:

- `PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql`
- `SLEEP_SECONDS=0`
- script: `delivery-create-logic-load.js`

### Run 01 - concentrated 20VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 02 - concentrated 50VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 03 - concentrated 80VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":80},{"duration":"5m","target":80},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

100VU까지 바로 확인할 경우:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

### Run 04 - distributed 50VU

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
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

- `00-legacy`: 기존 create 결과 이관본
- `01-baseline`: 확정 baseline seed 기준 최적화 전 결과
- `02-flush-save`: flush 최소화 전후 비교
- `03-aggregate-table`: 집계 테이블 도입 전후 비교
- `04-redis-lock-split`: Redis 락 범위 분산 비교
- `05-db-lock`: DB 락 대체 버전 비교
- `06-pool-bucket-lock`: pool / bucket 단위 분산 비교
- `07-post-optimization-validation`: distributed, stress, 회귀 확인
