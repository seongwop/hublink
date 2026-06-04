#!/usr/bin/env bash
set -euo pipefail

# VM 단위 공통 배포 스크립트
# 사용 예시: deploy-vm.sh hublink-platform-vm docker-compose.platform.yml config-repo
# 추가 복사 파일과 디렉터리 인자
# DEPLOY_SERVICES 공백 구분 서비스명
# SKIP_COMPOSE=true 설정 시 파일 복사만 수행
if [ "$#" -lt 2 ]; then
  echo "Usage: deploy-vm.sh <vm-name> <compose-file> [extra-path ...]" >&2
  exit 1
fi

VM_NAME="$1"
COMPOSE_FILE="$2"
shift 2

PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID is required}"
ZONE="${GCP_ZONE:?GCP_ZONE is required}"
VM_USER="${GCP_VM_USER:?GCP_VM_USER is required}"
REMOTE_DIR="${GCP_REMOTE_DIR:-/opt/hublink}"
REGION="${GCP_REGION:-asia-northeast3}"

# Ubuntu runner IAP 터널 접속
# 2시간 임시 SSH 키 등록
GCLOUD_FLAGS=(
  --zone "${ZONE}"
  --project "${PROJECT_ID}"
  --tunnel-through-iap
  --quiet
  --strict-host-key-checking=no
  --ssh-key-expire-after=2h
)

REMOTE="${VM_USER}@${VM_NAME}"

# Docker 설치 상태 확인
echo "Checking Docker on ${VM_NAME}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "set -e; if ! command -v docker >/dev/null 2>&1; then echo 'Docker not found. Installing Docker...'; sudo apt-get update; sudo apt-get install -y ca-certificates curl; curl -fsSL https://get.docker.com | sudo sh; fi; sudo systemctl enable --now docker; if command -v gcloud >/dev/null 2>&1; then gcloud auth configure-docker '${REGION}-docker.pkg.dev' --quiet || true; sudo mkdir -p /root/.docker; printf '{\"credHelpers\":{\"${REGION}-docker.pkg.dev\":\"gcloud\"}}' | sudo tee /root/.docker/config.json >/dev/null; fi; sudo docker version; sudo docker compose version"

# 원격 배포 디렉터리 권한 준비
echo "Preparing ${REMOTE}:${REMOTE_DIR}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "sudo mkdir -p '${REMOTE_DIR}' && sudo chown -R '${VM_USER}:${VM_USER}' '${REMOTE_DIR}'"

# 공통 환경파일과 VM 전용 compose 복사
echo "Copying .env.gcp and ${COMPOSE_FILE} to ${VM_NAME}"
gcloud compute scp .env.gcp "${COMPOSE_FILE}" "${REMOTE}:${REMOTE_DIR}/" "${GCLOUD_FLAGS[@]}"

# VM별 추가 파일 복사
for path in "$@"; do
  echo "Copying ${path} to ${VM_NAME}"
  gcloud compute scp --recurse "${path}" "${REMOTE}:${REMOTE_DIR}/" "${GCLOUD_FLAGS[@]}"
done

# 설정 파일만 반영하는 복사 전용 모드
if [ "${SKIP_COMPOSE:-false}" = "true" ]; then
  echo "Skip docker compose on ${VM_NAME}"
  exit 0
fi

# 지정 서비스만 pull/up
if [ -n "${DEPLOY_SERVICES:-}" ]; then
  echo "Deploying services on ${VM_NAME}: ${DEPLOY_SERVICES}"
  gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
    --command "cd '${REMOTE_DIR}' && sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull ${DEPLOY_SERVICES} && sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' up -d --no-deps ${DEPLOY_SERVICES} && sudo docker compose -f '${COMPOSE_FILE}' ps"
  exit 0
fi

# 전체 compose pull/up
echo "Deploying ${COMPOSE_FILE} on ${VM_NAME}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "cd '${REMOTE_DIR}' && sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull && sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' up -d --remove-orphans && sudo docker compose -f '${COMPOSE_FILE}' ps"
