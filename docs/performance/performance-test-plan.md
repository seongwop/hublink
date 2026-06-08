# 배송 도메인 성능 테스트와 트러블슈팅 계획

이 문서는 HubLink GCP VM 환경에서 배송 생성, 배송 생성 후속 이벤트 발행, Redis Stream 기반 AI 마감 생성 흐름에 부하를 만들고 병목을 개선하기 위한 테스트 계획을 정리한다.

주문/재고는 배송 이벤트를 만들기 위한 선행 흐름으로만 사용한다. 개선 이력의 중심은 delivery-service와 그 주변 비동기 처리다.

## 테스트 목표

- `delivery.create` Kafka 유입량이 delivery-service 처리량을 초과하는 지점 확인
- 배송 생성 로직의 DB, Redisson lock, Feign 호출 병목 확인
- 배송 생성 DB/락 테스트에서 현재 데이터와 더미데이터 적재 상태의 p95/p99, DB wait, Hikari pending 차이 확인
- `EXPLAIN ANALYZE`로 담당자 배정 쿼리와 배송/경로 저장 전후 조회 쿼리의 최적화 후보 확인
- 배송 생성 성공 후 `p_delivery_outboxes`와 Kafka 발행 worker 병목 확인
- Redis Stream 기반 AI 마감 생성 요청의 backlog, lag, pending 누적 확인
- Kafka lag, Redis Stream lag, DB connection, JVM 지표를 k6 TPS, p95, p99와 연결
- 개선 전후 수치를 비교해 이력서에 적을 수 있는 트러블슈팅 근거 확보
- Gateway 부하는 메인 개선 실험 이후 공통 진입 구간 확인용으로만 수행

## 테스트 축

| 축 | 주요 대상 | 트러블슈팅 방향 |
| --- | --- | --- |
| 배송 Kafka 처리 | `delivery.create`, delivery-service consumer | consumer concurrency, partition, instance 증설 |
| 배송 생성 DB/락 | 담당자 배정 lock, 담당자 가용성 조회, 배송/경로 저장 | 락 key 세분화, 담당자 배정 쿼리 최적화, DB pool 조정, flush 최소화 |
| Delivery Outbox | `p_delivery_outboxes`, `delivery.create.succeed` | polling 주기, batch 크기, Kafka producer batch/linger, 서비스별 병목 분리 |
| Redis Stream AI 처리 | `deadline:requested:stream`, ai-service consumer | read count, consumer 병렬화, pending recovery, stream trim |
| Gateway 공통 부하 | API Gateway, routing, rate limit | 공통 진입 구간 병목 확인용 부록 테스트 |

## 관찰 도구

| 도구 | URL 또는 위치 | 확인 항목 |
| --- | --- | --- |
| Grafana | `http://34.64.89.47:3000` | CPU, memory, JVM, HTTP, DB, Kafka 지표 |
| Prometheus | `http://34.64.89.47:9090` | raw metric query |
| Kafka UI | `http://34.64.89.47:8082` | topic, consumer lag, DLT |
| Zipkin | `http://34.64.89.47:9411` | delivery-service, Feign, Gateway trace |
| PostgreSQL | `hublink` database | delivery, route history, outbox, connection, wait event, `EXPLAIN ANALYZE` |
| Redis | `10.10.0.40:6379` | stream length, lag, pending, consumer group |
| k6 | `hublink-load-test-vm` | TPS, p95, p99, 실패율 |

## k6 핵심 지표

| 지표 | 의미 | 확인 기준 |
| --- | --- | --- |
| `http_reqs` | HTTP 요청 수 | 테스트 총량 |
| `http_req_duration` | HTTP 응답 시간 | avg, p95, p99 |
| `http_req_failed` | HTTP 실패율 | 1% 이상이면 원인 확인 |
| `iterations` | 시나리오 반복 수 | 실제 요청량 계산 |
| `checks` | 응답 검증 성공률 | 실패 응답 분리 |

TPS 계산:

```text
TPS = http_reqs / test_duration_seconds
Delivery Event TPS = delivery.create 메시지 수 / test_duration_seconds
Delivery Create TPS = 생성된 delivery row 수 / test_duration_seconds
Outbox Publish TPS = PUBLISHED outbox row 수 / test_duration_seconds
Redis Stream TPS = 처리 완료 stream message 수 / test_duration_seconds
```

비동기 lag 기록 기준:

```text
Kafka lag 최대값 = 테스트 중 delivery.create consumer lag 최대치
Kafka lag 회복 시간 = 부하 종료 후 delivery.create lag가 0 또는 기준값으로 돌아오기까지 걸린 시간
Redis Stream lag 최대값 = XINFO GROUPS 기준 stream lag 최대치
Redis backlog 회복 시간 = 부하 종료 후 stream lag가 0 또는 기준값으로 돌아오기까지 걸린 시간
Outbox 회복 시간 = 부하 종료 후 PENDING outbox가 0 또는 기준값으로 돌아오기까지 걸린 시간
```

## 공통 테스트 단계

| 단계 | VU | 시간 | 목적 |
| --- | ---: | --- | --- |
| Smoke | 1 | 1분 | 스크립트, 인증, seed 확인 |
| Baseline | 5~10 | 5분 | 정상 기준선 수집 |
| Load | 30~50 | 10분 | 일반 부하 처리 확인 |
| Stress | 80~150 | 10분 | 병목 발생 지점 확인 |
| Recovery | 부하 종료 후 | 5~10분 | lag, backlog 회복 시간 측정 |

공통 threshold 예시:

```javascript
export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};
```

## 데이터 볼륨 전략

배송 생성 DB/락 병목은 데이터가 적을 때와 누적됐을 때의 결과가 달라질 수 있으므로 현재 데이터 기준 baseline과 더미데이터 적재 후 결과를 분리해서 기록한다.

| 단계 | 데이터 상태 | 목적 |
| --- | --- | --- |
| 1차 | 현재 테스트 DB 상태 | 기본 p95/p99, Hikari pending, lock wait 기준선 확보 |
| 2차 | 배송/경로 이력 1만 건 수준 | 데이터 증가가 담당자 배정 쿼리와 write 지연에 주는 영향 확인 |
| 3차 | 배송/경로 이력 10만 건 수준 | 테이블 누적 상태에서 DB wait event와 p99 증가 여부 확인 |
| 4차 | 필요 시 50만~100만 건 수준 | 이력서용 병목 재현 조건 확보와 개선 전후 수치 비교 |

더미데이터는 배송 row만 늘리지 않고 담당자 배정 쿼리가 실제로 참조하는 데이터 분포를 같이 만든다.

```text
배송 담당자 수
허브별 담당자 분포
배송 상태별 데이터
배송 경로 이력
도착 회사와 목적지 허브 분포
```

락 병목을 볼 때는 같은 `RECEIVER_COMPANY_ID`로 요청을 몰고, 쿼리 병목을 볼 때는 `RECEIVER_COMPANY_IDS`를 여러 개 지정해 lock 영향을 낮춘다.

## 이번 라운드 우선순위

이번 테스트는 Gateway보다 배송 도메인 병목을 먼저 분리한다.

```text
1. 배송 Kafka 유입량 baseline/stress
2. 배송 생성 DB/락 병목 확인
3. Delivery Outbox 발행 병목 확인
4. Redis Stream 기반 AI 마감 생성 처리량 확인
5. Gateway 공통 부하 부록 테스트
6. Spike/Soak 등 추가 테스트
```

## 테스트 1. 배송 Kafka 유입량 한계

대상 흐름:

```text
POST /api/v1/orders
-> stock 성공 흐름
-> delivery.create
-> delivery-service consumer
-> 배송 생성
```

k6 script:

```bash
./run-k6.sh delivery-create-kafka-load.js
```

목적:

- `delivery.create` 유입량이 delivery-service 처리량을 초과하는 지점 확인
- Kafka consumer lag가 쌓이는 조건 확인
- delivery-service 단일 인스턴스 처리 한계 확인
- Kafka 설정 개선 전후 Delivery Create TPS 비교

k6 확인:

- 주문 요청 TPS
- p95/p99
- 실패율
- checks 성공률

Kafka UI 확인:

```text
delivery.create
delivery.create.succeed
delivery.create.failed
delivery.create.dlq
```

DB 확인:

```sql
select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_delivery
group by minute
order by minute desc;
```

Grafana/Zipkin 확인:

- delivery-service CPU/heap
- Kafka broker CPU/network
- `delivery.create` consumer lag
- PostgreSQL write 부하
- Hikari active/pending connection

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| `delivery.create` lag 지속 증가 | Kafka listener concurrency 증가 |
| concurrency 증가 후에도 lag 증가 | topic partition 증가, delivery-service instance 증설 |
| CPU 100% 지속 | 배송 생성 로직 최적화, Feign 호출 비용 확인 |
| Hikari pending 증가 | DB connection pool 조정, 배송 생성 DB/락 병목 분리 |
| failed/DLQ 증가 | 메시지 검증, 예외 분류, retry/DLQ 정책 조정 |

개선 실험:

```text
1차: 현재 설정 기준 lag와 Delivery Create TPS 기록
2차: Kafka listener concurrency 증가 후 재측정
3차: delivery.create partition 증가 후 재측정
4차: delivery-service instance 증설 후 재측정
5차: DB connection pool과 배송 생성 DB/락 병목 분리 후 재측정
```

비교 항목:

```text
Delivery Event TPS
Delivery Create TPS
consumer lag 최대값
lag 회복 시간
p95/p99
delivery-service CPU/heap
DB active/pending connection
```

## 테스트 2. 배송 생성 DB/락 병목

대상 흐름:

```text
POST /internal/deliveries
-> 배송 경로 조회
-> 허브 매니저 조회
-> 배송 담당자 조회/배정
-> Redisson lock 획득
-> 담당자 가용성 DB 조회
-> 배송 row 저장
-> 배송 경로 row 저장
-> Redis Stream 이벤트 등록
-> delivery outbox 저장
```

k6 script:

```bash
DELIVERY_BASE_URL=http://10.10.0.30:19099 ./run-k6.sh delivery-create-logic-load.js
```

테스트 의도:

이 테스트의 메인은 배송 생성 로직 전체가 아니라 **DB connection 대기와 Redisson lock 경합을 의도적으로 드러내는 것**이다. 같은 `RECEIVER_COMPANY_ID` 또는 같은 목적지 허브로 요청을 몰아 담당자 배정 lock key가 겹치게 만들고, 동시에 배송/경로 저장과 담당자 가용성 조회가 DB connection pool을 얼마나 점유하는지 확인한다.

데이터 볼륨별 진행:

```text
1차: 현재 데이터 상태에서 baseline 측정
2차: 배송/경로 이력 1만 건 적재 후 같은 부하 재측정
3차: 배송/경로 이력 10만 건 적재 후 같은 부하 재측정
4차: 필요 시 50만~100만 건 적재 후 병목 재현 조건 확보
```

더미데이터 적재 후에도 같은 `STAGES`, `SLEEP_SECONDS`, `RECEIVER_COMPANY_ID` 조건을 유지해야 데이터 증가 영향만 분리할 수 있다. 쿼리 병목 확인 시에는 `RECEIVER_COMPANY_IDS`를 여러 개 지정해 lock 경합을 낮춘 뒤 DB wait와 p95/p99 변화를 따로 본다.

목적:

- 동일 목적지 허브 요청 집중 시 Redisson lock wait와 timeout 확인
- Hikari active/pending connection 증가 조건 확인
- 담당자 가용성 조회와 배송/경로 저장 쿼리의 DB wait event 확인
- 현재 데이터와 더미데이터 적재 상태의 p95/p99, DB wait event, Hikari pending 차이 확인
- `EXPLAIN ANALYZE`로 담당자 배정 쿼리의 scan, sort, buffer 사용량, 실제 실행 시간 확인
- 담당자 배정 lock key 세분화로 같은 key 경합을 줄일 수 있는지 확인
- 담당자 배정 쿼리 최적화로 DB wait와 p95/p99를 줄일 수 있는지 확인
- DB pool 조정으로 Hikari pending과 connection 대기 시간을 줄일 수 있는지 확인
- 배송/경로 저장 시 flush 최소화로 write 지연을 줄일 수 있는지 확인
- hub/user Feign 호출 지연이 DB/락 병목에 영향을 주는지 보조 확인

확인 SQL:

```sql
select state, count(*)
from pg_stat_activity
group by state;

select wait_event_type, wait_event, count(*)
from pg_stat_activity
where wait_event is not null
group by wait_event_type, wait_event
order by count(*) desc;

select delivery_manager_id, count(*)
from delivery_service.p_delivery
group by delivery_manager_id
order by count(*) desc;

select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_delivery
group by minute
order by minute desc;

select delivery_manager_id, count(*)
from delivery_service.p_delivery_route_histories
group by delivery_manager_id
order by count(*) desc;
```

EXPLAIN ANALYZE 확인:

실제 SQL은 Hibernate SQL 로그, Zipkin span, PostgreSQL `pg_stat_statements`에서 담당자 배정 구간의 쿼리를 추출해 사용한다.

```sql
EXPLAIN (ANALYZE, BUFFERS)
-- 담당자 배정 후보 조회 쿼리
;

EXPLAIN (ANALYZE, BUFFERS)
-- 담당자 가용성 조회 쿼리
;

EXPLAIN (ANALYZE, BUFFERS)
-- 배송 생성 전후 경로/허브 조회 쿼리
;
```

확인 항목:

```text
actual time
rows / rows removed by filter
Sort Method / Sort Key
Nested Loop 반복 횟수
Buffers shared hit/read
planning time / execution time
```

`EXPLAIN ANALYZE` 결과는 바로 개선 결론으로 쓰지 않고 k6 결과와 같이 비교한다. 예를 들어 담당자 배정 쿼리의 execution time이 증가하고 동시에 k6 p99, Hikari pending, DB wait event가 증가할 때 담당자 배정 쿼리 최적화 후보로 확정한다.

Grafana/Zipkin 확인:

- Hikari active/pending connection
- DB wait event
- delivery-service HTTP p95/p99
- Redisson lock timeout 로그
- Feign client span duration
- delivery-service heap/GC pause
- domain-b VM CPU

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| lock wait 또는 timeout 증가 | lock key 세분화, lock 보유 구간 축소, 담당자 배정 로직 최적화 |
| Hikari pending 증가 | DB pool 조정, connection 점유 구간 축소, 외부 호출 분리 |
| 담당자 배정 쿼리 지연 | 담당자 배정 쿼리 최적화, 정렬/필터 조건 점검, 불필요 조회 제거 |
| DB write 지연 | flush 최소화, saveAll/batch 가능성 검토 |
| 특정 배송 기사 편중 | 배정 알고리즘 개선, 정렬 기준 점검 |
| Feign span 지연 | hub/user 조회 캐싱, timeout 조정, 호출 횟수 축소 |
| heap 상승 | DTO 변환 비용 확인, 불필요한 객체 생성 축소 |

개선 실험:

```text
1차: 현재 데이터와 같은 RECEIVER_COMPANY_ID 기준 p95/p99, Hikari pending, lock timeout 기록
2차: 배송/경로 이력 1만 건 적재 후 같은 부하 재측정
3차: 배송/경로 이력 10만 건 적재 후 같은 부하 재측정
4차: RECEIVER_COMPANY_IDS 분산 기준과 비교해 lock 경합 영향 분리
5차: 담당자 배정 구간 SQL 추출 후 EXPLAIN ANALYZE로 실행 계획과 buffer 사용량 확인
6차: 담당자 배정 쿼리 최적화 후 DB wait event와 p95/p99 재측정
7차: DB pool 조정 후 Hikari pending과 p99 재측정
8차: lock key 세분화 또는 lock 보유 구간 조정 후 lock wait 재측정
9차: flush 최소화 후 DB write 지연과 Delivery Create TPS 재측정
10차: Feign 호출 캐싱 또는 호출 수 축소 후 보조 지표 재측정
```

비교 항목:

```text
데이터 볼륨
Delivery Create TPS
HTTP p95/p99
DB active/pending connection
DB wait event 최대값
EXPLAIN execution time
EXPLAIN shared hit/read buffer
lock timeout 수
lock wait 의심 로그 수
Feign span p95
delivery row 생성량
route history row 생성량
```

## 테스트 3. Delivery Outbox 발행 병목

대상 흐름:

```text
배송 생성 성공
-> p_delivery_outboxes 저장
-> delivery outbox worker 조회
-> Kafka delivery.create.succeed 발행
-> order-service 결과 consume
```

k6 script:

```bash
DELIVERY_BASE_URL=http://10.10.0.30:19099 ./run-k6.sh delivery-outbox-publish-load.js
```

목적:

- 배송 생성 성공 후 outbox backlog가 쌓이는 조건 확인
- outbox worker의 Kafka 발행 처리량 확인
- `delivery.kafka.outbox.fixed-delay-ms` 조정 효과 확인
- Kafka producer `batch.size`, `linger.ms`, compression 조정 효과 확인
- 배송 생성 병목과 outbox 발행 병목을 분리해 기록

확인 SQL:

```sql
select status, count(*)
from delivery_service.p_delivery_outboxes
group by status;

select date_trunc('minute', created_at) as minute, status, count(*)
from delivery_service.p_delivery_outboxes
group by minute, status
order by minute desc;
```

Kafka UI 확인:

```text
delivery.create.succeed
delivery.create.failed
delivery.create.dlq
```

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| outbox PENDING 지속 증가 | fixed-delay 단축, 조회 batch 크기 조정 |
| publish timeout 증가 | Kafka broker 상태 확인, producer timeout 조정 |
| succeed topic 발행 TPS 낮음 | producer batch.size, linger.ms, compression 조정 |
| DB pending 증가 | outbox 조회/상태 변경 구간 분리, polling batch 크기 조정 |
| 배송 생성 TPS는 충분하지만 주문 완료 지연 | outbox worker 병목 분리 개선 |

개선 실험:

```text
1차: 현재 설정 기준 outbox backlog와 publish TPS 기록
2차: delivery.kafka.outbox.fixed-delay-ms 단축 후 재측정
3차: outbox 조회 batch 크기 조정 후 재측정
4차: Kafka producer batch.size / linger.ms 조정 후 재측정
5차: 배송 생성 TPS와 Outbox Publish TPS를 비교해 서비스별 병목 분리
```

비교 항목:

```text
Outbox Publish TPS
PENDING outbox 최대값
PENDING 회복 시간
delivery.create.succeed 발행량
Kafka send 실패율
delivery-service CPU/heap
DB active/pending connection
```

## 테스트 4. Redis Stream 기반 AI 마감 생성 처리량

대상 흐름:

```text
delivery-service 테스트 API
-> deadline:requested:stream
-> ai-service consume
-> AI 마감 생성
-> deadline:generated:stream
-> delivery-service/slack-service consume
```

k6 script:

```bash
./run-k6.sh delivery-ai-deadline-stream-load.js
```

참고:

```text
ai-deadline-request-load.js는 협업자 작성 스크립트이므로 수정하지 않는다.
배송 도메인 개선 테스트에서는 delivery-ai-deadline-stream-load.js를 사용한다.
```

목적:

- delivery-service 테스트 API를 통한 Stream 주입 처리량 확인
- ai-service consumer 처리량이 주입량을 따라가지 못하는 조건 확인
- requested stream lag와 pending entry 누적 확인
- generated stream 발행량과 AI Consume TPS 확인
- Redis Stream consumer 설정 개선 전후 처리량 비교

Redis 확인:

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XINFO CONSUMERS deadline:requested:stream ai-service-group
```

DB 확인:

```sql
select status, count(*)
from ai_service.p_ai_message
group by status;

select date_trunc('minute', created_at) as minute, count(*)
from ai_service.p_ai_message
group by minute
order by minute desc;
```

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| requested stream lag 지속 증가 | ai-service consumer 주기 단축, read count 조정 |
| pending entry 증가 | ack 위치 확인, pending recovery 보강 |
| AI Consume TPS 낮음 | consumer 병렬화, DB 저장 로직 확인 |
| generated stream 발행 지연 | Redis producer, 결과 발행 예외 확인 |
| Redis memory 증가 | stream trim 정책 검토 |

개선 실험:

```text
1차: 현재 설정 기준 AI Consume TPS 기록
2차: consumer read count 또는 polling 주기 조정 후 재측정
3차: ai-service consumer 병렬화 후 재측정
4차: ai-service instance 증설 후 재측정
5차: pending recovery와 stream trim 정책 적용 후 재측정
```

비교 항목:

```text
Redis Stream TPS
requested stream lag 최대값
pending entry 최대값
backlog 회복 시간
generated stream 증가량
AI Consume TPS
ai-service CPU/heap
Redis memory
```

## 참고. Gateway 진입 구간 부하

Gateway는 메인 개선 실험 이후 공통 진입 구간이 병목인지 확인하는 부록 테스트로 실행한다.

k6 script:

```bash
./run-k6.sh gateway-appendix-load.js
```

대상 예시:

```bash
PATHS=/actuator/health,/api/v1/orders ./run-k6.sh gateway-appendix-load.js
```

확인 항목:

```text
Gateway TPS
Gateway p95/p99
route별 4xx/5xx 비율
platform VM CPU/heap
downstream span duration
```

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| 모든 route p95 증가 | Gateway CPU/heap, rate limit 설정 확인 |
| 특정 route만 지연 | downstream service 또는 DB query 확인 |
| 5xx 증가 | timeout, service health, retry 정책 점검 |
| 순간 요청 폭주 | rate limit, bulkhead, queue 제한 검토 |

## 추가 테스트

기본 1~4번 테스트 이후 필요할 때 진행한다.

### 전체 흐름 테스트

대상 흐름:

```text
Gateway
-> order-service
-> Kafka
-> delivery-service
-> Redis Stream
-> ai-service
-> slack-service
```

목적:

- Gateway, Kafka, delivery-service, Redis Stream을 모두 포함한 end-to-end 지연 확인
- 개별 병목 개선이 전체 체감 시간에 주는 영향 확인

### Spike

목적:

- 갑작스러운 배송 이벤트 증가 시 Kafka lag와 DB connection 회복력 확인
- spike 이후 lag가 정상 범위로 감소하는지 확인

### Soak

목적:

- 장시간 배송 이벤트 처리 중 heap, connection, Kafka lag, Redis pending 누적 확인
- 로그, Kafka data, Redis Stream data 누적에 따른 disk/memory 사용량 확인

### Redis 장애와 배송 흐름 영향

목적:

- Redis 장애가 배송 생성 transaction에 영향을 주는지 확인
- Redis 복구 후 AI/Slack 이벤트 재처리 가능 여부 확인
- 알림 실패가 배송 생성 실패로 전파되는지 확인

## 병목별 확인 기준

| 병목 | k6 증상 | Grafana/Prometheus 증상 | 추가 확인 |
| --- | --- | --- | --- |
| Kafka consumer | HTTP 이후 배송 생성 지연 | consumer lag 증가 | Kafka UI lag |
| 배송 생성 로직 | 배송 생성 p95/p99 증가 | delivery-service CPU 또는 DB pending 증가 | Zipkin, DB wait event |
| Delivery Outbox | 배송 생성 후 주문 완료 지연 | outbox PENDING 증가 | `p_delivery_outboxes`, succeed topic |
| Redis Stream | AI/Slack 지연 | Redis memory/command 지연 | XINFO, XPENDING |
| DB connection | 배송 생성 지연, timeout | Hikari pending 증가 | `pg_stat_activity` |
| Feign 호출 | 배송 생성 일부 단계 지연 | 특정 span duration 증가 | Zipkin trace |
| Gateway CPU | 모든 API p95 증가 | platform CPU 상승 | Gateway log, route별 latency |

## Prometheus query 예시

HTTP 요청 수:

```promql
sum(rate(http_server_requests_seconds_count[1m])) by (application, uri)
```

HTTP p95:

```promql
histogram_quantile(
  0.95,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le, application, uri)
)
```

JVM heap:

```promql
sum(jvm_memory_used_bytes{area="heap"}) by (application)
```

GC pause:

```promql
sum(rate(jvm_gc_pause_seconds_sum[5m])) by (application)
```

CPU:

```promql
process_cpu_usage
```

Hikari active/pending:

```promql
hikaricp_connections_active
hikaricp_connections_pending
```

Kafka consumer lag는 Kafka exporter 또는 Kafka UI에서 확인한다. Prometheus exporter가 연결되어 있지 않다면 Kafka UI 기준으로 기록한다.

## DB 병목 확인 SQL

connection 상태:

```sql
select state, count(*)
from pg_stat_activity
group by state;
```

wait event:

```sql
select wait_event_type, wait_event, count(*)
from pg_stat_activity
where wait_event is not null
group by wait_event_type, wait_event
order by count(*) desc;
```

배송 생성량:

```sql
select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_delivery
group by minute
order by minute desc;
```

배송 기사 배정 분포:

```sql
select delivery_manager_id, count(*)
from delivery_service.p_delivery
group by delivery_manager_id
order by count(*) desc;
```

outbox 상태:

```sql
select status, count(*)
from delivery_service.p_delivery_outboxes
group by status;
```

## 결과 기록 양식

| 항목 | 값 |
| --- | --- |
| 테스트 일시 |  |
| 배포 commit |  |
| 테스트명 |  |
| 대상 API 또는 topic |  |
| k6 script |  |
| VU / duration |  |
| 데이터 상태 | 현재 데이터 / 더미데이터 |
| 더미데이터 규모 |  |
| 총 요청 수 |  |
| HTTP TPS |  |
| Delivery Event TPS |  |
| Delivery Create TPS |  |
| Outbox Publish TPS |  |
| Redis Stream TPS |  |
| 평균 응답 시간 |  |
| p95 |  |
| p99 |  |
| 실패율 |  |
| checks 성공률 |  |
| `delivery.create` lag 최대값 |  |
| Kafka lag 회복 시간 |  |
| delivery row 생성량 |  |
| outbox PENDING 최대값 |  |
| outbox 회복 시간 |  |
| Redis stream length |  |
| Redis stream lag 최대값 |  |
| Redis pending entry |  |
| Redis backlog 회복 시간 |  |
| DB active connection 최대값 |  |
| DB pending connection 최대값 |  |
| EXPLAIN execution time |  |
| EXPLAIN shared hit/read buffer |  |
| delivery-service CPU 최대값 |  |
| delivery-service heap 최대값 |  |
| GC pause 특이점 |  |
| 적용한 개선 |  |
| 개선 전 결과 |  |
| 개선 후 결과 |  |
| 남은 이슈 |  |

## 추천 진행 순서

1. [scenario-test-plan.md](../scenario/scenario-test-plan.md)의 배송 생성 성공 시나리오 통과 확인
2. `delivery-create-kafka-load.js`로 배송 Kafka 유입량 baseline 측정
3. 배송 Kafka 유입량 stress 측정
4. Kafka consumer concurrency, partition, instance 개선 실험
5. 현재 데이터 기준 `delivery-create-logic-load.js`로 배송 생성 DB/락 baseline 측정
6. 배송/경로 이력 더미데이터 적재 후 같은 부하로 배송 생성 DB/락 재측정
7. 담당자 배정 구간 SQL 추출 후 `EXPLAIN ANALYZE`로 쿼리 병목 확인
8. 배송 생성 lock key 세분화, 담당자 배정 쿼리 최적화, DB pool 조정, flush 최소화 개선 실험
9. `delivery-outbox-publish-load.js`로 Delivery Outbox 발행 baseline 측정
10. Delivery Outbox polling 또는 Kafka publish 설정 개선 실험
11. `delivery-ai-deadline-stream-load.js`로 Redis Stream AI 처리량 baseline 측정
12. Redis Stream consumer 설정 개선 실험
13. `gateway-appendix-load.js`로 Gateway 공통 진입 구간 부록 테스트
14. 필요 시 전체 흐름, Spike, Soak 테스트 진행
