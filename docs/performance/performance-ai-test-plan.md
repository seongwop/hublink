# AI 성능 테스트와 트러블슈팅 계획

이 문서는 HubLink GCP VM 환경에서 AI 시한 생성 처리 구간을 중심으로 부하를 만들고, Redis Stream 기반 비동기 처리 구조의 병목을 확인하기 위한 테스트 계획을 정리한다.

주문, 재고, 배송 생성, Slack 알림 전송은 별도 테스트 범위로 분리한다.

이 문서에서는 Redis Stream 기반 AI 처리 흐름의 처리량, 지연, backlog, pending 누적 여부, 복구 속도, 리소스 병목을 중심으로 다룬다.

AI 서비스는 별도 멱등성키 기반 중복 방지 정책을 적용하지 않으므로, 동일 이벤트 중복 저장 또는 중복 발행 방지 테스트는 본 성능 테스트 범위에서 제외한다.

단건 정상 흐름, validation 실패 처리, 실제 Gemini API 소량 안정성은 성능 테스트가 아니라 시나리오 테스트 또는 안정성 테스트에서 별도로 검증한다.

## 테스트 대상 흐름

성능 테스트 대상 흐름은 다음과 같다.

```text
k6 / delivery-service 테스트 전용 API
-> deadline:requested:stream
-> ai-service consumer
-> mock AI 처리
-> p_ai_message 저장
-> deadline:generated:stream
```

실제 운영 흐름에서는 `delivery-service`가 `deadline:requested:stream`에 발송 시한 생성 요청 이벤트를 발행한다.

성능 테스트에서도 이 책임 구조를 유지하기 위해, AI 요청 이벤트 주입 API는 `ai-service`가 아니라 `delivery-service`에 둔다.

즉, k6는 `delivery-service`의 테스트 전용 API를 호출하고, delivery-service는 `DeadlineRequestedEvent`를 `deadline:requested:stream`에 발행한다.

이 구조를 사용하면 `ai-service`를 중단한 상태에서도 delivery-service를 통해 Redis Stream에 이벤트를 계속 주입할 수 있으므로, ai-service 장애 중 backlog 생성과 복구 후 처리 속도를 테스트할 수 있다.

AI 서비스는 생성 API가 아니라 Redis Stream 이벤트를 consume해서 동작한다.

따라서 성능 테스트는 일반적인 API 응답 시간만 보는 방식이 아니라, Redis Stream에 AI 요청 이벤트를 대량 주입하고 ai-service consumer가 이를 얼마나 처리할 수 있는지 확인하는 방식으로 진행한다.

## 테스트 목표

* delivery-service 테스트 전용 API를 통해 AI 요청 이벤트를 대량 주입할 수 있는지 확인
* ai-service 처리량이 이벤트 유입량을 따라가지 못하는 상황 재현
* `deadline:requested:stream` backlog가 쌓이는 조건 확인
* `ai-service-group` pending entry 발생 여부 확인
* AI 처리 결과 이벤트가 `deadline:generated:stream`에 발행되는 속도 확인
* ai-service CPU, heap, GC, DB connection 병목 확인
* consumer read count, batch 크기, concurrency, 인스턴스 증설 효과 비교
* ai-service 장애 후 backlog 처리 속도 측정
* pending entry가 발생하는 경우 ACK 누락 또는 예외 처리 문제 확인
* k6 결과의 TPS, p95, p99, 실패율을 Redis/Grafana/DB 지표와 연결

## 테스트 축

| 축                   | 주요 대상                              | 트러블슈팅 방향                                                      |
| ------------------- | ---------------------------------- | ------------------------------------------------------------- |
| 이벤트 주입 API 처리량      | delivery-service, requested stream | 테스트 API 처리량, Redis XADD 속도, Gateway 병목 확인                     |
| AI Mock 처리량         | ai-service, requested stream       | consumer 수, read count, batch 처리, ACK 위치 개선                   |
| AI 결과 이벤트 발행량       | generated stream                   | 결과 이벤트 발행 속도, Redis producer, DB 저장 병목 확인                     |
| AI backlog 처리       | requested stream backlog           | consumer concurrency, 인스턴스 증설, batch 크기 조정                    |
| ai-service 장애 복구 성능 | requested stream backlog           | 복구 후 backlog 처리 속도, ACK 누락 여부 확인                              |
| JVM/DB 병목           | ai-service, PostgreSQL             | heap, GC pause, Hikari active/pending, insert 성능 확인           |
| Redis Stream 병목     | requested/generated stream         | stream length, lag, pending, Redis memory, command latency 확인 |

## Backlog와 Pending 구분

Redis Stream 테스트에서는 backlog와 pending을 구분해서 봐야 한다.

```text
backlog:
아직 consumer가 읽지 않은 stream 메시지

pending:
consumer가 읽었지만 ACK되지 않은 메시지
```

ai-service가 완전히 중지된 동안 delivery-service 테스트 전용 API로 새로 주입한 메시지는 보통 pending이 아니라 backlog로 쌓인다.

pending은 consumer가 메시지를 읽은 뒤 처리 중 예외가 발생했거나 ACK가 누락되었을 때 증가한다.

따라서 장애 복구 테스트에서는 다음을 분리해서 확인한다.

```text
1. delivery-service 테스트 전용 API로 requested stream에 이벤트가 쌓이는지
2. ai-service 중지 중에는 메시지가 처리되지 않고 backlog로 남는지
3. ai-service 복구 후 backlog가 처리되는지
4. pending entry가 비정상적으로 증가하지 않는지
5. pending이 증가한다면 ACK 위치 또는 예외 처리 로직에 문제가 없는지
```

## Redis Stream 길이 해석 주의

Redis Stream의 `XLEN`은 stream에 남아 있는 전체 entry 수를 의미한다.

ACK가 완료되어도 stream entry가 자동 삭제되는 것은 아니다.

따라서 backlog 판단은 `XLEN`만으로 하지 않고, 다음 값을 함께 확인한다.

```text
XINFO GROUPS의 lag
XPENDING 결과
generated stream 증가량
DB 저장량
ai-service 로그의 ACK 완료 여부
```

즉, `deadline:requested:stream`의 `XLEN`이 크다고 해서 무조건 처리 지연이 발생했다고 판단하면 안 된다.

성능 테스트에서는 `XLEN`, `lag`, `pending`, `generated stream 증가량`, `p_ai_message 저장량`을 함께 보고 처리 상태를 판단한다.

## 관찰 도구

| 도구           | URL 또는 위치                             | 확인 항목                                                     |
| ------------ | ------------------------------------- | --------------------------------------------------------- |
| Grafana      | platform VM IAP 터널의 `http://localhost:3000` | ai-service CPU, memory, JVM, DB, Redis 지표                 |
| Prometheus   | platform VM IAP 터널의 `http://localhost:9090` | raw metric query                                          |
| Redis        | data-vm                               | stream length, lag, pending entry, consumer group, ACK 상태 |
| Zipkin       | platform VM IAP 터널의 `http://localhost:9411` | ai-service 처리 trace                                       |
| PostgreSQL   | hublink database                      | AI 메시지 처리량, 처리 상태, DB connection                          |
| k6           | `hublink-k6-load-test` Cloud Run Job | 이벤트 주입 요청 수, TPS, p95, p99, 실패율                           |
| service logs | domain-b-vm                           | ai-service consume, 처리 지연, ACK, 예외 로그                     |
| service logs | domain-b-vm 또는 delivery-service 실행 VM | delivery-service 테스트 API 호출, requested stream 발행 로그       |

## 테스트 전용 API

성능 테스트에서는 k6로 delivery-service의 테스트 전용 API를 호출해 AI 요청 이벤트를 대량 주입한다.

```http
POST /api/v1/deliveries/test/deadline-requested
```

이 API는 delivery-service 배포 환경에서 `DELIVERY_TEST_API_ENABLED=true`일 때만 활성화된다.

이 API는 AI 처리를 직접 수행하는 API가 아니다.

역할은 `DeadlineRequestedEvent`를 `deadline:requested:stream`에 주입하는 것이다.

따라서 k6의 HTTP 응답 시간은 AI 처리 완료 시간을 의미하지 않는다.

AI 처리 완료 여부는 Redis Stream, DB, ai-service 로그, Grafana 지표를 함께 확인해야 한다.

AI 시한 생성 완료 이벤트를 직접 발행해 AI 이후 흐름을 테스트해야 할 경우에는 ai-service의 테스트 API를 별도로 사용한다.

```http
POST /api/v1/ai/test/deadline-generated
```

단, `deadline-generated` 직접 발행은 AI 처리 성능 테스트가 아니라 AI 이후 Slack/Delivery 후속 흐름 확인용이다.

## 테스트 이벤트 책임 구조

| 이벤트                      | 발행 주체            | 소비 주체                           | 테스트 API 위치       |
| ------------------------ | ---------------- | ------------------------------- | ---------------- |
| `DeadlineRequestedEvent` | delivery-service | ai-service                      | delivery-service |
| `DeadlineGeneratedEvent` | ai-service       | slack-service, delivery-service | ai-service       |

`DeadlineRequestedEvent`는 배송 생성 이후 AI 시한 생성을 요청하는 이벤트이므로 delivery-service에서 발행하는 것이 맞다.

`DeadlineGeneratedEvent`는 AI가 시한을 생성한 뒤 발행하는 결과 이벤트이므로 ai-service에 남긴다.

## k6 핵심 지표

| 지표                 | 의미                                      | 확인 기준            |
| ------------------ | --------------------------------------- | ---------------- |
| http_reqs          | delivery-service 테스트 전용 이벤트 주입 API 요청 수 | 테스트 총량           |
| http_req_duration  | 이벤트 주입 API 응답 시간                        | avg, p95, p99 확인 |
| http_req_failed    | 이벤트 주입 API 실패율                          | 1% 이상이면 원인 확인    |
| iterations         | 시나리오 반복 횟수                              | 실질 이벤트 주입량 계산    |
| iteration_duration | 한 시나리오 수행 시간                            | 복합 흐름 병목 확인      |
| checks             | 검증 성공률                                  | 응답 코드와 body 검증   |

TPS 계산:

```text
TPS = http_reqs / test_duration_seconds
```

AI 이벤트 주입량:

```text
AI Event TPS = deadline:requested:stream에 주입된 메시지 수 / test_duration_seconds
```

AI 소비 처리량:

```text
AI Consume TPS = ai-service 처리 완료 메시지 수 / test_duration_seconds
```

Generated Event 처리량:

```text
Generated Event TPS = deadline:generated:stream에 발행된 메시지 수 / test_duration_seconds
```

Redis Stream 처리량:

```text
Redis Stream TPS = 처리 완료된 stream message 수 / test_duration_seconds
```

AI 처리 지연 시간:

```text
AI 처리 지연 = deadline:generated:stream 발행 시각 - deadline:requested:stream 이벤트 생성 시각
```

단, AI 처리 지연을 정확히 측정하려면 requested 이벤트 payload 또는 stream entry에 생성 시각이 있어야 한다.

별도 timestamp가 없다면 Redis Stream ID의 millisecond timestamp를 참고값으로 사용한다.

## Mock AI 사용 기준

성능 테스트에서는 실제 Gemini API가 아니라 mock AI 응답을 사용한다.

실제 Gemini API를 호출하면 외부 API rate limit, timeout, 네트워크 지연, 응답 지연이 섞여 ai-service consumer 자체 처리량을 정확히 확인하기 어렵다.

따라서 본 성능 테스트 결과는 Gemini API 성능이 아니라 다음 구조의 처리량을 의미한다.

```text
delivery-service 테스트 전용 API
Redis Stream XADD
ai-service consumer
Redis Stream consume
AI 결과 DB 저장
DeadlineGeneratedEvent 발행
ACK 처리
```

실제 Gemini API는 별도의 소량 안정성 테스트에서 확인한다.

## 공통 테스트 단계

| 단계       | VU         | 시간     | 목적                                         |
| -------- | ---------- | ------ | ------------------------------------------ |
| Baseline | 5~10       | 5분     | mock 기준 정상 처리량 수집                          |
| Load     | 30~50      | 10분    | 일반 부하에서 stream lag, pending 변화 확인          |
| Stress   | 80~150     | 10분    | ai-service consumer 병목 발생 지점 확인            |
| Spike    | 순간 증가      | 3~5분   | 갑작스러운 이벤트 증가 후 회복 여부 확인                    |
| Recovery | 장애 유도 후 복구 | 5~10분  | ai-service 중지 중 backlog 생성 및 복구 후 처리 속도 확인 |
| Soak     | 5~10       | 30분 이상 | 장시간 처리 시 heap, Redis memory, pending 누적 확인 |

공통 threshold 예시:

```jsx
export const options = {
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};
```

단, AI 테스트에서 k6의 HTTP 응답 시간은 AI 처리 완료 시간을 의미하지 않는다.

k6는 delivery-service 테스트 전용 API를 통해 Redis Stream에 이벤트를 주입하는 역할이고, 실제 AI 처리 완료 여부는 Redis Stream lag, pending entry, DB 처리 상태, generated stream 증가량, ai-service 로그를 함께 확인해야 한다.

## 테스트 1. AI Mock 처리량 한계

### 대상

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream
-> ai-service consumer
-> mock AI 처리
-> p_ai_message 저장
-> deadline:generated:stream
```

### 목적

* delivery-service 테스트 전용 API를 통해 이벤트를 주입할 수 있는지 확인
* ai-service consumer 처리량 한계 확인
* AI 요청 이벤트 유입량이 처리량을 초과하는 지점 확인
* `deadline:requested:stream` backlog가 쌓이는 조건 확인
* `ai-service-group` pending entry 증가 여부 확인
* AI 처리 결과 이벤트가 `deadline:generated:stream`에 발행되는 속도 확인
* ai-service 단일 인스턴스 처리 한계 확인

### k6 확인

* 이벤트 주입 TPS
* p95/p99
* 실패율
* checks 성공률

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
```

### DB 확인

```sql
select status, count(*)
from ai_service.p_ai_message
group by status;
```

```sql
select date_trunc('minute', created_at) as minute, count(*)
from ai_service.p_ai_message
group by minute
order by minute desc;
```

### Grafana/Zipkin 확인

* delivery-service HTTP 요청 처리량
* delivery-service Redis XADD 실패 여부
* ai-service CPU/heap
* ai-service JVM GC pause
* Redis memory
* Redis command latency
* PostgreSQL write 부하
* Hikari active/pending connection
* AI 처리 trace duration

### 트러블슈팅 방향

| 증상                              | 개선 후보                                       |
| ------------------------------- | ------------------------------------------- |
| delivery-service 테스트 API p95 증가 | Gateway, delivery-service, Redis XADD 병목 확인 |
| XINFO GROUPS lag 지속 증가          | ai-service consumer 수 증가, read count 조정     |
| pending 증가                      | ACK 위치 확인, 예외 처리 후 ACK 여부 점검                |
| AI Consume TPS 낮음               | consumer batch 처리, DB 저장 로직 개선              |
| generated stream 발행량 부족         | 결과 이벤트 발행 로직, Redis producer 확인             |
| ai-service CPU 100% 지속          | DTO 변환, 로그 처리, mock 처리 로직 최적화               |
| Hikari pending 증가               | DB connection pool 조정, insert/index 확인      |
| GC pause 증가                     | heap 설정, 객체 생성 많은 로직 개선                     |

### 개선 실험

1차: 현재 설정 기준 AI Consume TPS 기록

2차: XREAD COUNT 또는 read batch 크기 조정 후 재측정

3차: ai-service consumer concurrency 증가 후 재측정

4차: ai-service 컨테이너 인스턴스 증설 후 재측정

5차: DB 저장 로직과 connection pool 조정 후 재측정

### 비교 항목

* HTTP TPS
* AI Event TPS
* AI Consume TPS
* Generated Event TPS
* requested stream lag 최대값
* pending entry 최대값
* AI 처리 지연 p95/p99
* delivery-service CPU/heap
* ai-service CPU/heap
* DB active/pending connection

## 테스트 2. AI Mock Stress 테스트

### 대상

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream
-> ai-service consumer
-> mock AI 처리
-> p_ai_message 저장
-> deadline:generated:stream
```

이 테스트는 AI 이벤트 유입량을 점진적으로 늘려 ai-service가 어느 지점에서 밀리는지 확인한다.

Baseline 테스트가 정상 기준선 수집이라면, Stress 테스트는 병목 발생 지점을 찾는 것이 목적이다.

### 목적

* 높은 이벤트 유입량에서 ai-service 처리 한계 확인
* requested stream lag 증가 시점 확인
* pending entry 증가 시점 확인
* CPU, heap, GC, DB connection 병목 확인
* consumer 설정 변경 전후 처리량 비교

### k6 확인

* 이벤트 주입 TPS
* p95/p99
* 실패율
* checks 성공률

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
```

### Grafana 확인

* delivery-service HTTP 처리량
* delivery-service CPU/heap
* ai-service CPU 최대값
* ai-service heap 최대값
* GC pause
* Redis memory
* DB active/pending connection
* domain-b VM CPU

### 트러블슈팅 방향

| 증상                          | 개선 후보                                         |
| --------------------------- | --------------------------------------------- |
| delivery-service API 실패율 증가 | 테스트 API 병목, Redis XADD 실패, Gateway timeout 확인 |
| requested stream lag 급증     | consumer concurrency 증가, ai-service 인스턴스 증설   |
| pending entry 증가            | ACK 처리 위치, 예외 처리, batch 처리 방식 점검              |
| CPU 90% 이상 지속               | CPU 병목 로직 최적화, 인스턴스 증설                        |
| heap 지속 증가                  | 객체 생성 로직, 로그 누적, batch 크기 확인                  |
| Hikari pending 증가           | DB connection pool 조정, transaction 범위 축소      |
| Generated Event TPS가 낮음     | 결과 이벤트 발행 로직과 Redis producer 확인               |

### 개선 실험

1차: VU 30~50 기준 처리량 기록

2차: VU 80~150 기준 병목 발생 지점 기록

3차: consumer read count 조정 후 재측정

4차: consumer concurrency 증가 후 재측정

5차: ai-service 인스턴스 증설 후 재측정

### 비교 항목

* HTTP TPS
* AI Event TPS
* AI Consume TPS
* Generated Event TPS
* requested stream lag 최대값
* pending entry 최대값
* AI 처리 지연 p95/p99
* delivery-service CPU/heap
* ai-service CPU/heap
* DB active/pending connection

## 테스트 3. ai-service 장애 복구 성능

### 대상

```text
ai-service 중지
-> delivery-service 테스트 전용 API로 AI 요청 이벤트 주입
-> deadline:requested:stream backlog 생성
-> ai-service 복구
-> backlog 처리
```

ai-service가 중지된 동안에도 delivery-service 테스트 전용 API는 살아 있어야 한다.

이 상태에서 AI 요청 이벤트를 Redis Stream에 계속 주입하고, ai-service 복구 후 얼마나 빠르게 backlog를 처리하는지 확인한다.

이 테스트는 단순 장애 시나리오 검증이 아니라, 복구 후 처리량과 회복 시간을 측정하는 성능 테스트다.

### 목적

* ai-service 장애 중 delivery-service 테스트 전용 API가 정상적으로 이벤트를 발행하는지 확인
* ai-service 장애 중 backlog 누적량 확인
* ai-service 복구 후 backlog 처리 속도 확인
* pending entry가 비정상적으로 증가하지 않는지 확인
* 복구 직후 CPU/heap/DB 부하 확인
* ai-service 단일 인스턴스와 다중 인스턴스 복구 속도 비교

### 장애 유도

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-503802 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml stop ai-service"
```

### 장애 중 이벤트 주입

```http
POST /api/v1/deliveries/test/deadline-requested
```

장애 중에는 ai-service가 중지되어 있으므로 `deadline:generated:stream`과 `p_ai_message`는 증가하지 않는 것이 정상이다.

대신 `deadline:requested:stream`의 lag 또는 backlog가 증가해야 한다.

### 복구

```bash
gcloud compute ssh hublink-domain-b-vm \
  --zone asia-northeast3-a \
  --project hublink-503802 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.domain-b.yml start ai-service"
```

### k6 확인

* 장애 중 이벤트 주입 TPS
* p95/p99
* 실패율
* checks 성공률

### Redis 확인

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
redis-cli -h 10.10.0.40 -p 6379 XINFO CONSUMERS deadline:requested:stream ai-service-group
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
```

### DB 확인

```sql
select date_trunc('minute', created_at) as minute, count(*)
from ai_service.p_ai_message
group by minute
order by minute desc;
```

### Grafana 확인

* delivery-service 장애 중 HTTP 요청 처리량
* delivery-service Redis XADD 실패 여부
* ai-service 복구 직후 CPU/heap
* ai-service GC pause
* Redis memory
* Redis command latency
* PostgreSQL write 부하
* Hikari active/pending connection

### 기대 흐름

```text
ai-service 중지
-> delivery-service 테스트 전용 API 호출
-> deadline:requested:stream에 이벤트 누적
-> deadline:generated:stream 증가 없음
-> p_ai_message 증가 없음
-> ai-service 재시작
-> requested stream backlog consume
-> p_ai_message 저장 증가
-> deadline:generated:stream 증가
-> ACK 처리
```

### 트러블슈팅 방향

| 증상                        | 개선 후보                                       |
| ------------------------- | ------------------------------------------- |
| ai-service 중지 중 이벤트 주입 실패 | 테스트 API 위치 확인, delivery-service Redis 연결 확인 |
| 복구 후 backlog가 천천히 감소      | ai-service 인스턴스 증설, consumer concurrency 증가 |
| pending이 증가함              | ACK 위치, 예외 처리, validation 실패 처리 후 ACK 여부 확인 |
| 복구 직후 CPU 100% 지속         | batch 크기 제한, 처리량 제한                         |
| DB pending 증가             | DB connection pool 조정, insert 성능 개선         |
| generated 이벤트 발행 지연       | 결과 이벤트 발행 로직, Redis producer 점검             |

### 개선 실험

1차: ai-service 단일 인스턴스 복구 속도 측정

2차: consumer read count 또는 batch 크기 조정 후 재측정

3차: ai-service consumer concurrency 증가 후 재측정

4차: ai-service 인스턴스 증설 후 backlog 처리 속도 재측정

5차: DB connection pool 조정 후 복구 처리량 재측정

### 비교 항목

* requested stream lag 최대값
* pending entry 최대값
* backlog 회복 시간
* AI Consume TPS
* Generated Event TPS
* delivery-service CPU/heap
* ai-service CPU/heap
* DB active/pending connection
* 복구 완료 시간

## 테스트 4. AI Spike 테스트

### 대상

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream
-> ai-service consumer
```

### 목적

* 갑작스러운 AI 요청 이벤트 증가 시 ai-service와 Redis Stream의 회복력 확인
* requested stream lag가 급증한 뒤 정상 범위로 감소하는지 확인
* ai-service pending entry가 비정상적으로 쌓이지 않는지 확인
* DB connection pool이 고갈 후 회복되는지 확인

### 관찰

* requested stream lag 최대값
* ai-service pending entry 최대값
* AI Consume TPS 변화
* Generated Event TPS 변화
* AI 처리 지연 p95/p99
* delivery-service CPU/heap
* ai-service CPU/heap
* Hikari active/pending connection
* Redis memory

### 트러블슈팅 방향

| 증상                  | 개선 후보                               |
| ------------------- | ----------------------------------- |
| spike 이후 lag가 줄지 않음 | consumer 수 증가, 인스턴스 증설              |
| pending 증가          | ACK 누락, 예외 처리 위치 확인                 |
| DB connection 고갈    | connection pool 조정, insert batch 검토 |
| p99 급증              | GC, DB, Redis command latency 확인    |

## 테스트 5. AI Soak 테스트

### 대상

```text
POST /api/v1/deliveries/test/deadline-requested
-> deadline:requested:stream
-> ai-service consumer
```

### 목적

* 장시간 AI 이벤트 처리 중 heap, connection, Redis memory, pending entry 누적 확인
* Prometheus/Grafana 지표 수집 안정성 확인
* 로그와 Redis Stream data 누적에 따른 disk/memory 사용량 확인
* 장시간 실행 중 ai-service가 안정적으로 consume과 ACK를 수행하는지 확인

### 관찰

* delivery-service CPU/heap
* ai-service heap
* ai-service GC pause
* Redis memory
* requested stream lag
* ai-service pending entry
* DB active/pending connection
* AI 처리 성공률
* AI 처리 실패율
* 로그 누적량

### 트러블슈팅 방향

| 증상                  | 개선 후보                                    |
| ------------------- | ---------------------------------------- |
| heap 지속 증가          | 객체 생성 로직, batch 크기, 로그 누적 확인             |
| Redis memory 지속 증가  | stream trimming 정책 검토                    |
| pending 누적          | ACK 누락, 예외 처리 경로 확인                      |
| DB connection 회수 지연 | transaction 범위, connection pool 설정 확인    |
| 장시간 후 처리량 저하        | GC pause, Redis memory, DB wait event 확인 |

## 병목별 확인 기준

| 병목             | k6 증상                       | Grafana/Prometheus 또는 Redis 증상      | 추가 확인                            |
| -------------- | --------------------------- | ----------------------------------- | -------------------------------- |
| 이벤트 주입 API 병목  | 모든 요청 p95 증가                | delivery-service CPU 상승             | Gateway/API 로그, Redis XADD 실패 여부 |
| ai-service CPU | AI 처리 지연 증가                 | ai-service CPU 상승                   | consumer log, Zipkin span        |
| JVM heap       | p99 급증                      | heap 상승, GC pause 증가                | GC log, heap 변화                  |
| DB connection  | AI 처리 완료 지연, timeout        | Hikari pending 증가                   | pg_stat_activity                 |
| Redis Stream   | AI 요청 이벤트 처리 지연             | requested stream lag 증가, pending 증가 | XPENDING, XINFO GROUPS           |
| Redis memory   | 장시간 테스트 후 지연 증가             | Redis memory 증가                     | stream length, trim 필요 여부        |
| 결과 이벤트 발행      | AI 처리는 됐지만 generated 이벤트 부족 | deadline:generated 증가 없음            | Redis producer log, 발행 예외        |

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

Hikari pending connection:

```promql
hikaricp_connections_pending
```

Redis Stream lag, pending, stream length는 ai-service의 Micrometer Gauge로 Prometheus에 노출한다.
Grafana 대시보드는 `AI Redis Stream Load Test`를 사용한다.

주요 PromQL:

consumer group lag:

```promql
redis_stream_group_lag{stream="deadline:requested:stream", group="ai-service-group"}
```

선택한 시간 범위의 lag 최대값:

```promql
max_over_time(redis_stream_group_lag{stream="deadline:requested:stream", group="ai-service-group"}[$__range])
```

pending:

```promql
redis_stream_pending_messages{stream="deadline:requested:stream", group="ai-service-group"}
```

선택한 시간 범위의 pending 최대값:

```promql
max_over_time(redis_stream_pending_messages{stream="deadline:requested:stream", group="ai-service-group"}[$__range])
```

consumer별 pending:

```promql
redis_stream_consumer_pending_messages{stream="deadline:requested:stream", group="ai-service-group"}
```

stream length:

```promql
redis_stream_length{stream=~"deadline:requested:stream|deadline:generated:stream"}
```

지표 수집 상태:

```promql
redis_stream_metrics_refresh_success
```

`redis_stream_metrics_refresh_success`가 0이면 ai-service가 Redis Stream 상태를 읽지 못한 것이다.
이 경우 Redis 연결, stream/group 존재 여부, ai-service 로그의 `REDIS_STREAM_METRICS_REFRESH_FAILED`를 먼저 확인한다.

## Grafana 기반 Redis Stream 부하 테스트 진행 순서

테스트 목적:

* 부하 중 AI Consumer가 아직 읽지 못한 `deadline:requested:stream` backlog가 얼마나 쌓이는지 lag로 측정한다.
* ACK 전 처리 실패나 ACK 누락 여부는 pending으로 보조 확인한다.
* `read-count=300` 기준으로 consumer concurrency 1, 2, 3의 lag 최대값과 회복 시간을 비교한다.
* AI 외부 API 비용을 제거하기 위해 `AI_ENABLED=false` 상태에서 Redis Stream consumer 처리량만 본다.

사전 준비:

1. data-monitor VM에서 Prometheus와 Grafana를 실행한다.
2. Grafana `Hublink / AI Redis Stream Load Test` 대시보드를 연다.
3. ai-service `/actuator/prometheus`에 `redis_stream_group_lag`, `redis_stream_pending_messages`가 노출되는지 확인한다.
4. `redis_stream_metrics_refresh_success`가 1인지 확인한다.
5. Redis Stream 상태를 초기화하거나, 이전 테스트의 stream length/pending 값을 기준값으로 기록한다.

공통 테스트 순서:

1. ai-service 설정을 테스트할 값으로 배포한다.
   * `ai.stream.consumer.deadline-requested.read-count=300`
   * `ai.stream.consumer.deadline-requested.concurrency=1`, `2`, `3` 중 하나
   * `ai.enabled=false`
2. ai-service를 재시작하고 Grafana에서 `Consumer Count`가 기대한 값과 맞는지 확인한다.
3. Grafana 시간 범위를 `Last 30 minutes` 또는 테스트 전체 시간이 들어가는 범위로 맞춘다.
4. k6 stress 테스트를 시작한다.
5. 부하 중 `Group Lag`, `Pending Messages`, `Pending By Consumer`, `Stream Length`를 관찰한다.
6. k6 주입이 끝난 뒤 lag가 0 또는 기준값으로 회복되는 시각을 기록한다.
7. `Max Lag In Range`와 `Max Pending In Range` 값을 결과표에 기록한다.
8. 같은 부하 조건으로 concurrency만 바꿔 1, 2, 3을 반복한다.

결과 기록 항목:

| 항목 | 설명 |
| --- | --- |
| concurrency | ai-service consumer 병렬 처리 수 |
| read-count | XREAD COUNT 설정값 |
| k6 scenario | stress/load/spike 등 실행한 시나리오 |
| max lag | Grafana `Max Lag In Range` 값 |
| max pending | Grafana `Max Pending In Range` 값 |
| peak time | lag가 최대가 된 시각 |
| recovery time | k6 종료 또는 peak 이후 lag가 기준값으로 돌아오기까지 걸린 시간 |
| generated stream delta | 테스트 전후 `deadline:generated:stream` 증가량 |
| error signal | k6 실패율, ai-service 에러 로그, refresh success 0 발생 여부 |

해석 기준:

* lag가 증가하면 consumer가 아직 읽지 못한 backlog가 쌓이는 것이다.
* pending이 거의 0인데 lag가 증가하면 ACK 누락 문제가 아니라 consumer 처리량 부족이다.
* pending이 거의 0인데 stream length만 크면 ACK 전 병목이 아니라 stream entry 보존 효과일 수 있다.
* pending이 계속 증가하면 consumer가 읽은 뒤 ACK 전 처리 시간이 밀리거나 예외로 ACK가 누락되는 상황이다.
* consumer별 pending이 한쪽에 몰리면 concurrency는 늘었지만 처리 분산이 기대만큼 되지 않는 것이다.
* `redis_stream_metrics_refresh_success`가 0인 구간은 pending 최대값 판단에서 제외하거나 Redis CLI로 교차 확인한다.

## Redis 병목 확인 명령

requested stream 길이:

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:requested:stream
```

generated stream 길이:

```bash
redis-cli -h 10.10.0.40 -p 6379 XLEN deadline:generated:stream
```

ai-service pending 확인:

```bash
redis-cli -h 10.10.0.40 -p 6379 XPENDING deadline:requested:stream ai-service-group
```

consumer group 정보 확인:

```bash
redis-cli -h 10.10.0.40 -p 6379 XINFO GROUPS deadline:requested:stream
```

consumer별 pending 확인:

```bash
redis-cli -h 10.10.0.40 -p 6379 XINFO CONSUMERS deadline:requested:stream ai-service-group
```

Redis memory 확인:

```bash
redis-cli -h 10.10.0.40 -p 6379 INFO memory
```

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

AI 메시지 처리 상태:

```sql
select status, count(*)
from ai_service.p_ai_message
group by status;
```

AI 처리량:

```sql
select date_trunc('minute', created_at) as minute, count(*)
from ai_service.p_ai_message
group by minute
order by minute desc;
```

## 결과 기록 양식

| 항목                           | 값    |
| ---------------------------- | ---- |
| 테스트 일시                       |      |
| 배포 commit                    |      |
| 테스트명                         |      |
| 테스트 모드                       | mock |
| 대상 API 또는 stream             |      |
| k6 script                    |      |
| VU / duration                |      |
| 총 요청 수                       |      |
| HTTP TPS                     |      |
| AI Event TPS                 |      |
| AI Consume TPS               |      |
| Generated Event TPS          |      |
| 평균 응답 시간                     |      |
| p95                          |      |
| p99                          |      |
| 실패율                          |      |
| checks 성공률                   |      |
| requested stream length 최대값  |      |
| requested stream lag 최대값     |      |
| generated stream length      |      |
| ai-service pending entry 최대값 |      |
| backlog 회복 시간                |      |
| AI 처리 지연 평균                  |      |
| AI 처리 지연 p95                 |      |
| AI 처리 지연 p99                 |      |
| AI 메시지 생성량                   |      |
| Redis memory 최대값             |      |
| DB active connection 최대값     |      |
| DB pending connection 최대값    |      |
| delivery-service CPU 최대값     |      |
| delivery-service heap 최대값    |      |
| ai-service CPU 최대값           |      |
| ai-service heap 최대값          |      |
| GC pause 특이점                 |      |
| 적용한 개선                       |      |
| 개선 전 결과                      |      |
| 개선 후 결과                      |      |
| 남은 이슈                        |      |

## 추천 진행 순서

1. scenario-test-plan.md에서 AI 단건 정상 흐름 확인
2. AI validation 실패 처리 확인
3. delivery-service 테스트 전용 API로 requested stream 이벤트 주입 확인
4. AI mock 기반 baseline 테스트
5. AI mock 기반 load 테스트
6. AI mock 기반 stress 테스트
7. ai-service consumer read count 또는 batch 크기 조정 실험
8. ai-service consumer concurrency 조정 실험
9. ai-service 인스턴스 증설 실험
10. ai-service 장애 복구 성능 테스트
11. AI spike 테스트
12. AI soak 테스트
13. 개선 전후 수치 정리
