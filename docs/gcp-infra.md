# GCP 인프라 구성 문서

이 문서는 HubLink 프로젝트를 GCP Compute Engine VM 6대로 실행하기 위한 인프라 구성을 정리한다.

## 구성 요약

인프라는 Terraform으로 생성한다.

```text
Terraform
  -> VPC
  -> Subnet
  -> Firewall
  -> Compute Engine VM
  -> Artifact Registry
  -> Service Account
  -> Workload Identity Federation
  -> VM stop schedule policy
```

Terraform 코드 위치:

```text
infra/gcp
```

## VM 구성

| VM | 내부 IP | 외부 IP | 역할 |
| --- | --- | --- | --- |
| hublink-platform-vm | `10.10.0.10` | `34.50.50.207` | Eureka, Config Server, API Gateway |
| hublink-domain-a-vm | `10.10.0.20` | 없음 | user, company, hub, product |
| hublink-domain-b-vm | `10.10.0.30` | 없음 | order, stock, delivery, slack, ai |
| hublink-data-vm | `10.10.0.40` | 없음 | PostgreSQL, Redis, Kafka |
| hublink-monitoring-vm | `10.10.0.60` | `8.230.17.44` | Kafka UI, Zipkin, Prometheus, Loki, Grafana |
| hublink-load-test-vm | `10.10.0.50` | `34.64.86.22` | k6 부하 발생 |

외부 IP는 `platform`, `monitoring`, `load-test`에만 고정 IP로 연결한다. 서비스 간 통신과 부하 테스트 트래픽은 외부 IP가 아니라 내부 IP를 기준으로 유지한다.

`load-test`는 외부 IP로 접속하더라도 실제 부하는 `platform` 내부 IP인 `10.10.0.10:19091`로 전송한다.

현재 IP 확인:

```bash
cd infra/gcp
terraform output vm_internal_ips
terraform output vm_external_ips
```

현재 고정 외부 IP:

```text
platform:    34.50.50.207
monitoring:  8.230.17.44
load-test:   34.64.86.22
```

## 네트워크

| 항목 | 값 |
| --- | --- |
| VPC | `hublink-vpc` |
| Subnet | `hublink-subnet` |
| CIDR | `10.10.0.0/24` |
| Region | `asia-northeast3` |
| Zone | `asia-northeast3-a` |

서비스 간 통신은 외부 IP가 아니라 내부 IP를 사용한다.

```text
Eureka:        http://10.10.0.10:19090/eureka/
Config Server: http://10.10.0.10:19092
PostgreSQL:    10.10.0.40:5432
Redis:         10.10.0.40:6379
Kafka:         10.10.0.40:9092
Zipkin:        http://10.10.0.60:9411/api/v2/spans
Load Test:     10.10.0.50
```

## 공개 접속 주소

| 항목 | URL |
| --- | --- |
| Swagger/API Gateway | `http://34.50.50.207:19091/swagger-ui/index.html` |
| Eureka Dashboard | `http://34.50.50.207:19090` |
| Kafka UI | `http://8.230.17.44:8082` |
| Grafana | `http://8.230.17.44:3000` |
| Prometheus | `http://8.230.17.44:9090` |
| Zipkin | `http://8.230.17.44:9411` |

Swagger에서 직접 요청을 보내려면 배포 환경의 `SWAGGER_GATEWAY_URL`, `CORS_ALLOWED_ORIGIN`이 `platform` 외부 IP를 바라봐야 한다.

```text
SWAGGER_GATEWAY_URL=http://34.50.50.207:19091
CORS_ALLOWED_ORIGIN=http://34.50.50.207:19091
```

## Docker Compose 분리

VM 역할별로 Compose 파일을 분리한다.

| 파일 | 실행 VM | 포함 항목 |
| --- | --- | --- |
| `docker-compose.data.yml` | data | PostgreSQL, Redis, Kafka |
| `docker-compose.monitoring.yml` | monitoring | Kafka UI, Zipkin, Prometheus, Loki, Grafana |
| `docker-compose.platform.yml` | platform | Eureka, Config Server, API Gateway |
| `docker-compose.domain-a.yml` | domain-a | user, company, hub, product |
| `docker-compose.domain-b.yml` | domain-b | order, stock, delivery, slack, ai |

공통 환경값은 `.env.gcp`로 주입한다. 실제 배포 시에는 GitHub Actions가 Secrets/Variables를 기준으로 `.env.gcp`를 생성한다.

## Terraform 주요 파일

| 파일 | 역할 |
| --- | --- |
| `main.tf` | Provider와 Terraform 기본 설정 |
| `apis.tf` | 필요한 GCP API 활성화 |
| `network.tf` | VPC, Subnet, Firewall 설정 |
| `compute.tf` | VM, 고정 내부 IP, 고정 외부 IP, startup script 연결 |
| `iam.tf` | VM 실행용 Service Account와 권한 |
| `artifact-registry.tf` | Docker 이미지 저장소 |
| `workload-identity.tf` | GitHub Actions OIDC 인증 |
| `schedule.tf` | VM 자동 종료 스케줄 정책 |
| `variables.tf` | 입력 변수 선언 |
| `outputs.tf` | 배포 후 확인할 주요 값 |
| `terraform.tfvars` | 실제 프로젝트별 입력값 |

`terraform.tfvars`는 개인 환경 파일이므로 git에 올리지 않는다.

## VM 자동 종료

VM 스케줄 정책 리소스는 유지하되, 실제 VM 연결 여부는 `vm_schedule_enabled`로 제어한다.

자동 시작은 서비스 기동 순서 문제를 줄이기 위해 미사용. 테스트 시 VM은 수동 시작 후 GitHub Actions 배포로 상태 정리.

| 항목 | 값 |
| --- | --- |
| 사용 여부 | 기본 `true` |
| 시작 | 수동 |
| 종료 | 매일 03:00 |
| 타임존 | `Asia/Seoul` |

Terraform 변수:

```hcl
vm_schedule_enabled   = true
vm_stop_schedule      = "0 3 * * *"
vm_schedule_time_zone = "Asia/Seoul"
```

수동 시작 스크립트:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/gcp/start-gcp-vms.ps1 -Deploy
```

수정 후 반영:

```bash
cd infra/gcp
terraform plan
terraform apply
```

`vm_schedule_enabled = false`이면 `google_compute_resource_policy.vm_schedule` 리소스는 남아 있지만 VM의 `resource_policies`에는 연결되지 않는다.

## 운영 확인

VM 상태 확인:

```bash
gcloud compute instances list --project hublink-500805
```

IAP SSH 접속:

```bash
gcloud compute ssh hublink-domain-a-vm \
  --zone asia-northeast3-a \
  --project hublink-500805 \
  --tunnel-through-iap
```

컨테이너 상태 확인:

```bash
gcloud compute ssh hublink-platform-vm \
  --zone asia-northeast3-a \
  --project hublink-500805 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.platform.yml ps"
```

서비스 등록 확인:

```text
http://34.50.50.207:19090
```

메시지 흐름 확인:

```text
http://8.230.17.44:8082
```

부하 테스트 VM 접속:

```bash
gcloud compute ssh hublink-load-test-vm \
  --zone asia-northeast3-a \
  --project hublink-500805
```

k6 smoke 테스트:

```bash
hublink-k6-smoke http://10.10.0.10:19091/actuator/health
```

모니터링 확인:

```text
http://8.230.17.44:3000
http://8.230.17.44:9090
```

## CI/CD

GitHub Actions 기반 배포 흐름은 별도 문서에서 정리한다.

[gcp-cicd.md](gcp-cicd.md)
