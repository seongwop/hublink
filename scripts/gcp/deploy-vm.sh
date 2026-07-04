#!/usr/bin/env bash
set -euo pipefail

# VM 단위 공통 배포 스크립트
# 사용 예시: deploy-vm.sh hublink-platform-vm docker-compose.platform.yml config-repo
# 추가 복사 파일과 디렉터리 인자
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
REMOTE_DEPLOY_DIR="${REMOTE_DIR}/.deploy"
REGISTRY_HOST="${GCP_REGION:-asia-northeast3}-docker.pkg.dev"

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

retry() {
  local description="$1"
  shift

  for attempt in 1 2 3; do
    "$@" && return 0
    echo "${description} failed. attempt=${attempt}" >&2
    if [ "${attempt}" -eq 3 ]; then
      return 1
    fi
    sleep $((attempt * 10))
  done
}

copy_path() {
  local path="$1"

  echo "Copying ${path} to ${VM_NAME}"
  if [ -d "${path}" ]; then
    retry "scp ${path}" \
      gcloud compute scp --recurse "${path}" "${REMOTE}:${REMOTE_DIR}/" "${GCLOUD_FLAGS[@]}"
    return
  fi

  local remote_path="${REMOTE_DIR}/${path}"
  local remote_parent
  remote_parent="$(dirname "${remote_path}")"

  gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
    --command "mkdir -p '${remote_parent}'"
  retry "scp ${path}" \
    gcloud compute scp "${path}" "${REMOTE}:${remote_path}" "${GCLOUD_FLAGS[@]}"
}

# 원격 배포 디렉터리 권한 준비
echo "Preparing ${REMOTE}:${REMOTE_DIR}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "sudo mkdir -p '${REMOTE_DIR}' '${REMOTE_DEPLOY_DIR}' && sudo chown -R '${VM_USER}:${VM_USER}' '${REMOTE_DIR}'"

# Docker와 compose 재부팅 복구 서비스 준비
echo "Ensuring Docker on ${VM_NAME}"
retry "scp docker helper scripts" \
  gcloud compute scp scripts/gcp/ensure-docker.sh scripts/gcp/hublink-compose-up.sh "${REMOTE}:${REMOTE_DEPLOY_DIR}/" "${GCLOUD_FLAGS[@]}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "sudo REMOTE_DIR='${REMOTE_DIR}' bash '${REMOTE_DEPLOY_DIR}/ensure-docker.sh' '${VM_USER}' '${COMPOSE_FILE}' '${REMOTE_DEPLOY_DIR}/hublink-compose-up.sh'"

# 공통 환경파일과 VM 전용 compose 복사
echo "Copying .env.gcp and ${COMPOSE_FILE} to ${VM_NAME}"
retry "scp env and compose files" \
  gcloud compute scp .env.gcp "${COMPOSE_FILE}" "${REMOTE}:${REMOTE_DIR}/" "${GCLOUD_FLAGS[@]}"

# VM별 추가 파일 복사
for path in "$@"; do
  copy_path "${path}"
done

# Artifact Registry pull 인증
echo "Configuring Docker registry auth on ${VM_NAME}"
ACCESS_TOKEN="$(gcloud auth print-access-token)"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "printf '%s' '${ACCESS_TOKEN}' | sudo docker login -u oauth2accesstoken --password-stdin 'https://${REGISTRY_HOST}'"

# 커밋 SHA 이미지 pull
# 변경 컨테이너 교체와 orphan 정리
echo "Deploying ${COMPOSE_FILE} on ${VM_NAME}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "cd '${REMOTE_DIR}' && for attempt in 1 2 3; do sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull && break; echo \"docker compose pull failed. attempt=\${attempt}\" >&2; if [ \"\${attempt}\" -eq 3 ]; then exit 1; fi; sleep \$((attempt * 20)); done && sudo /usr/local/bin/hublink-compose-up && sudo docker compose -f '${COMPOSE_FILE}' ps"
