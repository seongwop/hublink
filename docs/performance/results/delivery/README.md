# Delivery Performance Test Results

배송 성능 테스트 결과를 테스트 축과 데이터셋 상태 기준으로 구분해 기록한다.

## 문서 위치

| 경로 | 용도 |
| --- | --- |
| `docs/performance/performance-test-plan.md` | 배송 성능 테스트 계획 |
| `performance/k6` | k6 실행 스크립트 |
| `docs/performance/results/delivery/kafka` | Kafka 주입, consumer, lag 테스트 결과 |
| `docs/performance/results/delivery/create/before-db-reset/concentrated` | 배송 생성 로직, 입력 집중, reset 도입 전 결과 |
| `docs/performance/results/delivery/create/before-db-reset/distributed` | 배송 생성 로직, 입력 분산, reset 도입 전 결과 |
| `docs/performance/results/delivery/create/db-reset/concentrated` | 배송 생성 로직, 입력 집중, reset 기준 결과 |
| `docs/performance/results/delivery/create/db-reset/distributed` | 배송 생성 로직, 입력 분산, reset 기준 결과 |
| `docs/performance/results/delivery/create/db-reset/concentrated/no-sleep` | 배송 생성 로직, 입력 집중, reset 기준, `SLEEP_SECONDS=0` 결과 |

## 디렉터리 규칙

```text
delivery/
+-- kafka/
+-- create/
    +-- before-db-reset/
    |   +-- concentrated/
    |   +-- distributed/
    +-- db-reset/
        +-- concentrated/
        |   +-- no-sleep/
        +-- distributed/
```

- `before-db-reset`: 테스트 종료 후 delivery 런타임 데이터 초기화가 자동으로 정착되기 전 결과
- `db-reset`: `11-reset-delivery-loadtest-baseline.sql` 같은 reset 기준을 적용한 뒤의 결과
- `concentrated`: 같은 `RECEIVER_COMPANY_ID` 또는 같은 목적지 허브로 요청을 모아 lock 경합을 유도한 결과
- `distributed`: `RECEIVER_COMPANY_IDS`를 여러 개 사용해 입력을 분산한 결과
- `no-sleep`: `SLEEP_SECONDS=0` 조건으로 think time 없이 밀어 넣은 결과

## 파일명 규칙

```text
{flow}-runNN-{load}.md
```

예시:

```text
kafka-create-run01-20vu.md
logic-create-run01-20vu.md
logic-create-run06-50vu.md
```

## Grafana

기본 접속 URL:

```text
http://34.50.1.195:3000
```

조회 대상 대시보드:

```text
Delivery Kafka Inbound Bottleneck
Delivery Create Logic Bottleneck
```
