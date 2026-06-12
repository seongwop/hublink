# GCP 서버 스펙 문서

이 문서는 HubLink GCP 배포 환경에서 사용하는 Compute Engine VM 스펙과 VM별 역할을 정리한다.

스펙 기준은 `infra/gcp/variables.tf`의 `vm_specs`와 현재 `terraform.tfvars` 설정이다.

## 공통 구성

| 항목 | 값 |
| --- | --- |
| Cloud Provider | Google Cloud Platform |
| Region | `asia-northeast3` |
| Zone | `asia-northeast3-a` |
| OS Image | Debian 12 |
| Boot Image Project | `debian-cloud` |
| Disk Type | `pd-balanced` |
| Network CIDR | `10.10.0.0/24` |
| VM User | `hublink` |
| Provisioning | Terraform |
| Runtime | Docker Compose |

## VM 스펙 요약

| VM | Machine Type | vCPU | Memory | Boot Disk | Internal IP | External IP | 역할 |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| `hublink-platform-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.10` | `34.50.23.39` | Eureka, Config Server, API Gateway |
| `hublink-domain-a-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.20` | `8.230.24.217` | user, company, hub, product |
| `hublink-domain-b-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.30` | `8.230.9.99` | order, stock, delivery, slack, ai |
| `hublink-data-monitor-vm` | `e2-standard-2` | 2 | 8 GB | 50 GB | `10.10.0.40` | `34.64.89.47` | PostgreSQL, Redis, Kafka |
| `hublink-monitor-vm` | `e2-standard-2` | 2 | 8 GB | 50 GB | `10.10.0.60` | `34.50.1.195` | Kafka UI, Zipkin, Prometheus, Loki, Grafana |
| `hublink-load-test-vm` | `e2-medium` | 2 | 4 GB | 20 GB | `10.10.0.50` | `34.22.78.126` | k6 부하 발생 |

메모리와 vCPU는 GCP E2 machine type 기준이다.

## VM별 서비스 구성

### platform

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| Eureka Server | 19090 | 서비스 디스커버리 |
| API Gateway | 19091 | 외부 API 진입점 |
| Config Server | 19092 | 서비스 설정 제공 |

### domain-a

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| user-service | 19093 | 사용자 도메인 |
| company-service | 19095 | 업체 도메인 |
| hub-service | 19096 | 허브 도메인 |
| product-service | 19097 | 상품 도메인 |

### domain-b

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| order-service | 19094 | 주문 도메인 |
| stock-service | 19098 | 재고 도메인 |
| delivery-service | 19099 | 배송 도메인 |
| slack-service | 19100 | Slack 알림 |
| ai-service | 19101 | AI 마감 생성 |

### data-monitor

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| PostgreSQL | 5432 | 서비스 DB |
| Redis | 6379 | Redis Stream, cache, lock |
| Kafka | 9092 | 도메인 이벤트 |

### monitor

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| Kafka UI | 8082 | Kafka topic, lag 확인 |
| Zipkin | 9411 | 분산 추적 |
| Prometheus | 9090 | 메트릭 수집 |
| Loki | 3100 | 로그 저장 |
| Grafana | 3000 | 메트릭, 로그 대시보드 |

### load-test

| 구성 | 용도 |
| --- | --- |
| k6 | 부하 테스트 실행 |
| `performance/k6` | 부하 테스트 스크립트 |

## 스펙 배치 기준

| VM | 기준 |
| --- | --- |
| platform | 모든 요청의 진입점과 서비스 디스커버리를 담당하므로 기본 서비스 VM과 같은 `e2-standard-2` 사용 |
| domain-a | 4개 도메인 서비스를 함께 실행하므로 `e2-standard-2` 사용 |
| domain-b | 배송, 주문, 재고, AI, Slack 등 부하 테스트 대상 서비스가 함께 실행되므로 `e2-standard-2` 사용 |
| data-monitor | 부하 테스트 대상 데이터 경로인 DB, Redis, Kafka만 실행하도록 유지 |
| monitor | Prometheus, Grafana, Loki, Zipkin, Kafka UI를 분리해 data VM 자원 경합 완화 |
| load-test | k6 부하 발생 전용 VM이므로 `e2-medium`과 20 GB 디스크 사용 |

## 스케줄 상태

VM 자동 시작/종료 스케줄 정책은 Terraform 리소스로 유지한다.

현재 기본 설정은 부하 테스트 안정성을 위해 비활성화 상태다.

```hcl
vm_schedule_enabled = false
```

스케줄을 다시 사용할 때는 `terraform.tfvars`에서 값을 `true`로 변경한 뒤 `terraform apply`를 실행한다.

## 확인 명령

Terraform output으로 현재 IP를 확인한다.

```bash
cd infra/gcp
terraform output vm_internal_ips
terraform output vm_external_ips
```

GCP에서 VM machine type과 상태를 확인한다.

```bash
gcloud compute instances list \
  --project hublink-498115 \
  --zones asia-northeast3-a
```
