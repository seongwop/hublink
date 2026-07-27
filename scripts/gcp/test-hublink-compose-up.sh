#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
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
touch \
  "${REMOTE_DIR}/.env.gcp" \
  "${REMOTE_DIR}/docker-compose.platform-monitoring.yml" \
  "${REMOTE_DIR}/docker-compose.platform.yml" \
  "${REMOTE_DIR}/docker-compose.domain-a.yml" \
  "${REMOTE_DIR}/docker-compose.domain-b.yml" \
  "${REMOTE_DIR}/docker-compose.delivery.yml"

cat >"${MOCK_BIN}/cat" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "/etc/hublink-compose-file" ]; then
  printf '%s\n' "${HUBLINK_TEST_COMPOSE_FILE:-docker-compose.domain-b.yml}"
  exit 0
fi
exec /bin/cat "$@"
EOF

cat >"${MOCK_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
if [[ "$*" == *'/eureka/apps/'* ]]; then
  printf '%s\n' '{"application":{"instance":{"instanceId":"mock-host:service:19000","status":"UP"}}}'
fi
EOF

cat >"${MOCK_BIN}/timeout" <<'EOF'
#!/usr/bin/env bash
printf 'timeout %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
EOF

cat >"${MOCK_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >>"${HUBLINK_TEST_LOG}"
if [[ "$*" == *' ps -q '* ]]; then
  printf '%s\n' 'mock-container'
elif [ "${1:-}" = "inspect" ]; then
  printf '%s\n' 'mock-host'
fi
EOF

chmod +x "${MOCK_BIN}/cat" "${MOCK_BIN}/curl" "${MOCK_BIN}/timeout" "${MOCK_BIN}/docker"
export HUBLINK_TEST_LOG="${LOG_FILE}"

run_compose() {
  local compose_file="$1"
  local deploy_mode="$2"
  local deploy_services="$3"

  : >"${LOG_FILE}"
  PATH="${MOCK_BIN}:${PATH}" \
  REMOTE_DIR="${REMOTE_DIR}" \
  HUBLINK_TEST_COMPOSE_FILE="${compose_file}" \
  HUBLINK_DEPLOY_MODE="${deploy_mode}" \
  HUBLINK_DEPLOY_SERVICES="${deploy_services}" \
  bash "${TARGET_SCRIPT}"
}

assert_before() {
  local first="$1"
  local second="$2"
  local first_line
  local second_line

  first_line="$(grep -nF "${first}" "${LOG_FILE}" | head -n 1 | cut -d: -f1)"
  second_line="$(grep -nF "${second}" "${LOG_FILE}" | head -n 1 | cut -d: -f1)"
  if [ -z "${first_line}" ] || [ -z "${second_line}" ] || [ "${first_line}" -ge "${second_line}" ]; then
    echo "명령 순서 오류: ${first} -> ${second}" >&2
    exit 1
  fi
}

assert_count() {
  local expected="$1"
  local pattern="$2"
  local file="$3"
  local actual

  actual="$(grep -Fc "${pattern}" "${file}")"
  if [ "${actual}" -ne "${expected}" ]; then
    echo "설정 개수 오류: ${file} pattern=${pattern} expected=${expected} actual=${actual}" >&2
    exit 1
  fi
}

# 배송 서비스 부분 배포 검증
run_compose "docker-compose.delivery.yml" "image" "delivery-service"

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --no-deps promtail' "${LOG_FILE}"
grep -Fq 'curl -fsS http://10.10.0.10:19092/delivery-service/default' "${LOG_FILE}"
grep -Fq '/eureka/apps/COMPANY-SERVICE' "${LOG_FILE}"
grep -Fq '/eureka/apps/HUB-SERVICE' "${LOG_FILE}"
grep -Fq '/eureka/apps/USER-SERVICE' "${LOG_FILE}"
if grep -Fq '/eureka/apps/PRODUCT-SERVICE' "${LOG_FILE}"; then
  echo '배송 서비스 부분 배포에서 불필요한 상품 서비스 대기 발생' >&2
  exit 1
fi
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --no-deps delivery-service' "${LOG_FILE}"
grep -Fq 'curl -fsS http://localhost:19099/actuator/health' "${LOG_FILE}"
grep -Fq 'docker inspect --format {{.Config.Hostname}} mock-container' "${LOG_FILE}"

# 배송 VM 전체 재기동 순서 검증
run_compose "docker-compose.delivery.yml" "full" ""

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --no-deps promtail' "${LOG_FILE}"
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --remove-orphans' "${LOG_FILE}"
assert_before \
  'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --no-deps promtail' \
  'curl -fsS http://10.10.0.10:19090/actuator/health'
assert_before \
  'curl -fsS http://10.10.0.10:19092/delivery-service/default' \
  'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --remove-orphans'
assert_before \
  'docker compose --env-file .env.gcp -f docker-compose.delivery.yml up -d --remove-orphans' \
  'curl -fsS http://localhost:19099/actuator/health'

# domain-b 전체 재기동 순서 검증
run_compose "docker-compose.domain-b.yml" "full" ""

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-b.yml up -d --no-deps promtail' "${LOG_FILE}"
grep -Fq '/eureka/apps/PRODUCT-SERVICE' "${LOG_FILE}"
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-b.yml up -d --remove-orphans' "${LOG_FILE}"
if grep -Fq 'delivery-service/default' "${LOG_FILE}"; then
  echo 'domain-b 재기동에서 배송 서비스 설정 대기 발생' >&2
  exit 1
fi
if grep -Fq 'localhost:19099/actuator/health' "${LOG_FILE}"; then
  echo 'domain-b 재기동에서 배송 서비스 상태 확인 발생' >&2
  exit 1
fi

# domain-a 전체 재기동 순서 검증
run_compose "docker-compose.domain-a.yml" "full" ""

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-a.yml up -d --no-deps promtail' "${LOG_FILE}"
grep -Fq 'curl -fsS http://10.10.0.10:19092/company-service/default' "${LOG_FILE}"
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.domain-a.yml up -d --remove-orphans' "${LOG_FILE}"
assert_before \
  'docker compose --env-file .env.gcp -f docker-compose.domain-a.yml up -d --remove-orphans' \
  'curl -fsS http://localhost:19096/actuator/health'

# 플랫폼과 모니터링 통합 재기동 순서 검증
run_compose "docker-compose.platform-monitoring.yml" "full" ""

grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.platform-monitoring.yml up -d --no-deps promtail' "${LOG_FILE}"
grep -Fq 'timeout 1 bash -c :</dev/tcp/10.10.0.40/5432' "${LOG_FILE}"
grep -Fq 'timeout 1 bash -c :</dev/tcp/10.10.0.40/9092' "${LOG_FILE}"
grep -Fq 'docker compose --env-file .env.gcp -f docker-compose.platform-monitoring.yml up -d --remove-orphans' "${LOG_FILE}"
grep -Fq 'curl -fsS http://localhost:9090/-/healthy' "${LOG_FILE}"
grep -Fq 'curl -fsS http://localhost:3100/ready' "${LOG_FILE}"
grep -Fq 'curl -fsS http://localhost:3000/api/health' "${LOG_FILE}"
assert_before \
  'timeout 1 bash -c :</dev/tcp/10.10.0.40/5432' \
  'docker compose --env-file .env.gcp -f docker-compose.platform-monitoring.yml up -d --remove-orphans'

# Docker 조기 종료 차단과 Config Server 필수 연결 검증
assert_count 3 'restart: on-failure' "${REPO_ROOT}/docker-compose.platform.yml"
assert_count 4 'restart: on-failure' "${REPO_ROOT}/docker-compose.domain-a.yml"
assert_count 4 'restart: on-failure' "${REPO_ROOT}/docker-compose.domain-b.yml"
assert_count 1 'restart: on-failure' "${REPO_ROOT}/docker-compose.delivery.yml"
assert_count 1 'SPRING_CONFIG_IMPORT: configserver:${CONFIG_SERVER_URL}' "${REPO_ROOT}/docker-compose.platform.yml"
assert_count 4 'SPRING_CONFIG_IMPORT: configserver:${CONFIG_SERVER_URL}' "${REPO_ROOT}/docker-compose.domain-a.yml"
assert_count 4 'SPRING_CONFIG_IMPORT: configserver:${CONFIG_SERVER_URL}' "${REPO_ROOT}/docker-compose.domain-b.yml"
assert_count 1 'SPRING_CONFIG_IMPORT: configserver:${CONFIG_SERVER_URL}' "${REPO_ROOT}/docker-compose.delivery.yml"
grep -Fq '/usr/local/bin/hublink-compose-up' "${SCRIPT_DIR}/deploy-vm.sh"
config_branch="$(sed -n '/^  config)/,/^    ;;/p' "${SCRIPT_DIR}/deploy-vm.sh")"
grep -Fq "pull \${DEPLOY_SERVICES}" <<<"${config_branch}"

echo 'hublink-compose-up 재기동 순서 검증 완료'
