# GCP GitHub Actions CI/CD

HubLink를 GCP VM에 자동 배포하기 위한 GitHub Actions 구성

## 배포 흐름

```text
GitHub Actions
-> 서비스별 Docker 이미지 빌드
-> Artifact Registry push
-> .env.gcp 생성
-> VM별 compose/config/db/monitoring 파일 복사
-> docker compose pull
-> docker compose up -d
```

## PR 검증 흐름

`pr-check.yml`은 `develop` 대상 PR에서 변경된 서비스만 Gradle `bootJar`로 검증한다.

```text
Pull Request
-> 변경 파일 감지
-> 변경 서비스 bootJar
-> compose 변경 시 docker compose config 검증
```

PR 검증은 GCP 인증과 VM 배포를 수행하지 않는다. 실제 이미지 push와 VM 배포는 `develop` merge 후 `gcp-cicd.yml`에서 처리한다.

## 주요 파일

```text
.github/workflows/pr-check.yml
.github/workflows/gcp-cicd.yml
.github/workflows/gcp-load-test-sync.yml
scripts/gcp/deploy-vm.sh
scripts/gcp/deploy-load-test.sh
```

`gcp-cicd.yml`은 서비스 이미지 빌드와 VM 배포를 담당한다.

`gcp-load-test-sync.yml`은 `performance/k6` 스크립트를 `load-test-vm`으로 동기화한다.

`deploy-vm.sh`는 GitHub Actions runner에서 실행되는 VM 배포 보조 스크립트다. VM에 필요한 파일을 복사하고 해당 VM에서 Docker Compose를 실행한다.

## 필요한 GitHub Secrets

GitHub 저장소에서 `Settings` -> `Secrets and variables` -> `Actions` -> `Secrets`에 아래 값을 추가한다.

| 이름 | 값 |
| --- | --- |
| `GCP_PROJECT_ID` | GCP 프로젝트 ID |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Terraform output `github_actions_workload_identity_provider` 값 |
| `GCP_SERVICE_ACCOUNT_EMAIL` | Terraform output `github_actions_service_account_email` 값 |
| `GCP_VM_USER` | VM 접속 Linux 사용자명 |
| `DB_USERNAME` | PostgreSQL 사용자명 |
| `DB_PASSWORD` | GitHub Secret 값 |
| `JWT_SECRET` | JWT 서명용 문자열 |
| `SLACK_BOT_TOKEN` | Slack Bot token |
| `AI_API_KEY` | AI API key |
| `KAKAO_REST_API_KEY` | Kakao REST API key |
| `CORS_ALLOWED_ORIGIN` | `http://34.50.50.207:19091` |
| `SWAGGER_GATEWAY_URL` | `http://34.50.50.207:19091` |

## 선택 GitHub Variables

GitHub 저장소에서 `Settings` -> `Secrets and variables` -> `Actions` -> `Variables`에 아래 값을 추가할 수 있다.

| 이름 | 기본값 |
| --- | --- |
| `SLACK_ENABLED` | `false` |
| `AI_ENABLED` | `false` |
| `KAKAO_ENABLED` | `false` |
| `LOG_LEVEL_ROOT` | `WARN` |

`LOG_LEVEL_ROOT`를 등록하지 않으면 workflow에서 `WARN`으로 생성한다.

## GCP 인증 리소스

Terraform은 GitHub Actions용 서비스 계정과 Workload Identity Federation 설정을 생성한다.

```powershell
cd "D:\Spring Projects\hublink\infra\gcp"
terraform plan
terraform apply
```

적용 후 아래 output을 GitHub Secrets에 등록한다.

```powershell
terraform output github_actions_service_account_email
terraform output github_actions_workload_identity_provider
```

```text
GCP_SERVICE_ACCOUNT_EMAIL
GCP_WORKLOAD_IDENTITY_PROVIDER
```

GitHub Actions용 서비스 계정에는 배포 실습을 위해 아래 권한을 부여한다.

```text
Artifact Registry Writer
Compute Admin
IAP-secured Tunnel User
Service Account User
```

## JSON key 미사용

이 프로젝트는 서비스 계정 JSON key를 만들지 않는다.

GCP 조직 정책에서 `constraints/iam.disableServiceAccountKeyCreation`이 적용되면 key 생성이 막힌다. GitHub Actions는 Workload Identity Federation을 통해 짧은 수명의 인증 토큰으로 GCP 서비스 계정을 impersonation한다.

## 실행 방법

`develop` 브랜치에 push하면 `GCP CI/CD` workflow가 자동 실행된다.

수동 실행도 가능하다.

```text
GitHub 저장소
-> Actions
-> GCP CI/CD
-> Run workflow
```

## 배포 순서

```text
data-vm
-> monitoring-vm
-> platform-vm
-> domain-a-vm
-> domain-b-vm
```

DB, Redis, Kafka가 먼저 떠야 하고, 그 다음 모니터링 계층과 Eureka/Config Server가 떠야 도메인 서비스들이 설정을 읽고 Eureka에 등록된다.

## 확인 주소

외부 IP는 아래 명령으로 확인한다.

```powershell
gcloud compute instances list --project hublink-500805
```

```text
Eureka:     http://platform-vm-외부IP:19090
Gateway:    http://platform-vm-외부IP:19091
Grafana:    http://monitoring-vm-외부IP:3000
Prometheus: http://monitoring-vm-외부IP:9090
Kafka UI:   http://monitoring-vm-외부IP:8082
Zipkin:     http://monitoring-vm-외부IP:9411
```
