# GCP 인프라 구성 문서

이 문서는 HubLink 프로젝트를 GCP Compute Engine VM 5대로 실행하기 위한 인프라 구성을 정리한다.

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
  -> VM start/stop schedule
```

Terraform 코드 위치:

```text
infra/gcp
```

## VM 구성

| VM | 내부 IP | 외부 IP | 역할 |
| --- | --- | --- | --- |
| hublink-platform-vm | `10.10.0.10` | `34.50.23.39` | Eureka, Config Server, API Gateway |
| hublink-domain-a-vm | `10.10.0.20` | 없음 | user, company, hub, product |
| hublink-domain-b-vm | `10.10.0.30` | 없음 | order, stock, delivery, slack, ai |
| hublink-data-monitor-vm | `10.10.0.40` | `34.64.89.47` | PostgreSQL, Redis, Kafka, Kafka UI, Zipkin, Prometheus, Grafana |
| hublink-load-test-vm | `10.10.0.50` | `34.22.78.126` | k6 부하 발생 |

외부 IP는 브라우저 접속이 필요한 `platform`, `data-monitor`와 부하 테스트 VM 접속용 `load-test`에만 고정 IP로 연결한다. `domain-a`, `domain-b`는 외부 IP 없이 내부 통신과 IAP SSH를 사용한다.

`load-test`는 외부 IP로 접속하더라도 실제 부하는 `platform` 내부 IP인 `10.10.0.10:19091`로 전송한다.

현재 IP 확인:

```bash
cd infra/gcp
terraform output vm_internal_ips
terraform output vm_external_ips
```

현재 고정 외부 IP:

```text
platform:     34.50.23.39
data-monitor: 34.64.89.47
load-test:    34.22.78.126
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
Zipkin:        http://10.10.0.40:9411/api/v2/spans
Load Test:     10.10.0.50
```

## 공개 접속 주소

| 항목 | URL |
| --- | --- |
| Swagger/API Gateway | `http://34.50.23.39:19091/swagger-ui/index.html` |
| Eureka Dashboard | `http://34.50.23.39:19090` |
| Kafka UI | `http://34.64.89.47:8082` |
| Grafana | `http://34.64.89.47:3000` |
| Prometheus | `http://34.64.89.47:9090` |
| Zipkin | `http://34.64.89.47:9411` |

Swagger에서 직접 요청을 보내려면 배포 환경의 `SWAGGER_GATEWAY_URL`, `CORS_ALLOWED_ORIGIN`이 `platform` 외부 IP를 바라봐야 한다.

```text
SWAGGER_GATEWAY_URL=http://34.50.23.39:19091
CORS_ALLOWED_ORIGIN=http://34.50.23.39:19091
```

## Docker Compose 분리

VM 역할별로 Compose 파일을 분리한다.

| 파일 | 실행 VM | 포함 항목 |
| --- | --- | --- |
| `docker-compose.data-monitor.yml` | data-monitor | PostgreSQL, Redis, Kafka, Kafka UI, Zipkin, Prometheus, Grafana |
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
| `schedule.tf` | VM 자동 시작/종료 스케줄 |
| `variables.tf` | 입력 변수 선언 |
| `outputs.tf` | 배포 후 확인할 주요 값 |
| `terraform.tfvars` | 실제 프로젝트별 입력값 |

`terraform.tfvars`는 개인 환경 파일이므로 git에 올리지 않는다.

## VM 자동 시작과 종료

비용 절감을 위해 VM 스케줄을 적용한다.

| 항목 | 값 |
| --- | --- |
| 시작 | 매일 10:00 |
| 종료 | 매일 02:00 |
| 타임존 | `Asia/Seoul` |

Terraform 변수:

```hcl
vm_start_schedule     = "0 10 * * *"
vm_stop_schedule      = "0 2 * * *"
vm_schedule_time_zone = "Asia/Seoul"
```

수정 후 반영:

```bash
cd infra/gcp
terraform plan
terraform apply
```

## 운영 확인

VM 상태 확인:

```bash
gcloud compute instances list --project hublink-498115
```

IAP SSH 접속:

```bash
gcloud compute ssh hublink-domain-a-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap
```

컨테이너 상태 확인:

```bash
gcloud compute ssh hublink-platform-vm \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap \
  --command "cd /opt/hublink && sudo docker compose -f docker-compose.platform.yml ps"
```

서비스 등록 확인:

```text
http://34.50.23.39:19090
```

메시지 흐름 확인:

```text
http://34.64.89.47:8082
```

부하 테스트 VM 접속:

```bash
gcloud compute ssh hublink-load-test-vm \
  --zone asia-northeast3-a \
  --project hublink-498115
```

k6 smoke 테스트:

```bash
hublink-k6-smoke http://10.10.0.10:19091/actuator/health
```

모니터링 확인:

```text
http://34.64.89.47:3000
http://34.64.89.47:9090
```

## CI/CD

GitHub Actions 기반 배포 흐름은 별도 문서에서 정리한다.

[gcp-cicd.md](gcp-cicd.md)
