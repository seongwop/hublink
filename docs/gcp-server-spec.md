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
| Private VM Egress | Cloud NAT |
| VM User | `hublink` |
| Provisioning | Terraform |
| Runtime | Docker Compose |

## VM 스펙 요약

| VM | Machine Type | vCPU | Memory | Boot Disk | Internal IP | External IP | 역할 |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| `hublink-platform-vm` | `e2-highmem-2` | 2 | 16 GB | 30 GB | `10.10.0.10` | `34.50.55.18` | Eureka, Config Server, API Gateway, 모니터링 |
| `hublink-domain-a-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.20` | 없음 | user, company, hub, product |
| `hublink-domain-b-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.30` | 없음 | order, stock, slack, ai |
| `hublink-delivery-vm` | `e2-standard-2` | 2 | 8 GB | 30 GB | `10.10.0.70` | 없음 | delivery |
| `hublink-data-vm` | `e2-standard-4` | 4 | 16 GB | 50 GB | `10.10.0.40` | 없음 | PostgreSQL, Redis, Kafka |

Compute Engine 합계는 12 vCPU다. 메모리와 vCPU는 GCP E2 machine type 기준이다.

| 서버리스 실행 환경 | CPU | Memory | 연결 | 역할 |
| --- | ---: | ---: | --- | --- |
| `hublink-k6-load-test` Cloud Run Job | 1 | 4 GiB | Direct VPC | k6 부하 발생 |

## VM별 서비스 구성

### platform

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| Eureka Server | 19090 | 서비스 디스커버리 |
| API Gateway | 19091 | 외부 API 진입점 |
| Config Server | 19092 | 서비스 설정 제공 |
| Kafka UI | 8082 | 필요할 때만 실행해 Kafka topic, lag 확인 |
| Zipkin | 9411 | 분산 추적 |
| Prometheus | 9090 | 메트릭 수집 |
| Loki | 3100 | 로그 저장 |
| Grafana | 3000 | 메트릭, 로그 대시보드 |

### domain-a

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| user-service | 19093 | 사용자 도메인 |
| company-service | 19096 | 업체 도메인 |
| hub-service | 19095 | 허브 도메인 |
| product-service | 19097 | 상품 도메인 |

### domain-b

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| order-service | 19094 | 주문 도메인 |
| stock-service | 19098 | 재고 도메인 |
| slack-service | 19100 | Slack 알림 |
| ai-service | 19101 | AI 마감 생성 |

### delivery

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| delivery-service | 19099 | 배송 도메인 |

### data

| 서비스 | 포트 | 용도 |
| --- | ---: | --- |
| PostgreSQL | 5432 | 서비스 DB |
| Redis | 6379 | Redis Stream, cache, lock |
| Kafka | 9092 | 도메인 이벤트 |

### load-test

| 구성 | 용도 |
| --- | --- |
| Cloud Run Job | Compute Engine vCPU 쿼터와 분리된 부하 테스트 실행 |
| Direct VPC | 내부 배송 서비스와 DB 연결 |
| Secret Manager | DB 초기화 비밀번호 주입 |

## 스펙 배치 기준

| VM | 기준 |
| --- | --- |
| platform | 플랫폼과 모니터링의 기존 합산 메모리 16 GB를 유지하는 `e2-highmem-2` 사용 |
| domain-a | 4개 도메인 서비스를 함께 실행하므로 `e2-standard-2` 사용 |
| domain-b | 주문, 재고, AI, Slack 서비스를 함께 실행하므로 `e2-standard-2` 사용 |
| delivery | 배송 부하가 다른 도메인 서비스 CPU에 영향을 주지 않도록 전용 `e2-standard-2` 사용 |
| data | 부하 테스트 대상 데이터 경로인 DB, Redis, Kafka만 실행하도록 유지 |
| load-test | Compute Engine 12 vCPU를 서비스에 유지하기 위해 Cloud Run Job으로 분리 |

## 스케줄 상태

VM 자동 시작/종료 스케줄 정책은 Terraform 리소스로 유지한다.

현재 설정은 매일 09:30 시작, 매일 03:00 종료를 사용.

```hcl
vm_schedule_enabled   = true
vm_start_schedule     = "30 9 * * *"
vm_stop_schedule      = "0 3 * * *"
vm_schedule_time_zone = "Asia/Seoul"
```

수동 기동이 필요하면 `scripts/gcp/start-gcp-vms.ps1`로 처리.

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
  --project hublink-503802 \
  --zones asia-northeast3-a
```
