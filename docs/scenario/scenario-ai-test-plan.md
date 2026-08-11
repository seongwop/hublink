# AI 시나리오 테스트 계획

이 문서는 HubLink 배포 후 AI 도메인 중심으로 Redis Stream 이벤트 수신, AI 시한 생성, 결과 이벤트 발행이 정상적으로 동작하는지 검증하기 위한 시나리오를 정리한다.

주문, 재고, 배송 생성, Slack 알림 전송은 별도 테스트 범위로 분리한다.

AI 서비스는 생성 API가 아니라 Redis Stream 이벤트를 consume해서 동작한다.

따라서 AI 시한 생성 요청 이벤트는 `delivery-service`의 테스트 전용 API를 통해 `deadline:requested:stream`에 주입하고, ai-service가 해당 이벤트를 consume해서 AI 시한 생성과 결과 이벤트 발행을 수행하는지 검증한다.

또한 AI 서비스는 별도 멱등성키 기반 중복 방지 정책을 적용하지 않으므로, 동일 이벤트 중복 처리 방지 테스트는 본 시나리오에서 제외한다.

## 테스트 목표

* delivery-service 테스트 전용 API로 `DeadlineRequestedEvent`를 Redis Stream에 주입할 수 있는지 확인
* `deadline:requested:stream`에 AI 요청 이벤트가 생성되는지 확인
* ai-service가 `deadline:requested:stream` 이벤트를 consume하는지 확인
* AI가 최종 발송 시한 `finalDepartureDeadline`을 생성하는지 확인
* AI 처리 결과가 DB에 저장되는지 확인
* AI 처리 결과 이벤트인 `DeadlineGeneratedEvent`가 `deadline:generated:stream`에 발행되는지 확인
* validation 실패 이벤트가 DB에 저장되지 않고 로그 기록 후 ACK 처리되는지 확인
* AI 처리 중 오류가 발생했을 때 실패 상태 또는 에러 로그를 확인할 수 있는지 검증
* Redis, DB, 로그를 통해 AI 처리 흐름을 추적할 수 있는지 확인
* ai-service 중지 후에도 delivery-service를 통해 이벤트를 주입할 수 있고, 복구 후 Redis Stream에 쌓인 이벤트가 처리되는지 확인
* 필요 시 `DeadlineGeneratedEvent`를 직접 발행하여 AI 이후 Slack/Delivery 후속 흐름을 별도로 확인

## 테스트 대상 흐름

AI 시한 생성 요청 흐름:

```text
delivery-service 테스트 전용 API
-> deadline:requested:stream
-> ai-service consume
-> validation
-> mock AI 또는 Gemini API 처리
-> AI 결과 DB 저장
-> deadline:generated:stream
```

AI 시한 생성 완료 후속 흐름:

```text
ai-service
-> deadline:generated:stream
-> slack-service consume
-> delivery-service consume
```

## 이벤트 책임 구조

| 이벤트                      | 발행 주체            | 소비 주체                           | 테스트 API 위치       |
| ------------------------ | ---------------- | ------------------------------- | ---------------- |
| `DeadlineRequestedEvent` | delivery-service | ai-service                      | delivery-service |
| `DeadlineGeneratedEvent` | ai-service       | slack-service, delivery-service | ai-service       |

`DeadlineRequestedEvent`는 배송 생성 이후 AI 시한 생성을 요청하는 이벤트이므로 delivery-service에서 발행한다.

`DeadlineGeneratedEvent`는 AI가 시한을 생성한 뒤 발행하는 결과 이벤트이므로 ai-service에서 발행한다.

## 이벤트 구조

### DeadlineRequestedEvent

AI 서비스가 소비하는 요청 이벤트다.

```json
{
  "eventId": "이벤트-uuid",
  "deliveryId": "배송-uuid",
  "orderId": "주문-uuid",

  "ordererName": "김말숙",
  "ordererEmail": "msk@seafood.world",
  "orderedAt": "2025-12-08T10:00:00",
  "requestMessage": "12월 12일 3시까지는 보내주세요!",

  "receiverUserId": "수신자-사용자-uuid",
  "receiverSlackId": "U1234567890",

  "products": [
    {
      "productName": "마른 오징어",
      "quantity": 50
    }
  ],

  "requestedArrivalAt": "2025-12-12T15:00:00",

  "departureHubName": "경기 북부 센터",
  "destinationAddress": "부산시 사하구 낙동대로 1번길 1 해산물월드",

  "deliveryManagerName": "고길동",
  "deliveryManagerEmail": "kdk@sparta.world",

  "routeInfo": [
    {
      "departureHubName": "경기 북부 센터",
      "arrivalHubName": "대전광역시 센터",
      "arrivalCompanyName": "OO업체",
      "estimatedDistanceKm": 150.5,
      "estimatedDurationMin": 120,
      "routeType": "HUB_TO_HUB"
    }
  ],

  "workStartTime": "09:00",
  "workEndTime": "18:00"
}
```

### DeadlineGeneratedEvent

AI 서비스가 생성 후 발행하는 결과 이벤트다.

```json
{
  "eventId": "이벤트-uuid",
  "deliveryId": "배송-uuid",
  "aiMessageId": "ai-메시지-uuid",
  "receiverUserId": "수신자-사용자-uuid",
  "receiverSlackId": "U0123456789",
  "finalDepartureDeadline": "2026-05-20T15:00:00",
  "messageType": "DELIVERY_DEADLINE",
  "message": "..."
}
```

## 관찰 도구

| 도구           | URL 또는 위치                                        | 확인 항목                                     |
| ------------ | ------------------------------------------------ | ----------------------------------------- |
| Swagger      | `http://<PLATFORM_EXTERNAL_IP>:19091/swagger-ui/index.html` | 테스트 전용 API 호출                          |
| Eureka       | IAP 터널의 `http://localhost:19090`                      | ai-service, delivery-service 등록 여부        |
| Grafana      | platform VM IAP 터널의 `http://localhost:3000`    | ai-service CPU, heap, HTTP, JVM 지표        |
| Zipkin       | platform VM IAP 터널의 `http://localhost:9411`    | AI 처리 trace                               |
| PostgreSQL   | `hublink` database                               | AI 메시지 저장 결과                              |
| Redis        | Redis Stream                                     | requested/generated stream, pending entry |
| service logs | `domain-b-vm`                                    | ai-service consume, 처리 성공/실패 로그           |
| service logs | delivery-service 실행 VM                           | delivery-service 이벤트 발행 로그                |

## 사전 준비

AI 시나리오 테스트는 배송 생성 이후 발행되는 `DeadlineRequestedEvent`를 delivery-service 테스트 전용 API로 직접 주입하여 진행한다.

따라서 배송 시나리오처럼 주문 요청 body나 seed SQL을 필수로 사용하지 않는다.

AI 단독 테스트에서는 테스트 요청 body JSON만 준비하면 된다.

AI 요청 이벤트를 만들기 위한 테스트 요청 body:

```text
db/seed/ai/events/01-ai-success-event.json
db/seed/ai/events/02-ai-validation-fail-event.json
```

AI 이후 후속 흐름 테스트가 필요한 경우 추가 요청 body:

```text
db/seed/ai/events/03-ai-generated-event.json
```

각 파일의 역할:

| 파일                                 | 용도                | 확인 포인트                                 |
| ---------------------------------- | ----------------- | -------------------------------------- |
| `01-ai-success-event.json`         | 정상 성공 이벤트         | AI 소비, 메시지 저장, 결과 스트림 발행 정상 확인         |
| `02-ai-validation-fail-event.json` | validation 실패 이벤트 | 필수값 누락 시 DB 미저장, 결과 이벤트 미발행, ACK 처리 확인 |
| `03-ai-generated-event.json`       | AI 결과 이벤트 직접 발행   | AI 이후 Slack/Delivery 후속 consume 확인     |

테스트 전용 API:

```http
POST /api/v1/deliveries/test/deadline-requested
```

이 API는 delivery-service 배포 환경에서 `DELIVERY_TEST_API_ENABLED=true`일 때만 활성화된다.

AI 이후 후속 흐름 테스트용 API:

```http
POST /api/v1/ai/test/deadline-generated
```

진행 순서:

1. Redis Stream 상태 확인
2. 필요한 경우 테스트 전 requested/generated stream 초기화
3. Swagger 또는 curl로 delivery-service 테스트 전용 API 호출
4. 요청 body는 시나리오에 맞는 JSON 파일 사용
5. `deadline:requested:stream`에 이벤트 생성 여부 확인
6. ai-service 처리 로그 확인
7. 정상 케이스에서는 DB 저장 여부 확인
8. 정상 케이스에서는 `deadline:generated:stream` 결과 이벤트 발행 여부 확인
9. 실패 케이스에서는 validation 실패 로그, DB 미저장, generated stream 미발행, pending entry 누적 여부 확인
10. 필요 시 `DeadlineGeneratedEvent`를 직접 발행해 Slack/Delivery 후속 흐름을 확인

Redis Stream 초기화가 필요한 경우:

```bash
redis-cli -h 10.10.0.40 -p 6379 DEL deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 DEL deadline:generated:stream
```

정상 요청:

```http
POST /api/v1/deliveries/test/deadline-requested
Body: db/seed/ai/events/01-ai-success-event.json
```

실패 요청:

```http
POST /api/v1/deliveries/test/deadline-requested
Body: db/seed/ai/events/02-ai-validation-fail-event.json
```

AI 결과 이벤트 직접 발행 요청:

```http
POST /api/v1/ai/test/deadline-generated
Body: db/seed/ai/events/03-ai-generated-event.json
```

## 실패 처리 정책

AI 서비스의 실패 처리는 크게 두 가지로 나눈다.

### 1. Validation 실패

이벤트 필수값이 누락된 경우다.

예시:

* `deliveryId`가 null인 경우
* `orderId`가 null인 경우
* `requestedArrivalAt`이 null인 경우
* `products`가 비어 있는 경우
* `routeInfo`가 비어 있는 경우

이 경우는 AI 메시지 생성 대상이 아니므로 DB에 저장하지 않는다.

처리 정책:

```text
validation 실패
-> AI 메시지 생성 대상 아님
-> p_ai_message 저장 안 함
-> deadline:generated:stream 발행 안 함
-> validation 실패 로그 기록
-> ACK 처리
-> pending에 남기지 않음
```

### 2. AI 처리 중 실패

이벤트 필수값은 정상이나 Gemini API 호출, 응답 파싱, 메시지 생성 로직 등에서 오류가 발생한 경우다.

이 경우는 AI 요청 자체는 유효하므로, 구현 정책에 따라 `FAILED` 상태 저장 또는 에러 로그 기록으로 추적한다.

처리 정책:

```text
validation 통과
-> AI 처리 중 오류 발생
-> FAILED 저장 또는 에러 로그 기록
-> deadline:generated:stream 발행 안 함
-> ACK 처리 또는 pending 유지 정책 확인
```

현재 시나리오 테스트에서는 validation 실패 케이스를 우선 검증한다.

## 시나리오 1. AI 요청 이벤트 생성

### 목적

delivery-service 테스트 전용 API를 호출했을 때 `deadline:requested:stream`에 `DeadlineRequestedEvent`가 정상 생성되는지 확인한다.

### 요청

```http
POST /api/v1/deliveries/test/deadline-requested
```

### Body

```json
{
  "eventId": "90000000-0000-0000-0000-000000000001",
  "deliveryId": "60000000-0000-0000-0000-000000000001",
  "orderId": "70000000-0000-0000-0000-000000000001",

  "ordererName": "김말숙",
  "ordererEmail": "msk@seafood.world",
  "orderedAt": "2025-12-08T10:00:00",
  "requestMessage": "12월 12일 3시까지는 보내주세요!",

  "receiverUserId": "50000000-0000-0000-0000-000000000004",
  "receiverSlackId": "U1234567890",

  "products": [
    {
      "productName": "마른 오징어",
      "quantity": 50
    }
  ],

  "requestedArrivalAt": "2025-12-12T15:00:00",

  "departureHubName": "경기 북부 센터",
  "destinationAddress": "부산시 사하구 낙동대로 1번길 1 해산물월드",

  "deliveryManagerName": "고길동",
  "deliveryManagerEmail": "kdk@sparta.world",

  "routeInfo": [
    {
      "departureHubName": "경기 북부 센터",
      "arrivalHubName": "대전광역시 센터",
      "arrivalCompanyName": "OO업체",
      "estimatedDistanceKm": 150.5,
      "estimatedDurationMin": 120,
      "routeType": "HUB_TO_HUB"
    }
  ],

  "workStartTime": "09:00",
  "workEndTime": "18:00"
}
```

### 기대 흐름

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream XADD
```

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XRANGE deadline:requested:stream - + COUNT 10
```

### 로그 확인

```text
event=DELIVERY_TEST_DEADLINE_REQUESTED_EVENT_PUBLISHED
```

### 기대 결과

```text
deadline:requested:stream에 요청 이벤트가 생성된다.
payload에 DeadlineRequestedEvent 내용이 포함된다.
delivery-service 테스트 전용 API는 정상 응답을 반환한다.
```

### 문제 추적

* API 응답 실패: DeliveryTestController, DeliveryTestService 확인
* Redis 메시지 없음: Redis 연결 설정, stream key, XADD 로직 확인
* payload 누락: payload 필드명 및 직렬화 로직 확인
* 5xx 발생: Redis 연결 장애 또는 직렬화 오류 확인
* ai-service에서 역직렬화 실패: delivery-service의 DeadlineRequestedEvent JSON 구조와 ai-service의 소비 이벤트 구조 확인

## 시나리오 2. AI 요청 이벤트 정상 처리

### 목적

`deadline:requested:stream`에 들어온 `DeadlineRequestedEvent`를 ai-service가 consume하고, AI 결과를 생성하는지 확인한다.

### 진행

1. 시나리오 1 실행
2. ai-service 로그 확인
3. AI 메시지 DB 저장 여부 확인
4. `deadline:generated:stream` 발행 여부 확인
5. pending entry 누적 여부 확인

### 기대 흐름

```text
deadline:requested:stream
-> ai-service consume
-> validation 통과
-> mock AI 또는 Gemini API 처리
-> AI 결과 DB 저장
-> DeadlineGeneratedEvent 생성
-> deadline:generated:stream 발행
-> ACK 처리
```

### 발행 기대 이벤트

```json
{
  "eventId": "생성된-이벤트-uuid",
  "deliveryId": "60000000-0000-0000-0000-000000000001",
  "aiMessageId": "생성된-ai-message-id",
  "receiverUserId": "50000000-0000-0000-0000-000000000004",
  "receiverSlackId": "U1234567890",
  "finalDepartureDeadline": "AI가 계산한 최종 발송 시한",
  "messageType": "DELIVERY_DEADLINE",
  "message": "AI가 생성한 배송 시한 안내 메시지"
}
```

### 로그 확인

```text
event=AI_STREAM_BATCH_RECEIVED
event=AI_STREAM_EVENT_RECEIVED
event=AI_DEADLINE_REQUEST_PROCESSED
event=AI_DEADLINE_GENERATED_ENQUEUED
event=AI_STREAM_ACK_COMPLETED
event=AI_STREAM_ACKED
```

### 확인 SQL

```sql
select event_id, delivery_id, status, final_departure_deadline, created_at
from ai_service.p_ai_message
order by created_at desc;
```

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XRANGE deadline:generated:stream - + COUNT 10
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
```

### 기대 결과

```text
ai-service가 requested stream 이벤트를 consume한다.
AI 메시지가 p_ai_message에 저장된다.
final_departure_deadline 값이 생성된다.
deadline:generated:stream에 DeadlineGeneratedEvent가 발행된다.
처리 완료된 requested stream 메시지는 ACK 처리된다.
pending entry가 누적되지 않는다.
```

### 문제 추적

* ai-service consume 없음: consumer group, stream key, Redis connection 확인
* DB 저장 없음: AI 처리 로직, transaction, repository 확인
* `finalDepartureDeadline` 없음: AI 응답 parsing 또는 mock 응답 생성 로직 확인
* generated stream 없음: `DeadlineGeneratedEvent` 발행 로직 확인
* pending 누적: consume 후 예외 발생 또는 ACK 누락 확인
* 상태가 FAILED: Gemini API, mock 처리 로직, 응답 parsing 확인
* 역직렬화 실패: delivery-service의 이벤트 필드와 ai-service의 consume DTO 필드 타입 확인

## 시나리오 3. AI validation 실패 처리

### 목적

필수값이 누락된 `DeadlineRequestedEvent`가 들어왔을 때 ai-service가 validation 실패로 판단하고, DB 저장 및 결과 이벤트 발행 없이 ACK 처리하는지 확인한다.

### 진행

1. 실패를 유도할 수 있는 body로 delivery-service 테스트 전용 API 호출
2. ai-service 로그 확인
3. DB에 저장되지 않았는지 확인
4. `deadline:generated:stream`에 결과 이벤트가 발행되지 않았는지 확인
5. pending entry가 남지 않는지 확인

### 실패 유도 예시

```json
{
  "eventId": "90000000-0000-0000-0000-000000000002",
  "deliveryId": null,
  "orderId": "70000000-0000-0000-0000-000000000002",

  "ordererName": "",
  "ordererEmail": "",
  "orderedAt": "2025-12-08T10:00:00",
  "requestMessage": "",

  "receiverUserId": "50000000-0000-0000-0000-000000000004",
  "receiverSlackId": "U1234567890",

  "products": [],

  "requestedArrivalAt": null,

  "departureHubName": "",
  "destinationAddress": "",

  "deliveryManagerName": "",
  "deliveryManagerEmail": "",

  "routeInfo": [],

  "workStartTime": "09:00",
  "workEndTime": "18:00"
}
```

### 기대 흐름

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream XADD
-> ai-service consume
-> validation 실패
-> validation 실패 로그 기록
-> p_ai_message 저장 안 함
-> deadline:generated:stream 발행 안 함
-> ACK 처리
```

### 로그 확인

```text
event=AI_STREAM_BATCH_RECEIVED
event=AI_STREAM_VALIDATION_FAILED
```

### 확인 SQL

```sql
select event_id, delivery_id, status, final_departure_deadline, created_at
from ai_service.p_ai_message
where event_id = '90000000-0000-0000-0000-000000000002';
```

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XRANGE deadline:requested:stream - + COUNT 10
redis-cli -h 10.10.0.40 -p 6379 XRANGE deadline:generated:stream - + COUNT 10
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
```

### 기대 결과

```text
delivery-service 테스트 전용 API는 Redis Stream에 잘못된 이벤트를 주입한다.
ai-service는 이벤트를 consume한다.
Bean Validation에서 필수값 누락을 감지한다.
p_ai_message 테이블에는 저장되지 않는다.
deadline:generated:stream에는 결과 이벤트가 발행되지 않는다.
잘못된 이벤트는 ACK 처리되어 pending에 남지 않는다.
```

### 판정 기준

```text
validation 실패 로그가 출력된다.
DB 저장 결과가 없다.
generated stream 발행 결과가 없다.
pending entry가 누적되지 않는다.

위 조건을 만족하면 PASS로 판단한다.
```

### 문제 추적

* validation 실패 로그 없음: Event DTO validation annotation, Validator 주입, consumer validation 로직 확인
* DB에 저장됨: validation 이후 처리 흐름 진입 여부 확인
* generated stream 발행됨: validation 실패 후 continue 처리 여부 확인
* pending 계속 증가: validation 실패 후 ACK 누락 여부 확인
* API에서 400 발생: 테스트 컨트롤러에 @Valid가 붙어 있는지 확인

## 시나리오 4. DeadlineGeneratedEvent 발행 확인

### 목적

AI 요청 이벤트 정상 처리 후 ai-service가 `DeadlineGeneratedEvent`를 `deadline:generated:stream`에 발행하는지 확인한다.

이 시나리오는 `DeadlineGeneratedEvent`를 직접 주입하는 테스트가 아니라, AI 처리 결과로 generated stream에 이벤트가 생성되는지 확인하는 테스트다.

### 진행

1. 정상 요청 body로 delivery-service 테스트 전용 API 호출
2. ai-service가 requested stream 이벤트를 consume하는지 확인
3. AI 메시지가 DB에 저장되는지 확인
4. `deadline:generated:stream`에 `DeadlineGeneratedEvent`가 발행되는지 확인
5. generated 이벤트의 `deliveryId`, `aiMessageId`, `receiverUserId`, `receiverSlackId`, `finalDepartureDeadline` 값을 확인

### 요청

```http
POST /api/v1/deliveries/test/deadline-requested
Body: db/seed/ai/events/01-ai-success-event.json
```

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XRANGE deadline:generated:stream - + COUNT 10
```

### 확인 SQL

```sql
select event_id, delivery_id, status, final_departure_deadline, created_at
from ai_service.p_ai_message
where delivery_id = '60000000-0000-0000-0000-000000000001'
order by created_at desc;
```

### 기대 결과

```text
AI 메시지가 DB에 저장된다.
deadline:generated:stream에 DeadlineGeneratedEvent가 발행된다.
generated 이벤트의 deliveryId가 AI 요청 이벤트의 deliveryId와 일치한다.
generated 이벤트의 aiMessageId가 DB에 저장된 AI 메시지 ID와 연결된다.
generated 이벤트에 finalDepartureDeadline과 message가 포함된다.
```

### 문제 추적

* generated stream 없음: DeadlineGeneratedEventPublisher 확인
* aiMessageId 없음: aiService.generateDeadline 결과 확인
* deliveryId 불일치: 이벤트 매핑 로직 확인
* finalDepartureDeadline 없음: AI 응답 parsing 또는 mock 응답 생성 로직 확인
* generated 이벤트는 발행됐지만 후속 서비스 미처리: Slack/Delivery consumer group 확인

## 결과 기록 양식

| 항목                  | 값           |
| ------------------- | ----------- |
| 테스트 일시              |             |
| 배포 commit           |             |
| 시나리오                |             |
| 요청 body             |             |
| 기대 결과               |             |
| 실제 결과               |             |
| requested stream 확인 |             |
| generated stream 확인 |             |
| AI DB 확인            |             |
| ai-service 로그       |             |
| delivery-service 로그 |             |
| slack-service 로그    |             |
| pending entry       |             |
| 판정                  | PASS / FAIL |
| 원인 추정               |             |
| 조치                  |             |
| 재테스트 결과             |             |

## 시나리오별 판정 요약

| 시나리오                                    | PASS 기준                                                   |
| --------------------------------------- | --------------------------------------------------------- |
| AI 요청 이벤트 생성                            | requested stream에 이벤트가 생성됨                                |
| AI 요청 이벤트 정상 처리                         | DB 저장, generated stream 발행, ACK 처리 완료                     |
| AI validation 실패 처리                     | validation 실패 로그, DB 미저장, generated stream 미발행, ACK 처리 완료 |
| DeadlineGeneratedEvent 발행 확인            | generated stream 발행, DB의 aiMessageId와 이벤트 매칭              |
| DeadlineGeneratedEvent 직접 발행 후 후속 흐름 확인 | slack-service, delivery-service consume 확인                |
| ai-service 중지 후 복구 확인                   | 복구 후 이벤트 처리, DB 저장, generated stream 발행, pending 미누적      |
