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
DEPLOY_MODE="${DEPLOY_MODE:-full}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-}"
DEPLOY_SKIP_DOCKER_ENSURE="${DEPLOY_SKIP_DOCKER_ENSURE:-false}"
DEPLOY_FORCE_RECREATE="${DEPLOY_FORCE_RECREATE:-false}"

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
ARCHIVE_FILE="$(mktemp)"
REMOTE_ARCHIVE="${REMOTE_DEPLOY_DIR}/deploy.tar.gz"

cleanup() {
  rm -f "${ARCHIVE_FILE}"
}
trap cleanup EXIT

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

archive_paths=(
  ".env.gcp"
  "${COMPOSE_FILE}"
  "scripts/gcp/ensure-docker.sh"
  "scripts/gcp/hublink-compose-up.sh"
)

for path in "$@"; do
  archive_paths+=("${path}")
done

for path in "${archive_paths[@]}"; do
  if [ ! -e "${path}" ]; then
    echo "deploy path does not exist: ${path}" >&2
    exit 1
  fi
done

echo "Preparing deployment archive for ${VM_NAME}"
tar -czf "${ARCHIVE_FILE}" "${archive_paths[@]}"

# 원격 배포 디렉터리 권한 준비
echo "Preparing ${REMOTE}:${REMOTE_DIR}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "sudo mkdir -p '${REMOTE_DIR}' '${REMOTE_DEPLOY_DIR}' && sudo chown -R '${VM_USER}:${VM_USER}' '${REMOTE_DIR}'"

echo "Copying deployment archive to ${VM_NAME}"
retry "scp deployment archive" \
  gcloud compute scp "${ARCHIVE_FILE}" "${REMOTE}:${REMOTE_ARCHIVE}" "${GCLOUD_FLAGS[@]}"

# Docker와 compose 재부팅 복구 서비스 준비
# Artifact Registry pull 인증
# 커밋 SHA 이미지 pull
# 변경 컨테이너 교체와 orphan 정리
echo "Deploying ${COMPOSE_FILE} on ${VM_NAME} mode=${DEPLOY_MODE} services=${DEPLOY_SERVICES:-all}"
ACCESS_TOKEN="$(gcloud auth print-access-token)"

gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "set -euo pipefail
cd '${REMOTE_DIR}'
tar -xzf '${REMOTE_ARCHIVE}' -C '${REMOTE_DIR}'

if [ '${DEPLOY_SKIP_DOCKER_ENSURE}' != 'true' ] || ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  sudo REMOTE_DIR='${REMOTE_DIR}' bash '${REMOTE_DIR}/scripts/gcp/ensure-docker.sh' '${VM_USER}' '${COMPOSE_FILE}' '${REMOTE_DIR}/scripts/gcp/hublink-compose-up.sh'
fi

if [ '${DEPLOY_MODE}' = 'sync' ]; then
  echo 'sync-only deployment completed'
  exit 0
fi

printf '%s' '${ACCESS_TOKEN}' | sudo docker login -u oauth2accesstoken --password-stdin 'https://${REGISTRY_HOST}'

case '${DEPLOY_MODE}' in
  full)
    for attempt in 1 2 3; do
      sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull && break
      echo \"docker compose pull failed. attempt=\${attempt}\" >&2
      if [ \"\${attempt}\" -eq 3 ]; then exit 1; fi
      sleep \$((attempt * 20))
    done
    sudo REMOTE_DIR='${REMOTE_DIR}' HUBLINK_DEPLOY_MODE='full' bash '${REMOTE_DIR}/scripts/gcp/hublink-compose-up.sh'
    ;;
  image)
    if [ -n '${DEPLOY_SERVICES}' ]; then
      for attempt in 1 2 3; do
        sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull ${DEPLOY_SERVICES} && break
        echo \"docker compose pull failed. attempt=\${attempt}\" >&2
        if [ \"\${attempt}\" -eq 3 ]; then exit 1; fi
        sleep \$((attempt * 20))
      done
    else
      for attempt in 1 2 3; do
        sudo docker compose --env-file .env.gcp -f '${COMPOSE_FILE}' pull && break
        echo \"docker compose pull failed. attempt=\${attempt}\" >&2
        if [ \"\${attempt}\" -eq 3 ]; then exit 1; fi
        sleep \$((attempt * 20))
      done
    fi
    sudo REMOTE_DIR='${REMOTE_DIR}' HUBLINK_DEPLOY_MODE='image' HUBLINK_FORCE_RECREATE='${DEPLOY_FORCE_RECREATE}' HUBLINK_DEPLOY_SERVICES='${DEPLOY_SERVICES}' bash '${REMOTE_DIR}/scripts/gcp/hublink-compose-up.sh'
    ;;
  config)
    sudo REMOTE_DIR='${REMOTE_DIR}' HUBLINK_DEPLOY_MODE='config' HUBLINK_FORCE_RECREATE='true' HUBLINK_DEPLOY_SERVICES='${DEPLOY_SERVICES}' bash '${REMOTE_DIR}/scripts/gcp/hublink-compose-up.sh'
    ;;
  *)
    echo 'unknown DEPLOY_MODE: ${DEPLOY_MODE}' >&2
    exit 1
    ;;
esac

sudo docker compose -f '${COMPOSE_FILE}' ps"
