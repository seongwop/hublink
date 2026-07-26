# HubLink k6 부하 테스트

이 디렉터리는 배송 도메인 개선 이력 도출을 위한 k6 스크립트를 관리한다.

부하는 GCP `hublink-load-test-vm`에서 발생시킨다.

`load-test-vm`에는 GitHub Actions 동기화로 `performance/k6`와 `db/seed`가 함께 반영된다.

```text
load-test-vm: 10.10.0.50
api-gateway: http://10.10.0.10:19091
delivery-service: http://10.10.0.70:19099
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

## 부하 옵션 예시

`STAGES`는 `.env.k6`에 넣거나 실행 시 환경변수로 넘긴다.

```bash
# baseline
STAGES='[{"duration":"1m","target":5},{"duration":"3m","target":5},{"duration":"1m","target":0}]' \
SLEEP_SECONDS=1 \
./run-k6.sh delivery-create-kafka-load.js

# load
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
SLEEP_SECONDS=1 \
./run-k6.sh delivery-create-kafka-load.js

# stress
STAGES='[{"duration":"2m","target":50},{"duration":"5m","target":50},{"duration":"3m","target":0}]' \
SLEEP_SECONDS=1 \
./run-k6.sh delivery-create-kafka-load.js
```

## 테스트 전후 DB 자동 초기화

배송 부하테스트를 같은 DB 상태에서 반복하려면 reset SQL을 실행한다.

기본 reset SQL:

```text
db/seed/10-reset-delivery-loadtest.sql
```

기본 자동 복원 SQL:

```text
db/seed/11-reset-delivery-loadtest-baseline.sql
```

`10-reset-delivery-loadtest.sql`은 delivery 런타임 테이블만 비운다.
`11-reset-delivery-loadtest-baseline.sql`은 런타임 테이블 초기화 후 배송 로직 부하테스트에 필요한 허브, 경로, 업체, 허브 매니저, 배송 담당자 풀까지 다시 채운다.

`run-k6.sh`는 종료 후에는 기본으로 baseline 복원 SQL을 실행하고, 필요하면 실행 전에도 SQL 파일을 추가로 실행할 수 있다.
load-test-vm 기준 기본 reset DB 접속값은 `10.10.0.40:5432`, `user`, `0000`, `hublink` 이다.

```bash
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
./run-k6.sh delivery-create-logic-load.js
```

종료 후 reset을 끄고 싶으면 빈 값으로 덮어쓴다.

```bash
POST_TEST_SQL_FILE='' ./run-k6.sh delivery-create-logic-load.js
```

이전 run이 비정상 종료됐거나 baseline 복원이 실패해 시작 시점 정합성이 불안하면 그때만 `PRE_TEST_SQL_FILE`을 명시한다.

```bash
PRE_TEST_SQL_FILE=db/seed/11-reset-delivery-loadtest-baseline.sql \
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
./run-k6.sh delivery-create-logic-load.js
```

정상 종료뿐 아니라 `Ctrl+C`로 중단한 경우에도 종료 시 reset을 시도한다.
로컬 Docker 환경에서는 `hublink-postgres` 컨테이너에 직접 `psql`을 실행하고, load-test-vm 에서는 `postgres:16` 클라이언트 컨테이너로 data-vm PostgreSQL에 직접 접속한다.
공용 테스트 환경에서는 reset 대상 SQL과 접속 대상을 명확히 확인한 뒤 사용한다.

Gateway 포함 테스트에서 429가 발생하면 배송 병목이 아니라 Gateway rate limit에 먼저 걸린 것으로 기록한다. 배송 Kafka와 DB/락 테스트는 `DELIVERY_BASE_URL`로 delivery-service를 직접 호출해 Gateway rate limit을 분리한다.

## backlog 확인 기준

Kafka backlog는 Kafka UI 또는 consumer group 명령으로 확인한다.

```bash
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group delivery-service \
  --describe
```

`delivery.create`의 `LAG`가 부하 중 증가하고 부하 종료 후 천천히 줄면 consumer backlog로 본다. 회복 시간은 부하 종료 시점부터 lag가 0 또는 기준값으로 돌아올 때까지의 시간이다.

Outbox backlog는 DB에서 확인한다.

```sql
select status, count(*)
from delivery_service.p_delivery_outboxes
group by status;
```

`PENDING`이 부하 종료 후에도 남아 있으면 outbox backlog로 본다.

## Kafka 초기화 주의

Kafka UI는 보통 topic 메시지를 직접 비우는 기능보다 topic 삭제 또는 consumer offset reset 기능을 제공한다. 가장 안전한 방법은 테스트마다 새 consumer group을 쓰거나, 테스트 전후 lag 기준을 명확히 기록하는 것이다.

정말 topic을 비워야 하면 테스트 환경에서만 topic 삭제 후 재생성한다.

```bash
docker exec -it kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --delete \
  --topic delivery.create

docker exec -it kafka kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --create \
  --topic delivery.create \
  --partitions 3 \
  --replication-factor 1
```

## 실행 순서

### 1. 배송 생성 Kafka 유입량

```bash
./run-k6.sh delivery-create-kafka-load.js
```

`POST /api/v1/deliveries/test/delivery-create`로 `delivery.create` Kafka 이벤트를 직접 주입한다. Gateway, order-service, stock-service를 우회하므로 배송 Kafka consumer lag와 Delivery Create TPS를 분리해서 확인할 수 있다.

확인 지표:

```text
delivery.create 이벤트 주입 TPS
delivery.create consumer lag 최대값
lag 회복 시간
Delivery Create TPS
delivery.create.succeed / failed 발행량
delivery-service CPU/heap
DB active/pending connection
```

### 2. 배송 생성 DB/락 병목

```bash
DELIVERY_BASE_URL=http://10.10.0.70:19099 ./run-k6.sh delivery-create-logic-load.js
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
DELIVERY_BASE_URL=http://10.10.0.70:19099 ./run-k6.sh delivery-outbox-publish-load.js
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

## Delivery Query Baseline

��뷮 ���� ����� `EXPLAIN ANALYZE` �뵵������ �Ʒ� SQL�� ����Ѵ�.

```text
db/seed/12-reset-delivery-query-baseline.sql
db/seed/13-explain-delivery-query-baseline.sql
```

`12-reset-delivery-query-baseline.sql`�� delivery, route history, outbox �뷮 �����͸� �ٽ� ä��� baseline SQL�̴�.
`13-explain-delivery-query-baseline.sql`�� active assignment ����, outbox polling, manager�� delivery ��ȸ ������ �ٷ� ������ �� �ִ� explain �����̴�.

## Reset Policy

�⺻ ������ `pre reset`�� �����Ѵ�.
�׽�Ʈ ���� ������ baseline SQL�� �����ϰ�, �׽�Ʈ ���� �Ŀ��� �ڵ� reset�� ���� �ʴ´�.

```text
default PRE_TEST_SQL_FILE = db/seed/11-reset-delivery-loadtest-baseline.sql
default POST_TEST_SQL_FILE = empty
```

�׽�Ʈ �� DB �񱳰� �ʿ��ϸ� �״�� �����ϸ� �ȴ�.

```bash
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
./run-k6.sh delivery-create-logic-load.js
```

�׽�Ʈ �Ŀ��� baseline ������ �ٷ� �ϰ� ���� ���� `POST_TEST_SQL_FILE`�� ����Ѵ�.

```bash
POST_TEST_SQL_FILE=db/seed/11-reset-delivery-loadtest-baseline.sql \
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
./run-k6.sh delivery-create-logic-load.js
```
