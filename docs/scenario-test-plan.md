# 배송 도메인 시나리오 테스트 계획

이 문서는 HubLink 배포 후 배송 도메인 중심으로 Kafka 이벤트 수신, 배송 기사 배정, 배송 생성, AI/Slack Redis Stream 연계가 정상적으로 동작하는지 검증하기 위한 시나리오를 정리한다.

## 관련 문서

| 문서 | 내용 |
| --- | --- |
| [performance-test-plan.md](performance-test-plan.md) | 배송 중심 성능 테스트와 병목 관찰 |

## 테스트 목표

- `delivery.create` Kafka 이벤트 수신 확인
- 배송 생성 시 배송 경로와 배송 기사 배정 확인
- 배송 생성 성공 이벤트 발행 확인
- 배송 생성 실패 이벤트와 DLQ 흐름 확인
- 배송 이후 AI/Slack Redis Stream 이벤트 흐름 확인
- 장애 발생 시 Kafka, Redis, DB, 로그에서 추적 가능 여부 확인

## 관찰 도구

| 도구 | URL 또는 위치 | 확인 항목 |
| --- | --- | --- |
| Swagger | `http://34.50.23.39:19091/swagger-ui/index.html` | 주문 요청과 배송 API 확인 |
| Eureka | `http://34.50.23.39:19090` | delivery, ai, slack 서비스 등록 |
| Kafka UI | `http://34.64.89.47:8082` | delivery topic, consumer lag, DLT |
| Grafana | `http://34.64.89.47:3000` | delivery-service CPU, heap, HTTP, JVM 지표 |
| Zipkin | `http://34.64.89.47:9411` | 배송 생성 trace, Feign 호출 trace |
| PostgreSQL | `hublink` database | delivery, delivery manager, route row |
| Redis | Redis Stream | AI/Slack 이벤트, pending entry |

## 사전 준비

seed 파일:

```text
db/seed/00-reset-scenarios.sql
db/seed/01-base-scenarios.sql
```

배송 이벤트를 만들기 위한 주문 요청 body:

```text
db/seed/orders/01-success-order.json
db/seed/orders/03-delivery-fail-order.json
```

진행 순서:

1. `00-reset-scenarios.sql` 실행
2. `01-base-scenarios.sql` 실행
3. Swagger에서 로그인
4. 발급된 JWT를 Authorization header에 설정
5. 주문 요청으로 `delivery.create` 이벤트 발생
6. delivery-service 처리 결과 확인
7. Redis Stream 기반 AI/Slack 흐름 확인

로그인 요청:

```json
{
  "username": "buyer-manager",
  "password": "password"
}
```

주문 요청 공통 header:

```text
Authorization: Bearer <TOKEN>
X-Order-Key: <UUID>
```

`X-Order-Key`는 주문 중복 요청을 막기 위한 idempotency key다. 요청마다 새 UUID를 사용한다.

## 시나리오 1. 배송 생성 성공

목적:

```text
정상 주문을 통해 delivery.create 이벤트를 만들고, delivery-service가 배송을 생성하는지 확인
```

요청:

```text
POST /api/v1/orders
Body: db/seed/orders/01-success-order.json
```

기대 흐름:

```text
delivery.create
-> delivery-service consume
-> 배송 경로 조회
-> 배송 기사 배정
-> 배송 생성
-> delivery.create.succeed
-> AI/Slack Redis Stream event
```

Kafka topic:

```text
delivery.create
delivery.create.succeed
```

확인 SQL:

```sql
select order_id, status, departure_hub_id, arrival_hub_id, delivery_manager_id, created_at
from delivery_service.p_delivery
order by created_at desc;

select *
from delivery_service.p_delivery_route
order by created_at desc;
```

문제 추적:

- `delivery.create` 없음: 주문/재고 선행 흐름 또는 Kafka 발행 실패
- 배송 row 없음: delivery-service consumer 실패
- 배송 기사 없음: 배송 기사 seed, 배정 조건, 상태값 확인
- 배송 경로 없음: hub route seed 또는 hub-service 응답 확인
- 성공 이벤트 없음: delivery-service 후속 Kafka 발행 실패
- Redis 이벤트 없음: 배송 생성 이후 AI/Slack 이벤트 발행 실패

## 시나리오 2. 배송 경로 실패

목적:

```text
배송 경로가 없는 요청에서 delivery-service가 실패 이벤트를 발행하는지 확인
```

요청:

```text
POST /api/v1/orders
Body: db/seed/orders/03-delivery-fail-order.json
```

기대 흐름:

```text
delivery.create
-> delivery-service consume
-> 배송 경로 조회 실패
-> delivery.create.failed
```

Kafka topic:

```text
delivery.create
delivery.create.failed
```

확인 SQL:

```sql
select status, count(*)
from delivery_service.p_delivery
group by status;

select id, order_id, status, created_at
from order_service.p_order
order by created_at desc;
```

문제 추적:

- 실패 이벤트 없음: delivery-service 예외 처리 또는 producer 실패
- 주문 상태 미변경: order-service 실패 이벤트 consumer 확인
- 재고 복원 누락: 실패 이벤트 이후 보상 흐름 확인
- DLQ만 발생: message format, orderId 유무, JSON parsing 실패 확인

## 시나리오 3. 배송 이벤트 중복 처리

목적:

```text
같은 delivery.create 이벤트가 중복 수신될 때 배송이 중복 생성되지 않는지 확인
```

진행:

1. 주문 성공 시나리오 실행
2. Kafka UI에서 `delivery.create` 메시지 payload 확인
3. 같은 payload를 다시 발행하거나 동일 주문 요청을 같은 `X-Order-Key`로 재시도

확인 SQL:

```sql
select order_id, count(*)
from delivery_service.p_delivery
group by order_id
having count(*) > 1;
```

문제 추적:

- 같은 `order_id` 배송 중복 생성: idempotency 처리 확인
- 성공 이벤트 중복 발행: downstream order 상태 변경 중복 가능성 확인
- Redis 이벤트 중복 발행: AI/Slack 중복 알림 가능성 확인

## 시나리오 4. delivery-service 중지 후 Kafka lag 확인

목적:

```text
delivery-service가 중지된 동안 delivery.create 메시지가 쌓이고, 복구 후 처리되는지 확인
```

장애 유도:

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml stop delivery-service"
```

부하 요청:

```text
POST /api/v1/orders
Body: db/seed/orders/01-success-order.json
```

관찰:

- Kafka UI에서 `delivery.create` consumer lag 증가
- 배송 row 생성 지연
- order 상태 정체 여부

복구:

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml start delivery-service"
```

복구 후 확인:

- `delivery.create` lag 감소
- 배송 row 생성 재개
- `delivery.create.succeed` 또는 `delivery.create.failed` 발행
- DLT 발생 여부

## 시나리오 5. AI/Slack Redis Stream 정상 처리

목적:

```text
배송 생성 이후 Redis Stream을 통해 AI/Slack 비동기 처리가 이어지는지 확인
```

진행:

1. 배송 생성 성공 시나리오 실행
2. Redis Stream length 확인
3. ai-service, slack-service 처리 로그 확인
4. pending entry 누적 여부 확인

관찰:

- Redis stream message 생성
- ai-service consume 여부
- slack-service consume 여부
- 처리 실패 시 pending entry 누적 여부

문제 추적:

- Redis 메시지 없음: delivery-service 이벤트 발행 확인
- AI 처리 없음: ai-service consumer group 확인
- Slack 처리 없음: slack-service consumer group 확인
- pending 누적: consumer 장애 또는 ack 누락 확인

## 시나리오 6. Redis 중지 후 장애 전파 확인

목적:

```text
Redis 장애가 배송 생성 본 흐름까지 영향을 주는지, 또는 알림 흐름만 실패하는지 확인
```

장애 유도:

```bash
gcloud compute ssh hublink-data-monitor-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.data-monitor.yml stop redis"
```

관찰:

- delivery-service 로그
- ai-service 로그
- slack-service 로그
- 배송 생성 성공 여부
- Redis 복구 후 pending message 처리 여부

복구:

```bash
gcloud compute ssh hublink-data-monitor-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.data-monitor.yml start redis"
```

문제 추적:

- 배송 생성까지 실패: Redis 연계가 핵심 트랜잭션에 강하게 결합된 상태
- 배송은 성공하고 알림만 실패: 비동기 분리 정상
- 복구 후 메시지 누락: Redis Stream 발행/재시도 정책 확인
- 중복 알림: consumer 재처리와 ack 처리 확인

## 결과 기록 양식

| 항목 | 값 |
| --- | --- |
| 테스트 일시 |  |
| 배포 commit |  |
| 시나리오 |  |
| 요청 body |  |
| 기대 결과 |  |
| 실제 결과 |  |
| Kafka topic 확인 |  |
| delivery DB 확인 |  |
| Redis 확인 |  |
| delivery-service 로그 |  |
| ai/slack 로그 |  |
| 원인 추정 |  |
| 조치 |  |
| 재테스트 결과 |  |
