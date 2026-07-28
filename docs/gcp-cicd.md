# GCP GitHub Actions CI/CD

HubLink를 GCP VM에 자동 배포하기 위한 GitHub Actions 구성

## 배포 흐름

```text
GitHub Actions
-> 서비스별 Docker 이미지 빌드
-> Artifact Registry push
-> 외부 공통 이미지 mirror
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
performance/k6/Dockerfile.cloud-run
performance/k6/run-cloud-run-k6.sh
```

`gcp-cicd.yml`은 서비스 이미지 빌드와 VM 배포를 담당한다.

워크플로우 또는 `scripts/gcp/**` 변경 시 새 Artifact Registry 초기 배포를 위해 전체 서비스 이미지를 다시 빌드한다. 앱 VM 배포가 잡히면 promtail 이미지를 Artifact Registry에 mirror한다.

`gcp-load-test-sync.yml`은 k6 이미지를 빌드하고 Direct VPC가 연결된 Cloud Run Job을 배포한다. 수동 실행에서 `execute_test`를 선택한 경우에만 부하를 발생시킨다.

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
| `CORS_ALLOWED_ORIGIN` | `http://34.50.55.18:19091` |
| `SWAGGER_GATEWAY_URL` | `http://34.50.55.18:19091` |

## 선택 GitHub Variables

GitHub 저장소에서 `Settings` -> `Secrets and variables` -> `Actions` -> `Variables`에 아래 값을 추가할 수 있다.

| 이름 | 기본값 |
| --- | --- |
| `SLACK_ENABLED` | `false` |
| `AI_ENABLED` | `false` |
| `KAKAO_ENABLED` | `false` |
| `LOG_LEVEL_ROOT` | `WARN` |
| `DELIVERY_ASSIGNMENT_MAX_ACTIVE_PER_MANAGER` | `60` |
| `DELIVERY_OUTBOX_FIXED_DELAY_MS` | `100` |

`LOG_LEVEL_ROOT`를 등록하지 않으면 workflow에서 `WARN`으로 생성한다. `DELIVERY_ASSIGNMENT_MAX_ACTIVE_PER_MANAGER`는 GCP 성능 테스트에서 담당자 수를 바꾸지 않고 용량 소진을 방지하기 위한 값이며, 등록하지 않으면 `60`으로 생성한다. 애플리케이션 기본값 `30`은 유지한다. `DELIVERY_OUTBOX_FIXED_DELAY_MS`는 Outbox 성능 실험용 polling 간격이며 등록하지 않으면 `100ms`로 생성한다.

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
Cloud Run Admin
Secret Manager Secret Version Adder
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
-> platform-vm + monitoring
-> domain-a-vm
-> domain-b-vm
-> delivery-vm
```

DB, Redis, Kafka가 먼저 떠야 하고, 그 다음 플랫폼과 모니터링 통합 Compose가 떠야 도메인 서비스와 배송 전용 서비스가 설정을 읽고 Eureka에 등록된다.

배포와 VM 재기동에서는 다음 조건을 모두 확인한다.

- Config Server actuator health와 서비스별 설정 endpoint 응답
- 대상 서비스 actuator health
- 현재 컨테이너 hostname과 일치하는 Eureka `UP` 인스턴스
- 배송 서비스 배포 전 company, hub, user 서비스 등록

config 배포도 대상 이미지를 먼저 pull한 뒤 컨테이너를 교체한다. Compose의 `latest` 태그가 VM 로컬 캐시에 남아 이전 이미지를 재사용하는 상황을 방지한다.

VM 재기동에서는 Promtail을 먼저 기동하고 선행 서비스 준비 상태를 확인한다. 준비 상태 확인 전에 전체 Compose를 중지하지 않아 timeout이 로그 수집 중단으로 이어지지 않게 한다.

배포 archive의 `hublink-compose-up.sh`는 Docker 설치 확인을 생략하는 배포에서도 `/usr/local/bin/hublink-compose-up`에 갱신된다.

## 확인 주소

외부 IP는 아래 명령으로 확인한다.

```powershell
gcloud compute instances list --project hublink-503802
```

```text
Gateway:    http://platform-vm-외부IP:19091
Grafana:    IAP 터널의 http://localhost:3000
Prometheus: IAP 터널의 http://localhost:9090
Kafka UI:   필요할 때만 실행 후 IAP 터널의 http://localhost:8082
Zipkin:     IAP 터널의 http://localhost:9411
```

Eureka와 모니터링 도구는 외부에 공개하지 않는다. SSH는 `35.235.240.0/20`에서 들어오는 IAP 연결만 허용한다.
