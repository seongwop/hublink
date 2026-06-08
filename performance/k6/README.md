# HubLink k6 부하 테스트

이 디렉터리는 배송 도메인 개선 이력 도출을 위한 k6 스크립트를 관리한다.

부하는 GCP `hublink-load-test-vm`에서 발생시킨다.

```text
load-test-vm: 10.10.0.50
api-gateway: http://10.10.0.10:19091
delivery-service: http://10.10.0.30:19099
```

## 준비

```bash
cd /opt/hublink/performance/k6
cp .env.example .env.k6
chmod +x run-k6.sh
```

`.env.k6`에는 seed 데이터의 UUID를 넣는다.

```text
USER_ID
SUPPLIER_COMPANY_ID
RECEIVER_COMPANY_ID
PRODUCT_ID
```

여러 seed를 순환시키려면 쉼표로 구분한다.

```text
SUPPLIER_COMPANY_IDS=id1,id2
RECEIVER_COMPANY_IDS=id1,id2
PRODUCT_IDS=id1,id2
PRODUCT_NAMES=name1,name2
```

## 실행 순서

### 1. 배송 생성 Kafka 유입량

```bash
./run-k6.sh delivery-create-kafka-load.js
```

`POST /api/v1/orders`로 주문을 만들고, 이후 주문/재고 흐름에서 `delivery.create` Kafka 이벤트가 생성되도록 부하를 넣는다. 기본 k6 Docker 이미지는 Kafka producer를 직접 포함하지 않으므로 HTTP 주문 API를 배송 이벤트 유입점으로 사용한다.

확인 지표:

```text
order-service 요청 TPS
delivery.create consumer lag 최대값
lag 회복 시간
Delivery Create TPS
delivery.create.succeed / failed 발행량
delivery-service CPU/heap
DB active/pending connection
```

### 2. 배송 생성 DB/락 병목

```bash
DELIVERY_BASE_URL=http://10.10.0.30:19099 ./run-k6.sh delivery-create-logic-load.js
```

`POST /internal/deliveries`를 직접 호출해 같은 목적지 허브 기준 배송 생성 요청을 몰아 DB connection 대기와 Redisson lock 경합을 확인한다. Gateway route에 `/internal/deliveries`가 없으므로 delivery-service 직접 주소를 사용한다.

확인 지표:

```text
Hikari active/pending connection
DB wait event
Redisson lock wait / timeout
delivery-service HTTP p95/p99
delivery row 생성량
route history row 생성량
Feign hub/user span duration
```

락 경합을 강하게 만들려면 같은 `RECEIVER_COMPANY_ID`를 사용한다. 정상 처리량 baseline을 보려면 `RECEIVER_COMPANY_IDS`에 여러 회사를 넣어 경합을 분산한다.

### 3. Delivery Outbox 발행 병목

```bash
DELIVERY_BASE_URL=http://10.10.0.30:19099 ./run-k6.sh delivery-outbox-publish-load.js
```

배송 생성 성공 후 `p_delivery_outboxes`에 쌓이는 `delivery.create.succeed` 이벤트와 outbox worker의 Kafka 발행 속도를 확인한다. 테스트 전후로 outbox backlog, Kafka 발행 지연, publish 실패율을 비교한다.

확인 지표:

```text
p_delivery_outboxes PENDING / FAILED 수
delivery.create.succeed 발행 TPS
outbox backlog 회복 시간
Kafka send 실패율
delivery.kafka.outbox.fixed-delay-ms 변경 효과
Kafka producer batch / linger 변경 효과
```

### 4. Redis Stream AI 마감 생성

```bash
./run-k6.sh delivery-ai-deadline-stream-load.js
```

delivery-service 테스트 API를 호출해 `deadline:requested:stream`에 AI 마감 생성 요청 이벤트를 주입한다. 기존 `ai-deadline-request-load.js`는 협업자 작성 파일이므로 메인 테스트에서는 새 스크립트를 사용한다.

확인 지표:

```text
deadline:requested:stream lag 최대값
AI consume TPS
deadline:generated:stream 증가량
pending entry 최대값
backlog 회복 시간
ai-service CPU/heap
Redis memory
DB active/pending connection
```

## 부록. Gateway 공통 부하

```bash
./run-k6.sh gateway-appendix-load.js
```

배송 도메인 개선 실험 후 공통 진입 구간이 병목인지 확인하는 보조 테스트로 실행한다.

```bash
PATHS=/actuator/health,/api/v1/orders ./run-k6.sh gateway-appendix-load.js
```

## Redis 확인 명령

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XINFO CONSUMERS deadline:requested:stream ai-service-group
```

## 결과 해석

k6의 `http_req_duration`은 HTTP 요청 처리 시간이다. Kafka와 Redis Stream은 비동기 처리이므로 k6 결과만으로 성공을 판단하지 말고 Kafka UI, Redis, DB, Grafana 지표를 함께 기록한다.

```text
TPS = http_reqs / test_duration_seconds
Delivery Create TPS = 생성된 delivery row 수 / test_duration_seconds
Outbox Publish TPS = PUBLISHED outbox row 수 / test_duration_seconds
Redis Stream TPS = 처리 완료 stream message 수 / test_duration_seconds
```
