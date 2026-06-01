# HubLink GCP Infra Lab

> 기존 프로젝트: [Team4-MSA/hublink](https://github.com/Team4-MSA/hublink)

이 저장소는 기존 HubLink MSA 프로젝트를 기반으로, GCP 환경에서 서버 구성, 배포, 모니터링, 성능 테스트, 트러블슈팅을 실습하기 위한 레포지토리입니다.

---

## 배포 목표

GCP에 VM 4대를 만들고, 각 VM에 역할별 서비스를 나누어 배포합니다.

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
  - Grafana
```

서비스 컨테이너들은 같은 Docker 네트워크에 있는 것이 아니므로, GCP 내부 IP를 기준으로 통신합니다.

---

## VM 구성

| VM | 역할 | 주요 포트 |
| --- | --- | --- |
| platform-vm | 서비스 디스커버리, 중앙 설정, API 진입점 | 19090, 19091, 19092 |
| domain-a-vm | 사용자/업체/허브/상품 도메인 서비스 | 19093, 19095, 19096, 19097 |
| domain-b-vm | 주문/재고/배송/알림/AI 도메인 서비스 | 19094, 19098, 19099, 19100, 19101 |
| data-monitor-vm | 데이터 저장소, 메시징, 모니터링 | 5432, 6379, 9092, 8082, 9411, 9090, 3000 |

예정 내부 IP:

| VM | Internal IP |
| --- | --- |
| platform-vm | `10.10.0.10` |
| domain-a-vm | `10.10.0.20` |
| domain-b-vm | `10.10.0.30` |
| data-monitor-vm | `10.10.0.40` |

이 IP는 `.env.gcp`와 `monitoring/prometheus.gcp.yml`에서 사용합니다.

---

## 문서

- [GCP 인프라 구성 문서](docs/gcp-infra.md)

---

## 현재 진행 중인 작업

- GCP VM 4대 기준 서비스 배치 정리
- Terraform 기반 인프라 구성 준비
- VM별 Docker Compose 분리
