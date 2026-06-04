# GCP GitHub Actions CI/CD

이 문서는 HubLink를 GCP VM 4대에 자동 배포하기 위한 GitHub Actions 설정을 정리합니다.

## 배포 흐름

```text
GitHub Actions
→ 서비스별 Docker 이미지 빌드
→ Artifact Registry에 push
→ .env.gcp 생성
→ VM별 compose/config/db/monitoring 파일 복사
→ docker compose pull
→ docker compose up -d
```

## 추가된 파일

```text
.github/workflows/gcp-cicd.yml
scripts/gcp/deploy-vm.sh
```

`gcp-cicd.yml`은 전체 CI/CD workflow입니다.

`deploy-vm.sh`는 GitHub Actions runner에서 실행되는 보조 스크립트입니다. VM에 파일을 복사하고 해당 VM에서 Docker Compose를 실행합니다.

## 기존 AWS workflow 비활성화

기존 AWS/ECR 기반 workflow는 실행되지 않도록 legacy 폴더로 이동했습니다.

```text
.github/workflows-legacy/aws
```

GitHub Actions는 `.github/workflows` 바로 아래 workflow만 실행합니다. 현재 실제 실행 대상은 `gcp-cicd.yml`입니다.

## 필요한 GitHub Secrets

GitHub 저장소에서 `Settings` → `Secrets and variables` → `Actions` → `Secrets`에 아래 값을 추가합니다.

| 이름 | 값 |
| --- | --- |
| `GCP_PROJECT_ID` | GCP 프로젝트 ID. 예: `hublink-498115` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Terraform output `github_actions_workload_identity_provider` 값 |
| `GCP_SERVICE_ACCOUNT_EMAIL` | Terraform output `github_actions_service_account_email` 값 |
| `GCP_VM_USER` | VM 접속용 Linux 사용자명. 예: `github-actions` |
| `DB_USERNAME` | PostgreSQL 사용자명 |
| `DB_PASSWORD` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | JWT 서명용 긴 문자열 |
| `SLACK_BOT_TOKEN` | Slack 사용 시 토큰. 사용하지 않으면 비워둘 수 있음 |
| `AI_API_KEY` | AI API 사용 시 키. 사용하지 않으면 비워둘 수 있음 |
| `KAKAO_REST_API_KEY` | Kakao API 사용 시 키. 사용하지 않으면 비워둘 수 있음 |
| `CORS_ALLOWED_ORIGIN` | 허용할 프론트엔드 origin. 없으면 `http://localhost:3000` |
| `SWAGGER_GATEWAY_URL` | Swagger 요청 서버 URL. 예: `http://34.22.78.126:19091` |

## 선택 GitHub Variables

`Settings` → `Secrets and variables` → `Actions` → `Variables`에 아래 값을 추가할 수 있습니다.

| 이름 | 기본값 |
| --- | --- |
| `SLACK_ENABLED` | `false` |
| `AI_ENABLED` | `false` |
| `KAKAO_ENABLED` | `false` |
| `LOG_LEVEL_ROOT` | `WARN` |

## GCP 인증 리소스 생성

Terraform에 GitHub Actions 전용 서비스 계정과 Workload Identity Federation 설정을 추가했습니다.

먼저 인프라 코드를 다시 적용합니다.

```powershell
cd "D:\Spring Projects\hublink\infra\gcp"
terraform plan
terraform apply
```

적용 후 GitHub Actions 인증에 필요한 output을 확인합니다.

```powershell
terraform output github_actions_service_account_email
terraform output github_actions_workload_identity_provider
```

출력값을 GitHub Secrets에 등록합니다.

```text
GCP_SERVICE_ACCOUNT_EMAIL
GCP_WORKLOAD_IDENTITY_PROVIDER
```

GitHub Actions 전용 서비스 계정에는 아래 권한을 부여합니다.

```text
Artifact Registry Writer
Compute Admin
IAP-secured Tunnel User
Service Account User
```

이 권한은 Terraform에서 자동으로 부여합니다. 실습용으로 넉넉하게 잡은 구성이라, 배포가 안정화되면 더 좁은 권한으로 줄이는 것이 좋습니다.

## JSON 키 미사용

이 프로젝트는 서비스 계정 JSON 키를 만들지 않습니다.

GCP 조직 정책에서 `constraints/iam.disableServiceAccountKeyCreation`이 적용된 경우 서비스 계정 키 생성이 막힙니다. GitHub Actions는 Workload Identity Federation을 통해 짧은 수명의 인증 토큰으로 GCP 서비스 계정을 impersonation합니다.

## 실행 방법

workflow는 `develop-infra` 브랜치 push 시 자동 실행됩니다.

수동 실행도 가능합니다.

```text
GitHub 저장소
→ Actions
→ GCP CI/CD
→ Run workflow
```

## 배포 순서

workflow는 아래 순서로 배포합니다.

```text
data-monitor-vm
→ platform-vm
→ domain-a-vm
→ domain-b-vm
```

DB, Redis, Kafka가 먼저 떠야 하고, 그다음 Eureka와 Config Server가 떠야 도메인 서비스들이 정상적으로 설정을 읽고 Eureka에 등록됩니다.

## 확인 주소

외부 IP는 아래 명령으로 확인합니다.

```powershell
gcloud compute instances list --project hublink-498115
```

```text
Eureka:     http://platform-vm-외부IP:19090
Gateway:    http://platform-vm-외부IP:19091
Grafana:    http://data-monitor-vm-외부IP:3000
Prometheus: http://data-monitor-vm-외부IP:9090
Kafka UI:   http://data-monitor-vm-외부IP:8082
Zipkin:     http://data-monitor-vm-외부IP:9411
```
