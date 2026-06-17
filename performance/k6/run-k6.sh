#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# 실행 대상 스크립트
SCRIPT_NAME="${1:-delivery-create-kafka-load.js}"
shift || true

ENV_FILE="${ENV_FILE:-.env.k6}"

if [ -f "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi

# 기본 실행 환경
BASE_URL="${BASE_URL:-http://10.10.0.10:19091}"
PRE_TEST_SQL_FILE="${PRE_TEST_SQL_FILE:-db/seed/11-reset-delivery-loadtest-baseline.sql}"
POST_TEST_SQL_FILE="${POST_TEST_SQL_FILE:-}"
RESET_DB_CONTAINER="${RESET_DB_CONTAINER:-hublink-postgres}"
RESET_DB_HOST="${RESET_DB_HOST:-10.10.0.40}"
RESET_DB_PORT="${RESET_DB_PORT:-5432}"
RESET_DB_USER="${RESET_DB_USER:-user}"
RESET_DB_PASSWORD="${RESET_DB_PASSWORD:-0000}"
RESET_DB_NAME="${RESET_DB_NAME:-hublink}"
docker_bin=(docker)

# docker 그룹 권한 없을 때 sudo 전환
if ! docker info >/dev/null 2>&1; then
  docker_bin=(sudo docker)
fi

# k6 컨테이너 실행 옵션
docker_args=(
  run
  --rm
  --network host
  -v "${SCRIPT_DIR}:/scripts"
)

run_sql_file() {
  local phase="$1"
  local sql_file="$2"
  local sql_path="$sql_file"

  if [ -z "${sql_path}" ]; then
    return 0
  fi

  if [[ "${sql_path}" != /* ]]; then
    sql_path="${REPO_ROOT}/${sql_path}"
  fi

  if [ ! -f "${sql_path}" ]; then
    echo "[run-k6] ${phase} SQL 파일 없음: ${sql_path}" >&2
    return 1
  fi

  echo "[run-k6] ${phase} SQL 실행: ${sql_path}"
  if [ -n "${RESET_DB_HOST}" ]; then
    "${docker_bin[@]}" run --rm --network host -i \
      -e "PGPASSWORD=${RESET_DB_PASSWORD}" \
      postgres:16 \
      psql \
        -h "${RESET_DB_HOST}" \
        -p "${RESET_DB_PORT}" \
        -U "${RESET_DB_USER}" \
        -d "${RESET_DB_NAME}" < "${sql_path}"
  else
    "${docker_bin[@]}" exec -i "${RESET_DB_CONTAINER}" \
      psql -U "${RESET_DB_USER}" -d "${RESET_DB_NAME}" < "${sql_path}"
  fi
}

run_post_test_sql() {
  local exit_code=$?
  trap - EXIT

  if [ -n "${POST_TEST_SQL_FILE}" ]; then
    if ! run_sql_file "post-test reset" "${POST_TEST_SQL_FILE}"; then
      local reset_exit=$?
      if [ "${exit_code}" -eq 0 ]; then
        exit_code="${reset_exit}"
      fi
    fi
  fi

  exit "${exit_code}"
}

trap run_post_test_sql EXIT

# VM 로컬 실행값 주입
if [ -f "${ENV_FILE}" ]; then
  docker_args+=(--env-file "${ENV_FILE}")
fi

# 셸 입력 BASE_URL 우선 적용
docker_args+=(-e "BASE_URL=${BASE_URL}")

# 선택 실행 옵션 전달
pass_env_vars=(
  STAGES
  SLEEP_SECONDS
  DELIVERY_BASE_URL
  TARGET_PATH
  USER_ROLE
  USER_ID
  ACCESS_TOKEN
  SUPPLY_COMPANY_ID
  SUPPLY_COMPANY_IDS
  SUPPLIER_COMPANY_ID
  SUPPLIER_COMPANY_IDS
  RECEIVER_COMPANY_ID
  RECEIVER_COMPANY_IDS
  PRODUCT_ID
  PRODUCT_IDS
  PRODUCT_NAME
  PRODUCT_NAMES
  ORDER_QUANTITY
  PRODUCT_QUANTITY
  DEADLINE_HOURS
  REQUEST_MESSAGE
  DELIVERY_ADDRESS
  RECEIVER_NAME
  REQUESTED_ARRIVAL_AT
  RECEIVER_SLACK_ID
  DEADLINE_DAYS
  PATHS
  RESET_DB_HOST
  RESET_DB_PORT
  RESET_DB_USER
  RESET_DB_PASSWORD
  RESET_DB_NAME
  RESET_DB_CONTAINER
)

for env_name in "${pass_env_vars[@]}"; do
  if [ -n "${!env_name:-}" ]; then
    docker_args+=(-e "${env_name}=${!env_name}")
  fi
done

if [ -n "${PRE_TEST_SQL_FILE}" ]; then
  run_sql_file "pre-test reset" "${PRE_TEST_SQL_FILE}"
fi

# k6 Docker 실행
"${docker_bin[@]}" "${docker_args[@]}" grafana/k6:latest run "/scripts/${SCRIPT_NAME}" "$@"
