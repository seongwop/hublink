# 배송 도메인 성능 테스트와 트러블슈팅 계획

이 문서는 HubLink GCP VM 환경에서 Gateway, delivery-service Kafka 처리, AI/Slack Redis Stream 처리를 중심으로 부하를 만들고 병목을 개선하기 위한 테스트 계획을 정리한다.

## 관련 문서

| 문서 | 내용 |
| --- | --- |
| [scenario-test-plan.md](scenario-test-plan.md) | 배송 도메인 기능 시나리오와 장애 유도 |

## 테스트 목표

- Gateway에 부하를 주고 요청 진입 구간의 병목 확인
- `delivery.create` Kafka 이벤트 처리량이 유입량을 따라가지 못하는 상황 재현
- delivery-service 클러스터링, Kafka partition, consumer 설정 조정 효과 비교
- 배송 기사 배정과 배송 생성 로직의 DB/Feign/JVM 병목 확인
- Redis Stream 기반 AI/Slack 처리량 부족과 pending 누적 상황 재현
- Redis Stream consumer 설정과 구현 개선으로 처리량과 지연 시간 개선
- k6 결과의 TPS, p95, p99, 실패율을 Grafana/Kafka UI/Redis/DB 지표와 연결

## 테스트 축

| 축 | 주요 대상 | 트러블슈팅 방향 |
| --- | --- | --- |
| Gateway 부하 | API Gateway, 인증 필터, routing | VM 스펙, Gateway 설정, timeout, rate limit, 구현 개선 |
| 배송 Kafka 처리 | `delivery.create`, delivery-service consumer | partition, consumer concurrency, consumer instance 증설 |
| 배송 생성 로직 | 배송 경로 조회, 배송 기사 배정, 배송 row 저장 | DB index, query, Feign 호출, transaction 범위 개선 |
| Redis Stream 처리 | delivery -> ai/slack stream | consumer 수, read count, ack, pending recovery, stream trim |

주문/재고는 배송 이벤트를 만들기 위한 선행 흐름으로만 사용한다. 트러블슈팅의 중심은 delivery-service와 그 주변 비동기 통신이다.

## 관찰 도구

| 도구 | URL | 확인 항목 |
| --- | --- | --- |
| Grafana | `http://34.64.89.47:3000` | CPU, memory, JVM, HTTP, DB, Kafka 지표 |
| Prometheus | `http://34.64.89.47:9090` | raw metric query |
| Kafka UI | `http://34.64.89.47:8082` | delivery topic, consumer lag, DLT |
| Zipkin | `http://34.64.89.47:9411` | Gateway, delivery-service, Feign trace |
| Swagger | `http://34.50.23.39:19091/swagger-ui/index.html` | 단건 API 확인 |
| PostgreSQL | `hublink` database | delivery, route, manager, connection, lock |
| Redis | Redis Stream | stream length, pending entry, ack 상태 |

## k6 핵심 지표

| 지표 | 의미 | 확인 기준 |
| --- | --- | --- |
| `http_reqs` | 전체 HTTP 요청 수 | 테스트 총량 |
| `http_req_duration` | 요청 전체 응답 시간 | avg, p95, p99 확인 |
| `http_req_failed` | HTTP 실패율 | 1% 이상이면 원인 확인 |
| `iterations` | 시나리오 반복 횟수 | 실질 처리량 계산 |
| `iteration_duration` | 한 시나리오 수행 시간 | 복합 흐름 병목 확인 |
| `checks` | 검증 성공률 | 응답 코드와 body 검증 |

TPS 계산:

```text
TPS = http_reqs / test_duration_seconds
```

배송 이벤트 처리량:

```text
Delivery Event TPS = delivery.create 메시지 수 / test_duration_seconds
Delivery Create TPS = 생성된 delivery row 수 / test_duration_seconds
```

Redis Stream 처리량:

```text
Redis Stream TPS = 처리 완료된 stream message 수 / test_duration_seconds
```

## 공통 테스트 단계

| 단계 | VU | 시간 | 목적 |
| --- | ---: | --- | --- |
| Smoke | 1 | 1분 | 스크립트, 인증, seed 확인 |
| Baseline | 5~10 | 5분 | 정상 기준선 수집 |
| Load | 30~50 | 10분 | 일반 부하 처리 확인 |
| Stress | 80~150 | 10분 | 병목 발생 지점 확인 |

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

## 테스트 1. Gateway 진입 구간 부하

대상:

```text
GET /actuator/health
GET /api/v1/hubs
GET /api/v1/products
GET /api/v1/deliveries
```

배송 조회 API만 보는 테스트가 아니라 Gateway 자체가 높은 요청량을 받을 때 어떻게 무너지는지 확인한다. downstream을 거의 타지 않는 health 요청, 조회 API, 인증이 필요한 API를 나누어 테스트한다.

목적:

- Gateway CPU/heap 병목 확인
- JWT 인증 필터 비용 확인
- route별 downstream 지연 전파 확인
- timeout, connection pool, rate limit 후보 도출

k6 확인:

- Gateway TPS
- route별 p95/p99
- 4xx/5xx 비율
- `checks` 성공률

Grafana/Zipkin 확인:

- platform VM CPU
- api-gateway heap
- Gateway HTTP p95/p99
- downstream service span duration
- downstream service unavailable 여부

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| Gateway CPU 90% 이상 지속 | platform VM 스펙 조정, Gateway 인스턴스 분리, 인증 필터 비용 개선 |
| p99 급증 | timeout 설정 점검, downstream span 확인, connection 대기 확인 |
| 5xx 증가 | Gateway timeout 조정, service health check, 재시도 정책 점검 |
| 특정 route만 느림 | 해당 downstream 서비스 또는 DB query 개선 |
| 순간 요청 폭주 | rate limit, bulkhead, queue 제한 검토 |

## 테스트 2. 배송 Kafka 유입량 한계

대상:

```text
POST /api/v1/orders
-> delivery.create
-> delivery-service consumer
```

주문 API는 배송 이벤트를 만들기 위한 진입점으로만 사용한다.

목적:

- `delivery.create` 유입량이 delivery-service 처리량을 초과하는 지점 확인
- Kafka consumer lag가 쌓이는 조건 확인
- delivery-service 단일 인스턴스 처리 한계 확인

k6 확인:

- 주문 요청 TPS
- p95/p99
- 실패율

Kafka UI 확인:

```text
delivery.create
delivery.create.succeed
delivery.create.failed
delivery.create-dlt 또는 관련 DLT topic
```

DB 확인:

```sql
select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_delivery
group by minute
order by minute desc;
```

Grafana 확인:

- delivery-service CPU/heap
- Kafka broker CPU
- Kafka network in/out
- `delivery.create` consumer lag
- PostgreSQL write 부하
- Hikari active/pending connection

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| `delivery.create` lag 지속 증가 | delivery-service consumer concurrency 증가 |
| concurrency 증가 후에도 lag 증가 | topic partition 증가, delivery-service 인스턴스 증설 |
| CPU 100% 지속 | 배송 생성 로직 최적화, Feign 호출 비용 확인 |
| Hikari pending 증가 | DB connection pool 조정, query/index 개선 |
| DLT 증가 | 메시지 검증, 예외 분류, retry/DLT 정책 조정 |

개선 실험:

```text
1차: 현재 설정 기준 lag와 Delivery Create TPS 기록
2차: Kafka listener concurrency 증가 후 재측정
3차: delivery.create partition 증가 후 재측정
4차: delivery-service 컨테이너 인스턴스 증설 후 재측정
5차: DB connection pool과 query 개선 후 재측정
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

## 테스트 3. 배송 기사 배정과 배송 생성 로직 병목

대상 흐름:

```text
delivery.create consume
-> 배송 경로 조회
-> 배송 기사 조회/배정
-> 배송 row 저장
-> 배송 경로 row 저장
-> delivery.create.succeed
```

목적:

- 배송 기사 배정 로직의 DB 부하 확인
- hub/user 등 Feign 호출 지연 확인
- 배송 생성 transaction 처리 시간 확인
- consumer concurrency를 올렸을 때 DB 병목으로 이동하는지 확인

확인 SQL:

```sql
select delivery_manager_id, count(*)
from delivery_service.p_delivery
group by delivery_manager_id
order by count(*) desc;

select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_delivery
group by minute
order by minute desc;
```

Grafana/Zipkin 확인:

- delivery-service p95/p99
- delivery-service heap
- delivery-service GC pause
- Feign client span duration
- DB connection active/pending
- domain-b VM CPU

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| 특정 배송 기사에 편중 | 배정 알고리즘 개선, 정렬 기준 점검 |
| Feign span 지연 | hub/user 조회 캐싱, timeout 조정, 호출 횟수 축소 |
| DB write 지연 | index 추가, transaction 범위 축소, batch 가능성 검토 |
| heap 상승 | 이벤트 DTO 변환 비용 확인, 불필요한 객체 생성 축소 |
| GC pause 증가 | heap 조정, allocation 많은 로직 개선 |

## 테스트 4. delivery-service 장애와 Kafka 복구

대상:

```text
delivery-service 중지
-> delivery.create backlog 생성
-> delivery-service 복구
-> backlog 처리
```

목적:

- delivery-service 장애 시 Kafka 메시지 보존 확인
- 복구 후 consumer lag 회복 속도 확인
- backlog 처리 중 클러스터링 필요 여부 판단

장애 유도:

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml stop delivery-service"
```

복구:

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml start delivery-service"
```

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| 복구 후 lag가 천천히 감소 | delivery-service 인스턴스 증설, concurrency 증가 |
| lag가 줄지 않음 | 반복 예외, DLT 이동, DB 병목 확인 |
| 복구 직후 CPU 100% 지속 | backlog 처리 batch 크기, poll 설정 조정 |
| 중복 배송 생성 | idempotency 보강 |
| DLT 증가 | non-retryable/retryable 예외 분리 |

개선 실험:

```text
1차: 단일 delivery-service 복구 속도 측정
2차: delivery-service 2개 이상 실행 후 복구 속도 측정
3차: topic partition 수와 consumer 수를 맞춘 뒤 복구 속도 측정
4차: max.poll.records, fetch 설정 조정 후 복구 속도 측정
```

## 테스트 5. Redis Stream AI/Slack 처리량 한계

대상 흐름:

```text
배송 생성 성공
-> Redis Stream event
-> ai-service consume
-> slack-service consume
-> Slack 알림 또는 mock 처리
```

목적:

- 배송 생성량 증가 시 Redis Stream 메시지 누적 확인
- ai-service/slack-service consumer 처리량 확인
- pending entry와 ack 누락 확인
- Redis Stream 설정과 consumer 구현 개선 효과 확인

관찰:

- Redis stream length
- consumer group pending entry
- ai-service CPU/heap
- slack-service CPU/heap
- Redis memory
- Redis command latency

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| stream length 지속 증가 | consumer 수 증가, read count 조정 |
| pending entry 증가 | ack 누락 확인, pending recovery 구현 |
| AI 처리 지연 | 외부 AI API timeout, rate limit, fallback/mock 처리 |
| Slack 처리 지연 | Slack API timeout, retry/backoff 조정 |
| Redis memory 증가 | stream trim 정책, 보관 기간 조정 |

개선 실험:

```text
1차: 현재 Redis Stream 처리량 기록
2차: XREAD COUNT 또는 batch 처리 크기 조정 후 재측정
3차: ai/slack consumer 인스턴스 수 증가 후 재측정
4차: pending recovery 로직 적용 후 장애 복구 시간 측정
5차: stream trim 정책 적용 후 Redis memory 변화 측정
```

비교 항목:

```text
Redis Stream TPS
stream length 최대값
pending entry 최대값
pending 회복 시간
ai/slack 처리 성공률
Redis memory
ai/slack CPU/heap
```

## 테스트 6. Redis 장애와 배송 흐름 영향

대상:

```text
Redis 중지
-> 배송 생성 요청
-> 배송 생성 성공 여부 확인
-> AI/Slack 이벤트 실패 범위 확인
```

목적:

- Redis 장애가 배송 생성 본 트랜잭션에 영향을 주는지 확인
- Redis 복구 후 메시지 재처리 가능 여부 확인
- 알림 실패가 배송 실패로 전파되는지 확인

장애 유도:

```bash
gcloud compute ssh hublink-data-monitor-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.data-monitor.yml stop redis"
```

복구:

```bash
gcloud compute ssh hublink-data-monitor-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.data-monitor.yml start redis"
```

트러블슈팅 방향:

| 증상 | 개선 후보 |
| --- | --- |
| 배송 생성까지 실패 | Redis 연계를 핵심 transaction 밖으로 분리 |
| 배송 성공, 알림 실패 | 실패 이벤트 저장, 재발행 로직 검토 |
| 복구 후 메시지 없음 | outbox 또는 재시도 저장소 검토 |
| 복구 후 중복 알림 | idempotency key와 ack 처리 보강 |

## 추가 테스트

기본 테스트 이후 추가로 진행한다.

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
- 배송 생성 성공 시점과 AI/Slack 처리 완료 시점 간 지연 측정
- 특정 구간 개선 후 전체 체감 시간이 줄어드는지 확인

관찰:

- k6 p95/p99
- Delivery Event TPS
- Delivery Create TPS
- Redis Stream TPS
- Kafka lag
- Redis pending entry
- Zipkin trace 전체 span

### Spike

목적:

- 갑작스러운 배송 이벤트 증가 시 Gateway와 delivery-service 회복력 확인
- Kafka lag가 쌓인 뒤 정상 범위로 감소하는지 확인
- DB connection pool이 고갈 후 회복되는지 확인

### Soak

목적:

- 장시간 배송 이벤트 처리 중 heap, connection, Kafka lag, Redis pending 누적 확인
- Prometheus/Grafana 지표 수집 안정성 확인
- 로그와 Kafka data 누적에 따른 disk 사용량 확인

## 병목별 확인 기준

| 병목 | k6 증상 | Grafana/Prometheus 증상 | 추가 확인 |
| --- | --- | --- | --- |
| Gateway CPU | 모든 API p95 증가 | platform CPU 상승 | Gateway 로그, route별 latency |
| delivery-service CPU | 배송 이벤트 처리 지연 | delivery-service CPU 상승 | consumer log, Zipkin span |
| JVM heap | p99 급증 | heap 상승, GC pause 증가 | GC log, heap 변화 |
| DB connection | 배송 생성 지연, timeout | Hikari pending 증가 | `pg_stat_activity` |
| Kafka consumer | HTTP 이후 배송 생성 지연 | consumer lag 증가 | Kafka UI lag |
| Redis Stream | AI/Slack 지연 | Redis memory/command 지연 | stream pending |
| Feign 호출 | 배송 생성 일부 단계 지연 | 특정 span duration 증가 | Zipkin trace |

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

Hikari active connection:

```promql
hikaricp_connections_active
```

Kafka consumer lag는 Kafka exporter 또는 Kafka UI에서 확인한다. Prometheus에 exporter가 연결되어 있지 않다면 Kafka UI를 기준으로 기록한다.

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

## 결과 기록 양식

| 항목 | 값 |
| --- | --- |
| 테스트 일시 |  |
| 배포 commit |  |
| 테스트명 |  |
| 대상 API 또는 topic |  |
| k6 script |  |
| VU / duration |  |
| 총 요청 수 |  |
| HTTP TPS |  |
| Delivery Event TPS |  |
| Delivery Create TPS |  |
| Redis Stream TPS |  |
| 평균 응답 시간 |  |
| p95 |  |
| p99 |  |
| 실패율 |  |
| checks 성공률 |  |
| `delivery.create` lag 최대값 |  |
| lag 회복 시간 |  |
| delivery row 생성량 |  |
| Redis stream length |  |
| Redis pending entry |  |
| DB active connection 최대값 |  |
| delivery-service CPU 최대값 |  |
| delivery-service heap 최대값 |  |
| GC pause 특이점 |  |
| 적용한 개선 |  |
| 개선 전 결과 |  |
| 개선 후 결과 |  |
| 남은 이슈 |  |

## 추천 진행 순서

1. [scenario-test-plan.md](scenario-test-plan.md)의 배송 생성 성공 시나리오 통과 확인
2. Gateway 진입 구간 baseline
3. Gateway 진입 구간 stress
4. 배송 Kafka 유입량 baseline
5. 배송 Kafka 유입량 stress
6. Kafka consumer concurrency 또는 클러스터링 개선 실험
7. 배송 기사 배정과 배송 생성 병목 확인
8. Redis Stream AI/Slack baseline
9. Redis Stream 처리량 개선 실험
10. Redis 장애와 배송 흐름 영향 확인
11. 추가 전체 흐름 테스트
12. 추가 Spike 테스트
13. 추가 Soak 테스트
