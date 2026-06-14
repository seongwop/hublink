# Delivery Performance Test Results

이 디렉터리는 배송 도메인 부하 테스트 결과를 누적 기록한다.

## 문서 위치

| 경로 | 용도 |
| --- | --- |
| `docs/performance/performance-test-plan.md` | 배송 성능 테스트 계획 |
| `performance/k6` | k6 실행 스크립트 |
| `docs/performance/results/delivery` | 배송 성능 테스트 결과 |

## 파일명 규칙

```text
{flow}-runNN-{load}.md
```

예시:

```text
kafka-create-run01-20vu.md
kafka-create-run02-30vu.md
logic-create-run01-baseline.md
```

## 배송 Kafka 생성 테스트 판단 기준

`delivery-create-kafka-load.js`는 `POST /api/v1/deliveries/test/delivery-create`를 호출해 `delivery.create` Kafka 이벤트를 직접 주입한다.

k6 결과는 Kafka 이벤트 publish API의 응답 결과이므로, 실제 배송 생성 성공 여부는 아래 지표와 함께 판단한다.

| 구분 | 확인 항목 |
| --- | --- |
| k6 | HTTP TPS, 실패율, checks 성공률, p95, p99 |
| Kafka | `delivery.create` consumer lag 최대값, 최종값, 회복 시간 |
| DB | `delivery_service.p_deliveries` 생성량, 분당 생성량 |
| Outbox | `p_delivery_outboxes` 상태별 건수, PENDING 회복 시간 |
| Kafka 결과 topic | `delivery.create.succeed`, `delivery.create.failed`, `delivery.create.dlq` 증가량 |
| Grafana | delivery-service CPU/heap, Hikari active/pending, Kafka broker, DB 지표 |

## Grafana

기본 접속 URL:

```text
http://34.50.1.195:3000
```

조회 대상 대시보드:

```text
delivery internal bottleneck
```

현재 Grafana 서버는 응답하지만 대시보드 API는 인증이 필요하다. 테스트 결과 작성 시 Grafana 로그인 세션, 계정, 또는 API 토큰이 필요하다.
