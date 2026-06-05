# HubLink k6 부하 테스트

이 디렉터리는 GCP `hublink-load-test-vm`에서 실행할 k6 스크립트를 관리한다.

## 실행 위치

부하는 로컬 PC가 아니라 GCP 내부 부하 테스트 VM에서 발생시킨다.

```text
hublink-load-test-vm
api-gateway: http://10.10.0.10:19091
```

## CI/CD 동기화

`performance/k6/**` 변경을 `develop-infra`에 push하면 GitHub Actions의 `GCP Load Test Sync` workflow가 실행된다.

동기화 위치는 다음과 같다.

```text
/opt/hublink/performance/k6
```

`.env.k6`는 git에 포함하지 않는다. VM에서 처음 한 번만 만들고, CI/CD는 스크립트 파일만 갱신한다.

## VM 접속

```bash
gcloud compute ssh hublink-load-test-vm \
  --zone asia-northeast3-a \
  --project hublink-498115
```

## 최초 준비

CI/CD 동기화 후 VM에서 한 번만 실행한다.

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

## Gateway Smoke

```bash
./run-k6.sh gateway-smoke.js
```

다른 경로를 확인할 때는 `TARGET_PATH`를 바꾼다.

```bash
TARGET_PATH=/v3/api-docs ./run-k6.sh gateway-smoke.js
```

## Gateway Load

```bash
./run-k6.sh gateway-load.js
```

대상 경로를 바꿀 때는 `PATHS`를 사용한다.

```bash
PATHS=/actuator/health,/v3/api-docs,/api/v1/deliveries ./run-k6.sh gateway-load.js
```

부하 단계를 바꿀 때는 `STAGES`를 사용한다.

```bash
STAGES='[{"duration":"1m","target":50},{"duration":"5m","target":50},{"duration":"1m","target":0}]' ./run-k6.sh gateway-load.js
```

## 배송 조회 부하

```bash
USER_ROLE=MASTER ./run-k6.sh delivery-list-load.js
```

확인 지표는 Gateway p95/p99, delivery-service p95/p99, PostgreSQL read load를 본다.

## 주문-배송 흐름 부하

```bash
./run-k6.sh order-delivery-flow.js
```

확인 지표는 order-service 요청 성공률, delivery.create Kafka lag, delivery-service consumer 처리량, Redis Stream pending을 본다.

## 결과 해석

주요 지표는 다음 값을 본다.

```text
http_reqs
http_req_failed
http_req_duration p(95)
http_req_duration p(99)
iterations
```

TPS는 다음 방식으로 계산한다.

```text
TPS = http_reqs / duration_seconds
```
