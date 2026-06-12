# HubLink

> 기존 프로젝트: [Team4-MSA/hublink](https://github.com/Team4-MSA/hublink)

기존 HubLink MSA 프로젝트를 기반으로, 배포 환경 구성, 성능 테스트, 모니터링, 트러블슈팅을 보강하기 위한 확장 프로젝트

## 배포 구조

GCP VM 6개를 기준으로 서비스를 나누어 배포한다.

서버별 CPU, 메모리, 디스크, IP 세부 스펙은 [GCP 서버 스펙](docs/gcp-server-spec.md)에서 확인한다.

```text
platform-vm
  - eureka-server
  - config-server
  - api-gateway

domain-a-vm
  - user-service
  - company-service
  - hub-service
  - product-service

domain-b-vm
  - order-service
  - stock-service
  - delivery-service
  - slack-service
  - ai-service

data-vm
  - PostgreSQL
  - Redis
  - Kafka

monitoring-vm
  - Kafka UI
  - Zipkin
  - Prometheus
  - Loki
  - Grafana

load-test-vm
  - k6
  - load test scripts
```

서비스 컨테이너들은 각 VM의 Docker 네트워크에 따로 올라가므로, 서비스 간 통신은 GCP 내부 IP를 기준으로 연결한다.

## VM 구성

| VM | 역할 | 주요 포트 |
| --- | --- | --- |
| platform-vm | 서비스 디스커버리, 설정 서버, API 진입점 | 19090, 19091, 19092 |
| domain-a-vm | 사용자, 업체, 허브, 상품 도메인 서비스 | 19093, 19095, 19096, 19097 |
| domain-b-vm | 주문, 재고, 배송, 알림, AI 도메인 서비스 | 19094, 19098, 19099, 19100, 19101 |
| data-vm | 데이터 저장소, 메시지 브로커 | 5432, 6379, 9092 |
| monitoring-vm | 모니터링, 로그, Kafka UI, 분산 추적 | 8082, 9411, 9090, 3100, 3000 |
| load-test-vm | GCP 내부 부하 발생 | 없음 |

## 고정 IP

| VM | Internal IP | External IP |
| --- | --- | --- |
| platform-vm | `10.10.0.10` | `34.50.23.39` |
| domain-a-vm | `10.10.0.20` | `8.230.24.217` |
| domain-b-vm | `10.10.0.30` | `8.230.9.99` |
| data-vm | `10.10.0.40` | `34.64.89.47` |
| monitoring-vm | `10.10.0.60` | `34.50.1.195` |
| load-test-vm | `10.10.0.50` | `34.22.78.126` |

## 모니터링 도구

| 도구 | 주소 | 용도 |
| --- | --- | --- |
| Grafana | `http://34.50.1.195:3000` | 메트릭, 로그 통합 조회 |
| Prometheus | `http://34.50.1.195:9090` | JVM, HTTP, 시스템 메트릭 조회 |
| Zipkin | `http://34.50.1.195:9411` | 분산 추적 |
| Kafka UI | `http://34.50.1.195:8082` | topic, message, consumer lag 확인 |
| Loki | Grafana datasource | Docker 로그 검색 |

## 문서

### 인프라와 배포

- [GCP 인프라 구성](docs/gcp-infra.md)
- [GCP 서버 스펙](docs/gcp-server-spec.md)
- [GCP GitHub Actions CI/CD](docs/gcp-cicd.md)

### 테스트 계획

- [배송 도메인 시나리오 테스트 계획](docs/scenario/scenario-test-plan.md)
- [배송 도메인 성능 테스트 계획](docs/performance/performance-test-plan.md)
- [AI 도메인 시나리오 테스트 계획](docs/scenario/scenario-ai-test-plan.md)
- [AI 도메인 성능 테스트 및 트러블슈팅 계획](docs/performance/performance-ai-test-plan.md)

### 개발 규칙

- [코드 컨벤션](docs/code-convention.md)
- [Git 컨벤션](docs/git-convention.md)
- [서비스 포트](docs/service-port.md)
