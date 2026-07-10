#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/hublink-compose-up.sh"
TEST_ROOT="$(mktemp -d)"
MOCK_BIN="${TEST_ROOT}/bin"
REMOTE_DIR="${TEST_ROOT}/remote"
LOG_FILE="${TEST_ROOT}/commands.log"

cleanup() {
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

mkdir -p "${MOCK_BIN}" "${REMOTE_DIR}"
touch "${REMOTE_DIR}/.env.gcp" "${REMOTE_DIR}/docker-compose.domain-b.yml"

cat >"${MOCK_BIN}/cat" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "/etc/hublink-compose-file" ]; then
  printf '%s\n' 'docker-compose.domain-b.yml'
  exit 0
fi
exec /bin/cat "$@"
EOF

cat >"${MOCK_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
EOF

cat >"${MOCK_BIN}/timeout" <<'EOF'
#!/usr/bin/env bash
printf 'timeout %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
EOF

cat >"${MOCK_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
EOF

chmod +x "${MOCK_BIN}/cat" "${MOCK_BIN}/curl" "${MOCK_BIN}/timeout" "${MOCK_BIN}/docker"
export HUBLINK_TEST_LOG="${LOG_FILE}"

# delivery-service 부분 배포 검증
: >"${LOG_FILE}"
PATH="${MOCK_BIN}:${PATH}" \
REMOTE_DIR="${REMOTE_DIR}" \
HUBLINK_DEPLOY_MODE="image" \
HUBLINK_DEPLOY_SERVICES="delivery-service" \
bash "${TARGET_SCRIPT}"

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-b.yml up -d --no-deps delivery-service' "${LOG_FILE}"
grep -Fq '/eureka/apps/DELIVERY-SERVICE' "${LOG_FILE}"

if grep -Eq '/eureka/apps/(COMPANY|HUB|USER|PRODUCT)-SERVICE' "${LOG_FILE}"; then
  echo '부분 배포에서 domain-a 서비스 대기 발생' >&2
  exit 1
fi

# domain-b 전체 배포 검증
: >"${LOG_FILE}"
PATH="${MOCK_BIN}:${PATH}" \
REMOTE_DIR="${REMOTE_DIR}" \
HUBLINK_DEPLOY_MODE="full" \
HUBLINK_DEPLOY_SERVICES="" \
bash "${TARGET_SCRIPT}"

grep -Fq '/eureka/apps/COMPANY-SERVICE' "${LOG_FILE}"
grep -Fq '/eureka/apps/HUB-SERVICE' "${LOG_FILE}"
grep -Fq '/eureka/apps/USER-SERVICE' "${LOG_FILE}"
grep -Fq '/eureka/apps/PRODUCT-SERVICE' "${LOG_FILE}"
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-b.yml up -d --remove-orphans' "${LOG_FILE}"

echo 'hublink-compose-up 배포 분기 검증 완료'
