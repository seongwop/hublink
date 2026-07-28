# HubLink

> 기존 프로젝트: [Team4-MSA/hublink](https://github.com/Team4-MSA/hublink)

기존 HubLink MSA 프로젝트를 기반으로, 배포 환경 구성, 성능 테스트, 모니터링, 트러블슈팅을 보강하기 위한 확장 프로젝트

## 배포 구조

Compute Engine VM 5개와 Cloud Run Job으로 서비스를 나누어 배포한다.

서버별 CPU, 메모리, 디스크, IP 세부 스펙은 [GCP 서버 스펙](docs/gcp-server-spec.md)에서 확인한다.

```text
platform-vm
  - eureka-server
  - config-server
  - api-gateway
  - Kafka UI (필요할 때만 실행)
  - Zipkin
  - Prometheus
  - Loki
  - Grafana

domain-a-vm
  - user-service
  - company-service
  - hub-service
  - product-service

domain-b-vm
  - order-service
  - stock-service
  - slack-service
  - ai-service

delivery-vm
  - delivery-service

data-vm
  - PostgreSQL
  - Redis
  - Kafka

Cloud Run Job
  - k6
  - load test scripts
```

서비스 컨테이너들은 각 VM의 Docker 네트워크에 따로 올라가므로, 서비스 간 통신은 GCP 내부 IP를 기준으로 연결한다.

## VM 구성

| VM | 역할 | 주요 포트 |
| --- | --- | --- |
| platform-vm | 서비스 디스커버리, 설정 서버, API 진입점, 모니터링 | 19090, 19091, 19092, 3000, 9090, 9411 |
| domain-a-vm | 사용자, 업체, 허브, 상품 도메인 서비스 | 19093, 19095, 19096, 19097 |
| domain-b-vm | 주문, 재고, 알림, AI 도메인 서비스 | 19094, 19098, 19100, 19101 |
| delivery-vm | 배송 전용 서비스 | 19099 |
| data-vm | 데이터 저장소, 메시지 브로커 | 5432, 6379, 9092 |

## 고정 IP

| VM | Internal IP | External IP |
| --- | --- | --- |
| platform-vm | `10.10.0.10` | `34.50.55.18` |
| domain-a-vm | `10.10.0.20` | 없음 |
| domain-b-vm | `10.10.0.30` | 없음 |
| delivery-vm | `10.10.0.70` | 없음 |
| data-vm | `10.10.0.40` | 없음 |

## 모니터링 도구

| 도구 | 주소 | 용도 |
| --- | --- | --- |
| Grafana | IAP 터널의 `http://localhost:3000` | 메트릭, 로그 통합 조회 |
| Prometheus | IAP 터널의 `http://localhost:9090` | JVM, HTTP, 시스템 메트릭 조회 |
| Zipkin | IAP 터널의 `http://localhost:9411` | 분산 추적 |
| Kafka UI | 필요할 때만 실행 후 IAP 터널의 `http://localhost:8082` | topic, message, consumer lag 확인 |
| Loki | Grafana datasource | Docker 로그 검색 |

Grafana, Prometheus, Kafka UI는 platform VM의 loopback 주소에만 바인딩하고 IAP SSH 포트 포워딩으로 접근한다. k6는 Direct VPC가 연결된 Cloud Run Job에서 내부 배송 주소로 부하를 전송한다.

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
