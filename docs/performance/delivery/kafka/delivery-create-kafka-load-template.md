# 배송 생성 Kafka Load 테스트 결과

### 1. 테스트 목적

배송 생성 Kafka Load 테스트는 `delivery.create` 이벤트 유입량이 delivery-service consumer 처리량을 초과하는 지점과 병목 지표를 확인하기 위한 테스트다.

이번 테스트에서는 order-service, stock-service, Gateway 흐름을 우회하고 delivery-service 테스트 API를 통해 `delivery.create` Kafka 이벤트를 직접 주입한다. k6의 HTTP 응답은 Kafka publish API의 성공 여부를 의미하며, 실제 배송 생성 완료 여부는 Kafka consumer lag, DB 배송 생성량, outbox 상태, 결과 topic 증가량, Grafana 지표를 함께 확인한다.

테스트 대상 흐름은 다음과 같다.

```text
k6
-> delivery-service 테스트 API
-> delivery.create
-> delivery-service Kafka consumer
-> 배송 생성 로직
-> p_deliveries 저장
-> p_delivery_route_histories 저장
-> p_delivery_outboxes 저장
-> delivery.create.succeed / failed / dlq
```

### 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 일시 | 미기록 |
| 배포 commit | 미기록 |
| 테스트명 | 배송 생성 Kafka Load 테스트 |
| 테스트 모드 | Kafka 이벤트 직접 주입 |
| 대상 API | `POST /api/v1/deliveries/test/delivery-create` |
| 대상 topic | `delivery.create`, `delivery.create.succeed`, `delivery.create.failed`, `delivery.create.dlq` |
| k6 script | `delivery-create-kafka-load.js` |
| 실행 위치 | `hublink-k6-load-test` Cloud Run Job |
| 실행 명령어 | 미기록 |
| VU / duration | 미기록 |
| 부하 패턴 | 미기록 |
| 요청 간격 | `SLEEP_SECONDS` 미기록 |
| 대상 URL | `DELIVERY_BASE_URL` 미기록 |

실행 명령어

```bash
# 예시
STAGES='[{"duration":"1m","target":20},{"duration":"5m","target":20},{"duration":"2m","target":0}]' \
SLEEP_SECONDS=1 \
./run-k6.sh delivery-create-kafka-load.js
```

부하 패턴

- ramp-up: 미기록
- 유지 구간: 미기록
- ramp-down: 미기록
- 총 테스트 시간: 미기록

### 3. 시작 전 상태

#### Kafka

```bash
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group delivery-service \
  --describe
```

결과:

| topic | partition | current offset | log end offset | lag |
| --- | ---: | ---: | ---: | ---: |
| `delivery.create` | 미기록 | 미기록 | 미기록 | 미기록 |

#### DB

배송 생성량 확인 쿼리:

```sql
select date_trunc('minute', created_at) as minute, count(*)
from delivery_service.p_deliveries
group by minute
order by minute desc;
```

배송 전체 건수:

```sql
select count(*)
from delivery_service.p_deliveries;
```

배송 경로 이력 전체 건수:

```sql
select count(*)
from delivery_service.p_delivery_route_histories;
```

#### Outbox

```sql
select status, count(*)
from delivery_service.p_delivery_outboxes
group by status;
```

결과:

| status | count |
| --- | ---: |
| PENDING | 미기록 |
| PUBLISHED | 미기록 |
| FAILED | 미기록 |

### 4. k6 실행 결과

| 항목 | 값 |
| --- | --- |
| 총 요청 수 | 미기록 |
| HTTP TPS | 미기록 |
| 실패 요청 수 | 미기록 |
| 실패율 | 미기록 |
| checks 성공률 | 미기록 |
| 평균 응답 시간 | 미기록 |
| p95 응답 시간 | 미기록 |
| p99 응답 시간 | 미기록 |
| 최대 응답 시간 | 미기록 |
| max VU | 미기록 |
| 테스트 시간 | 미기록 |

Threshold 결과:

```text
checks
- rate>0.95

http_req_duration
- p(95)<2500
- p(99)<5000

http_req_failed
- rate<0.05
```

### 5. Kafka / DB / Outbox 전후 비교

#### Kafka consumer lag

| 항목 | 테스트 전 | 테스트 중 최대 | 테스트 후 | 회복 시간 |
| --- | ---: | ---: | ---: | --- |
| `delivery.create` lag | 미기록 | 미기록 | 미기록 | 미기록 |

#### Kafka topic 증가량

| topic | 테스트 전 | 테스트 후 | 증가량 |
| --- | ---: | ---: | ---: |
| `delivery.create` | 미기록 | 미기록 | 미기록 |
| `delivery.create.succeed` | 미기록 | 미기록 | 미기록 |
| `delivery.create.failed` | 미기록 | 미기록 | 미기록 |
| `delivery.create.dlq` | 미기록 | 미기록 | 미기록 |

#### DB 증가량

| 항목 | 테스트 전 | 테스트 후 | 증가량 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 미기록 | 미기록 | 미기록 |
| `p_delivery_route_histories` | 미기록 | 미기록 | 미기록 |

#### Outbox 상태

| status | 테스트 전 | 테스트 후 | 증가량 |
| --- | ---: | ---: | ---: |
| PENDING | 미기록 | 미기록 | 미기록 |
| PUBLISHED | 미기록 | 미기록 | 미기록 |
| FAILED | 미기록 | 미기록 | 미기록 |

### 6. Grafana 지표

대시보드:

```text
delivery internal bottleneck
```

조회 시간 범위:

```text
테스트 시작 시각: 미기록
테스트 종료 시각: 미기록
회복 관찰 종료 시각: 미기록
```

| 항목 | 값 |
| --- | --- |
| delivery-service CPU 최대값 | 미기록 |
| delivery-service heap 최대값 | 미기록 |
| JVM GC pause 특이점 | 미기록 |
| Hikari active connection 최대값 | 미기록 |
| Hikari pending connection 최대값 | 미기록 |
| Kafka broker CPU 최대값 | 미기록 |
| Kafka broker network 최대값 | 미기록 |
| PostgreSQL write 부하 특이점 | 미기록 |
| DB wait event 특이점 | 미기록 |
| delivery VM CPU 최대값 | 미기록 |
| 에러 로그 특이점 | 미기록 |

### 7. 결과 기록

| 항목 | 값 |
| --- | --- |
| 테스트 일시 | 미기록 |
| 배포 commit | 미기록 |
| 테스트명 | 배송 생성 Kafka Load 테스트 |
| 테스트 모드 | Kafka 이벤트 직접 주입 |
| 대상 API 또는 topic | `POST /api/v1/deliveries/test/delivery-create`, `delivery.create` |
| k6 script | `delivery-create-kafka-load.js` |
| VU / duration | 미기록 |
| 총 요청 수 | 미기록 |
| HTTP TPS | 미기록 |
| Delivery Event TPS | 미기록 |
| Delivery Create TPS | 미기록 |
| Outbox Publish TPS | 미기록 |
| 평균 응답 시간 | 미기록 |
| p95 | 미기록 |
| p99 | 미기록 |
| 실패율 | 미기록 |
| checks 성공률 | 미기록 |
| `delivery.create` lag 최대값 | 미기록 |
| `delivery.create` lag 최종값 | 미기록 |
| Kafka lag 회복 시간 | 미기록 |
| delivery row 생성량 | 미기록 |
| route history row 생성량 | 미기록 |
| `delivery.create.succeed` 증가량 | 미기록 |
| `delivery.create.failed` 증가량 | 미기록 |
| `delivery.create.dlq` 증가량 | 미기록 |
| outbox PENDING 최대값 | 미기록 |
| outbox PENDING 최종값 | 미기록 |
| outbox 회복 시간 | 미기록 |
| DB active connection 최대값 | 미기록 |
| DB pending connection 최대값 | 미기록 |
| delivery-service CPU 최대값 | 미기록 |
| delivery-service heap 최대값 | 미기록 |
| GC pause 특이점 | 미기록 |
| 적용한 개선 | 없음 |
| 개선 전 결과 | N/A |
| 개선 후 결과 | N/A |
| 남은 이슈 | 미기록 |

### 8. 결과 해석

미기록.

작성 시에는 k6 HTTP 성공률과 실제 배송 생성 성공률을 분리해서 해석한다.

- k6 성공: delivery-service 테스트 API가 Kafka publish 요청을 정상 접수했는지
- 배송 생성 성공: `p_deliveries` 증가량과 `delivery.create.succeed` 증가량이 k6 성공 요청 수와 일치하는지
- consumer 병목: `delivery.create` lag가 부하 중 증가하고 종료 후 회복되는지
- DB 병목: Hikari pending, DB wait event, PostgreSQL write 지표가 함께 증가하는지
- outbox 병목: 배송 생성은 완료됐지만 PENDING outbox가 누적되는지

### 9. 최종 판단

```text
판정: 미기록

- 총 요청 수: 미기록
- HTTP 실패율: 미기록
- checks 성공률: 미기록
- delivery.create lag 최대값: 미기록
- delivery.create lag 최종값: 미기록
- Kafka lag 회복 시간: 미기록
- delivery row 생성량: 미기록
- delivery.create.succeed 증가량: 미기록
- failed/dlq 증가량: 미기록
- p95: 미기록
- p99: 미기록
- 주요 병목: 미기록
```
