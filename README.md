# HubLink

> 기존 프로젝트: [Team4-MSA/hublink](https://github.com/Team4-MSA/hublink)

기존 HubLink MSA 팀 프로젝트를 기반으로 배포 자동화, 관측 환경, 배송 성능 테스트를 보강한 개인 확장 프로젝트

## 기술 구성

| 구분 | 기술 |
| --- | --- |
| 애플리케이션 | Java 17, Spring Boot 3.5.14, Spring Cloud |
| 데이터 | PostgreSQL 16, Redis 7.2 |
| 메시징 | Kafka, Redis Stream |
| 인프라 | GCP Compute Engine, Cloud Run Job, Terraform, Docker Compose, GitHub Actions |
| 관측 | Prometheus, Grafana, Loki |

## 배포 구조

Compute Engine VM 5개와 Cloud Run Job으로 서비스를 나누어 배포

서버별 CPU, 메모리, 디스크, IP 세부 스펙은 [GCP 서버 스펙](docs/gcp-server-spec.md) 참고

```text
platform-vm
  - eureka-server
  - config-server
  - api-gateway
  - Kafka UI (필요할 때만 실행)
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

서비스 컨테이너는 VM별 Docker 네트워크에 구성하고 서비스 간 통신은 GCP 내부 IP로 연결

## 문서

### 인프라와 배포

- [GCP 인프라 구성](docs/gcp-infra.md)
- [GCP 서버 스펙](docs/gcp-server-spec.md)
- [GCP GitHub Actions CI/CD](docs/gcp-cicd.md)

### 테스트와 성능 개선

- [배송 도메인 시나리오 테스트 계획](docs/scenario/scenario-test-plan.md)
- [배송 성능 테스트 기록](docs/performance/delivery/README.md)
- [배송 성능 테스트 방법](docs/performance/performance-test-plan.md)
