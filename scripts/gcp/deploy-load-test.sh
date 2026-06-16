#!/usr/bin/env bash
set -euo pipefail

# 필수 배포 변수
PROJECT_ID="${GCP_PROJECT_ID:?GCP_PROJECT_ID is required}"
ZONE="${GCP_ZONE:?GCP_ZONE is required}"
VM_USER="${GCP_VM_USER:?GCP_VM_USER is required}"
VM_NAME="${GCP_LOAD_TEST_VM:?GCP_LOAD_TEST_VM is required}"
REMOTE_DIR="${GCP_LOAD_TEST_REMOTE_DIR:-/opt/hublink/performance}"
REMOTE_ROOT="${GCP_LOAD_TEST_REMOTE_ROOT:-$(dirname "${REMOTE_DIR}")}"

# GitHub Actions runner IAP 접속 옵션
GCLOUD_FLAGS=(
  --zone "${ZONE}"
  --project "${PROJECT_ID}"
  --tunnel-through-iap
  --quiet
  --strict-host-key-checking=no
  --ssh-key-expire-after=2h
)

# 원격 접속 대상
REMOTE="${VM_USER}@${VM_NAME}"

# 원격 스크립트 디렉터리 준비
echo "Preparing ${REMOTE}:${REMOTE_DIR}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "sudo mkdir -p '${REMOTE_DIR}' '${REMOTE_ROOT}/db' && sudo chown -R '${VM_USER}:${VM_USER}' '${REMOTE_DIR}' '${REMOTE_ROOT}/db'"

# k6 디렉터리 재귀 복사
echo "Copying performance/k6 to ${VM_NAME}"
gcloud compute scp --recurse performance/k6 "${REMOTE}:${REMOTE_DIR}/" "${GCLOUD_FLAGS[@]}"

echo "Copying db/seed to ${VM_NAME}"
gcloud compute scp --recurse db/seed "${REMOTE}:${REMOTE_ROOT}/db/" "${GCLOUD_FLAGS[@]}"

# 실행 스크립트 권한 보정
echo "Preparing k6 runner on ${VM_NAME}"
gcloud compute ssh "${REMOTE}" "${GCLOUD_FLAGS[@]}" \
  --command "chmod +x '${REMOTE_DIR}/k6/run-k6.sh' && ls -la '${REMOTE_DIR}/k6' && ls -la '${REMOTE_ROOT}/db/seed'"
