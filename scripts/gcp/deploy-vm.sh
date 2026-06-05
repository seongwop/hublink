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

# 커밋 SHA 이미지 pull
# 변경 컨테이너 교체와 orphan 정리
echo "Deploying ${COMPOSE_FILE} on ${VM_NAME}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "cd '${REMOTE_DIR}' && for attempt in 1 2 3; do sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull && break; echo \"docker compose pull failed. attempt=\${attempt}\" >&2; if [ \"\${attempt}\" -eq 3 ]; then exit 1; fi; sleep \$((attempt * 20)); done && sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' up -d --remove-orphans && sudo docker compose -f '${COMPOSE_FILE}' ps"
