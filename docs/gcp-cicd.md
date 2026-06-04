# GCP GitHub Actions CI/CD

이 문서는 HubLink를 GCP VM 4대에 배포하기 위한 GitHub Actions CI/CD 구성을 정리한다.

## 관련 문서

| 문서 | 내용 |
| --- | --- |
| [gcp-infra.md](gcp-infra.md) | GCP VM, 네트워크, Terraform 구성 |
| [scenario-test-plan.md](scenario-test-plan.md) | 배포 후 배송 도메인 시나리오 검증 |
| [performance-test-plan.md](performance-test-plan.md) | 배포 후 배송 중심 성능 테스트와 병목 관찰 |

## 구성 파일

| 파일 | 역할 |
| --- | --- |
| `.github/workflows/gcp-cicd.yml` | 전체 CI/CD workflow |
| `scripts/gcp/deploy-vm.sh` | VM 파일 복사와 Docker Compose 실행 |
| `Dockerfile` | 서비스별 Docker 이미지 빌드 |
| `docker-compose.*.yml` | VM 역할별 서비스 실행 |

기존 AWS/ECR 기반 workflow는 실행되지 않도록 legacy 폴더로 분리한다.

```text
.github/workflows-legacy/aws
```

GitHub Actions는 `.github/workflows` 바로 아래 workflow만 실행한다.

## 배포 흐름

```text
GitHub Actions
  -> 변경 파일 분석
  -> 변경 서비스 산정
  -> 서비스별 Docker 이미지 빌드
  -> Artifact Registry push
  -> .env.gcp 생성
  -> VM별 compose/config/db/monitoring 파일 복사
  -> docker compose pull
  -> docker compose up -d
```

배포 순서:

```text
data-monitor-vm
-> platform-vm
-> domain-a-vm
-> domain-b-vm
```

DB, Redis, Kafka가 먼저 실행되고, 이후 Eureka와 Config Server가 실행되어야 도메인 서비스가 정상적으로 설정을 읽고 Eureka에 등록된다.

## 실행 조건

자동 실행:

```text
develop-infra 브랜치 push
```

수동 실행:

```text
GitHub 저장소
-> Actions
-> GCP CI/CD
-> Run workflow
```

수동 실행(`workflow_dispatch`)은 전체 배포로 동작한다.

## 변경 감지 배포

workflow는 변경된 파일을 기준으로 필요한 서비스와 VM만 배포한다.

| 변경 파일 | 동작 |
| --- | --- |
| `delivery-service/**` | delivery-service 이미지 빌드 후 domain-b VM 재배포 |
| `user-service/**` | user-service 이미지 빌드 후 domain-a VM 재배포 |
| `config-repo/user-service.yml` | config-repo 복사 후 user-service 이미지 빌드와 domain-a VM 재배포 |
| `docker-compose.domain-a.yml` | domain-a 서비스 이미지 빌드 후 domain-a VM 재배포 |
| `docker-compose.domain-b.yml` | domain-b 서비스 이미지 빌드 후 domain-b VM 재배포 |
| `docker-compose.platform.yml` | platform 서비스 이미지 빌드 후 platform VM 재배포 |
| `docker-compose.data-monitor.yml` | data-monitor VM 재배포 |
| `db/**` | data-monitor VM 재배포 |
| `monitoring/**` | data-monitor VM 재배포 |
| `Dockerfile`, `build.gradle`, `settings.gradle`, `gradle/**`, `core-common/**` | 전체 서비스 영향 가능성으로 전체 이미지 빌드와 관련 VM 배포 |

## 빌드와 배포 단위

서비스 이미지는 matrix job으로 병렬 빌드한다.

```text
Build and push images (<service-name>)
```

이미지 태그:

```text
<region>-docker.pkg.dev/<project-id>/hublink/hublink-<service>:<github-sha>
<region>-docker.pkg.dev/<project-id>/hublink/hublink-<service>:latest
```

VM 배포는 `deploy-vm.sh`가 담당한다.

```text
1. 원격 디렉터리 생성
2. .env.gcp와 compose 파일 복사
3. 추가 디렉터리 복사
4. 지정 서비스 pull
5. 지정 서비스 up -d --no-deps
6. compose ps 확인
```

VM 접속은 IAP 터널을 사용한다.

```bash
gcloud compute ssh <vm-name> \
  --zone asia-northeast3-a \
  --project hublink-498115 \
  --tunnel-through-iap
```

## 필요한 GitHub Secrets

GitHub 저장소의 `Settings` -> `Secrets and variables` -> `Actions` -> `Secrets`에 아래 값을 추가한다.

| 이름 | 값 |
| --- | --- |
| `GCP_PROJECT_ID` | GCP 프로젝트 ID |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Terraform output `github_actions_workload_identity_provider` 값 |
| `GCP_SERVICE_ACCOUNT_EMAIL` | Terraform output `github_actions_service_account_email` 값 |
| `GCP_VM_USER` | VM 접속용 Linux 사용자명 |
| `DB_USERNAME` | PostgreSQL 사용자명 |
| `DB_PASSWORD` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | JWT 서명용 문자열 |
| `SLACK_BOT_TOKEN` | Slack 사용 시 토큰 |
| `AI_API_KEY` | AI API 사용 시 키 |
| `KAKAO_REST_API_KEY` | Kakao API 사용 시 키 |
| `CORS_ALLOWED_ORIGIN` | Swagger 또는 프론트엔드 요청 origin |
| `SWAGGER_GATEWAY_URL` | Swagger 요청 서버 URL |

`SWAGGER_GATEWAY_URL`, `CORS_ALLOWED_ORIGIN`이 비어 있으면 workflow가 `platform` VM 외부 IP를 조회해 기본값을 만든다.

## 선택 GitHub Variables

GitHub 저장소의 `Settings` -> `Secrets and variables` -> `Actions` -> `Variables`에 아래 값을 추가할 수 있다.

| 이름 | 기본값 |
| --- | --- |
| `SLACK_ENABLED` | `false` |
| `AI_ENABLED` | `false` |
| `KAKAO_ENABLED` | `false` |
| `LOG_LEVEL_ROOT` | `WARN` |

## GCP 인증 방식

서비스 계정 JSON 키는 사용하지 않는다.

GitHub Actions는 Workload Identity Federation을 통해 짧은 수명의 인증 토큰으로 GCP 서비스 계정을 impersonation한다.

Terraform output 확인:

```bash
cd infra/gcp
terraform output github_actions_service_account_email
terraform output github_actions_workload_identity_provider
```

GitHub Actions 전용 서비스 계정 권한:

```text
Artifact Registry Writer
Compute Admin
IAP-secured Tunnel User
Service Account User
```

현재 권한은 실습과 배포 안정성을 우선한 구성이다. 배포가 안정화되면 필요한 권한만 남기도록 줄인다.

## 배포 확인

VM 목록:

```bash
gcloud compute instances list --project hublink-498115
```

주요 접속 주소:

```text
Eureka:     http://34.50.23.39:19090
Gateway:    http://34.50.23.39:19091
Grafana:    http://34.64.89.47:3000
Prometheus: http://34.64.89.47:9090
Kafka UI:   http://34.64.89.47:8082
Zipkin:     http://34.64.89.47:9411
```

## 배포 시간 병목 후보

현재 workflow는 안전하게 전체 영향 범위를 잡는 대신 공통 파일 변경 시 빌드 범위가 넓어진다.

주요 병목:

- Docker build 내부 Gradle `bootJar` 반복
- 공통 파일 변경 시 전체 서비스 빌드
- IAP 기반 VM별 `ssh/scp` 반복
- VM별 Docker image pull

개선 후보:

- Actions runner에서 Gradle build 후 JAR만 Docker image에 복사
- VM별 여러 서비스 pull/up을 한 번의 SSH 명령으로 묶기
- 공통 변경 감지 범위 세분화
- Docker build cache hit율 확인
