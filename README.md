# HubLink

> 기존 프로젝트: [Team4-MSA/hublink](https://github.com/Team4-MSA/hublink)

기존 HubLink MSA 프로젝트를 기반으로, 배포 환경 구성, 성능 테스트, 모니터링, 트러블슈팅을 보강하기 위한 확장 프로젝트

## 배포 구조

GCP VM 5개를 기준으로 서비스를 나누어 배포한다.

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

data-monitor-vm
  - PostgreSQL
  - Redis
  - Kafka
  - Kafka UI
  - Zipkin
  - Prometheus
  - Loki
  - Grafana

load-test-vm
  - k6
  - 부하 테스트 스크립트
```

서비스 컨테이너들은 각 VM의 Docker 네트워크에 따로 올라가므로, 서비스 간 통신은 GCP 내부 IP를 기준으로 연결한다.

## VM 구성

| VM | 역할 | 주요 포트 |
| --- | --- | --- |
| platform-vm | 서비스 디스커버리, 설정 서버, API 진입점 | 19090, 19091, 19092 |
| domain-a-vm | 사용자, 업체, 허브, 상품 도메인 서비스 | 19093, 19095, 19096, 19097 |
| domain-b-vm | 주문, 재고, 배송, 알림, AI 도메인 서비스 | 19094, 19098, 19099, 19100, 19101 |
| data-monitor-vm | 데이터 저장소, 메시징, 모니터링 | 5432, 6379, 9092, 8082, 9411, 9090, 3100, 3000 |
| load-test-vm | GCP 내부 부하 발생 | k6 실행 |

## 고정 내부 IP

| VM | Internal IP |
| --- | --- |
| platform-vm | `10.10.0.10` |
| domain-a-vm | `10.10.0.20` |
| domain-b-vm | `10.10.0.30` |
| data-monitor-vm | `10.10.0.40` |
| load-test-vm | `10.10.0.50` |

## 고정 외부 IP

| VM | External IP |
| --- | --- |
| platform-vm | `34.50.23.39` |
| domain-a-vm | `8.230.24.217` |
| domain-b-vm | `8.230.9.99` |
| data-monitor-vm | `34.64.89.47` |
| load-test-vm | `34.22.78.126` |

## 관측 도구

| 도구 | 주소 | 용도 |
| --- | --- | --- |
| Grafana | `http://34.64.89.47:3000` | 메트릭, 로그 통합 조회 |
| Prometheus | `http://34.64.89.47:9090` | JVM, HTTP, 시스템 메트릭 조회 |
| Zipkin | `http://34.64.89.47:9411` | 분산 추적 |
| Kafka UI | `http://34.64.89.47:8082` | topic, message, consumer lag 확인 |
| Loki | Grafana datasource | Docker 로그 검색 |

## 테스트 방향

이 프로젝트의 테스트 초점은 배송 도메인 흐름이다.

```text
Gateway 부하
주문 생성 이후 배송 생성 Kafka 흐름
배송 기사 배정과 배송 생성 처리량
배송 -> AI -> Slack Redis Stream 흐름
Kafka lag, Redis pending, JVM heap, p95/p99 기반 병목 확인
```

부하 테스트 스크립트는 `performance/k6`에서 관리하고, GitHub Actions의 `GCP Load Test Sync` workflow가 `load-test-vm`으로 동기화한다.

## 문서

- [GCP 인프라 구성](docs/gcp-infra.md)
- [배송 도메인 시나리오 테스트 계획](docs/scenario/scenario-test-plan.md)
- [배송 도메인 성능 테스트 계획](docs/performance/performance-test-plan.md)
- [AI 도메인 시나리오 테스트 계획](docs/scenario/ai-scenario-test-plan.md)
- [AI 도메인 성능 테스트 및 트러블슈팅 계획](docs/performance/ai-performance-test-plan.md)
