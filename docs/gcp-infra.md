# GCP 인프라 구성 문서

이 문서는 HubLink 프로젝트를 GCP Compute Engine VM 4대에 배포하기 위한 인프라 구성과 배포 흐름을 정리합니다.

## GCP 인프라 계획

Terraform으로 다음 리소스를 생성할 예정입니다.

- VPC
- Subnet
- Firewall Rule
- Compute Engine VM 4대
- VM별 고정 internal IP
- Artifact Registry
- VM 실행용 Service Account
- Docker 설치용 startup script

Terraform은 인프라 생성을 담당하고, 애플리케이션 이미지는 Docker로 빌드한 뒤 Artifact Registry에 push합니다.

```text
Terraform
  -> GCP 네트워크와 VM 생성

Docker / Gradle
  -> 서비스별 이미지 빌드

Artifact Registry
  -> 이미지 저장

Docker Compose
  -> VM별 서비스 실행
```

---

## 배포 파일 구조

GCP VM 배포를 위해 Docker Compose 파일을 VM 역할별로 분리했습니다.

```text
docker-compose.platform.yml
docker-compose.domain-a.yml
docker-compose.domain-b.yml
docker-compose.data-monitor.yml
```

환경 변수 예시는 다음 파일에 있습니다.

```text
.env.gcp.example
```

각 VM에서는 이 파일을 `.env.gcp`로 복사한 뒤 실제 GCP 프로젝트, 내부 IP, DB 비밀번호, 외부 API 키 등을 설정합니다.

```bash
cp .env.gcp.example .env.gcp
```

---

## 서비스 설정 구조

각 서비스의 `src/main/resources/application.yaml`은 최소 설정만 유지합니다.

```yaml
spring:
  application:
    name: service-name
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:19092}
```

실제 런타임 설정은 `config-repo`에서 관리합니다.

```text
config-repo/
  api-gateway.yml
  user-service.yml
  order-service.yml
  hub-service.yml
  company-service.yml
  product-service.yml
  stock-service.yml
  delivery-service.yml
  slack-service.yml
  ai-service.yml
```

GCP 배포에서는 Config Server가 `config-repo`를 읽고, 각 서비스는 Config Server에서 DB, Redis, Kafka, Eureka, Zipkin 설정을 받아옵니다.

---

## 배포 흐름

### 1. Terraform으로 인프라 생성

```bash
cd infra/gcp
terraform init
terraform plan
terraform apply
```

생성 예정 리소스:

- `platform-vm`
- `domain-a-vm`
- `domain-b-vm`
- `data-monitor-vm`
- VPC/Subnet/Firewall
- Artifact Registry

### 2. 서비스 이미지 빌드

서비스별 이미지는 루트 `Dockerfile`의 `SERVICE_NAME` build arg를 사용해 빌드합니다.

```bash
docker build --build-arg SERVICE_NAME=eureka-server -t hublink-eureka-server .
docker build --build-arg SERVICE_NAME=config-server -t hublink-config-server .
docker build --build-arg SERVICE_NAME=api-gateway -t hublink-api-gateway .
```

다른 서비스도 같은 방식으로 빌드합니다.

```bash
docker build --build-arg SERVICE_NAME=user-service -t hublink-user-service .
docker build --build-arg SERVICE_NAME=order-service -t hublink-order-service .
docker build --build-arg SERVICE_NAME=delivery-service -t hublink-delivery-service .
```

### 3. Artifact Registry에 push

이미지 태그는 `.env.gcp`의 `IMAGE_REGISTRY` 값과 맞춥니다.

```bash
docker tag hublink-user-service asia-northeast3-docker.pkg.dev/PROJECT_ID/hublink/hublink-user-service:latest
docker push asia-northeast3-docker.pkg.dev/PROJECT_ID/hublink/hublink-user-service:latest
```

### 4. VM별 Docker Compose 실행

각 VM에서 자기 역할에 맞는 Compose 파일을 실행합니다.

```bash
docker compose --env-file .env.gcp -f docker-compose.data-monitor.yml up -d
docker compose --env-file .env.gcp -f docker-compose.platform.yml up -d
docker compose --env-file .env.gcp -f docker-compose.domain-a.yml up -d
docker compose --env-file .env.gcp -f docker-compose.domain-b.yml up -d
```

권장 실행 순서:

```text
1. data-monitor-vm
2. platform-vm
3. domain-a-vm
4. domain-b-vm
```

---

## 접속 주소

| 항목 | URL |
| --- | --- |
| API Gateway | `http://<platform-vm-external-ip>:19091` |
| Eureka Dashboard | `http://<platform-vm-external-ip>:19090` |
| Kafka UI | `http://<data-monitor-vm-external-ip>:8082` |
| Zipkin | `http://<data-monitor-vm-external-ip>:9411` |
| Prometheus | `http://<data-monitor-vm-external-ip>:9090` |
| Grafana | `http://<data-monitor-vm-external-ip>:3000` |

외부 공개가 필요하지 않은 포트는 GCP 방화벽에서 내부 통신만 허용합니다.

---

## 모니터링

GCP VM 배포용 Prometheus 설정은 다음 파일을 사용합니다.

```text
monitoring/prometheus.gcp.yml
```

이 파일은 Docker 컨테이너 이름이 아니라 VM 내부 IP를 기준으로 Actuator Prometheus endpoint를 scrape합니다.

```text
10.10.0.20:19093
10.10.0.30:19094
10.10.0.30:19099
```

로컬 단일 Docker Compose 환경에서는 기존 `monitoring/prometheus.yml`을 사용할 수 있습니다.

---

## DB 초기화

GCP 배포용 PostgreSQL 컨테이너는 다음 SQL을 사용해 서비스별 schema를 생성합니다.

```text
db/init/01-create-schemas.sql
```

각 서비스는 하나의 PostgreSQL 인스턴스를 공유하되, `currentSchema`를 통해 서비스별 schema를 사용합니다.

```text
user_service
order_service
hub_service
company_service
product_service
stock_service
delivery_service
slack_service
ai_service
```

---

## 로컬 실행과 GCP 실행

기존 로컬 실행 파일은 유지합니다.

```text
docker-compose.yml
monitoring/prometheus.yml
```

GCP VM 실행 파일은 별도로 사용합니다.

```text
docker-compose.platform.yml
docker-compose.domain-a.yml
docker-compose.domain-b.yml
docker-compose.data-monitor.yml
monitoring/prometheus.gcp.yml
```

로컬 실행과 GCP 실행은 네트워크 전제가 다릅니다.

| 환경 | 서비스 통신 방식 |
| --- | --- |
| 로컬 Docker Compose | Docker service name |
| GCP VM | GCP internal IP |

---

## 다음 작업

- Terraform 디렉터리 구성
- GCP VPC/Subnet/Firewall 작성
- Compute Engine VM 4대 생성
- Artifact Registry 생성
- Docker 설치 startup script 작성
- 이미지 빌드 및 push 스크립트 작성
- VM 배포 후 Eureka, Gateway, Prometheus 확인
- k6 기반 성능 테스트 시나리오 작성
