#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="/opt/hublink"
SCRIPT_DIR="${REPO_ROOT}/performance/k6"
SCRIPT_NAME="${K6_SCRIPT:-delivery-create-logic-load.js}"
PRE_TEST_SQL_FILE="${PRE_TEST_SQL_FILE:-db/seed/14-reset-delivery-perf-baseline.sql}"
POST_TEST_SQL_FILE="${POST_TEST_SQL_FILE:-}"

if [[ ! "${SCRIPT_NAME}" =~ ^[A-Za-z0-9._-]+\.js$ ]]; then
  echo "[cloud-run-k6] 잘못된 스크립트 이름: ${SCRIPT_NAME}" >&2
  exit 1
fi

if [ ! -f "${SCRIPT_DIR}/${SCRIPT_NAME}" ]; then
  echo "[cloud-run-k6] 스크립트 없음: ${SCRIPT_DIR}/${SCRIPT_NAME}" >&2
  exit 1
fi

# 기존 100VU 대표 조건
K6_VUS="${K6_VUS:-100}"
K6_RAMP_UP="${K6_RAMP_UP:-1m}"
K6_STEADY="${K6_STEADY:-5m}"
K6_RAMP_DOWN="${K6_RAMP_DOWN:-2m}"
STAGES="${STAGES:-[{\"duration\":\"${K6_RAMP_UP}\",\"target\":${K6_VUS}},{\"duration\":\"${K6_STEADY}\",\"target\":${K6_VUS}},{\"duration\":\"${K6_RAMP_DOWN}\",\"target\":0}]}"

# 배송 생성 테스트 고정 입력
DELIVERY_BASE_URL="${DELIVERY_BASE_URL:-http://10.10.0.70:19099}"
SUPPLIER_COMPANY_ID="${SUPPLIER_COMPANY_ID:-20000000-0000-0000-0000-000000000001}"
RECEIVER_COMPANY_IDS="${RECEIVER_COMPANY_IDS:-20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027}"
PRODUCT_NAME="${PRODUCT_NAME:-k6-test-product}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0}"
USER_ROLE="${USER_ROLE:-MASTER}"

RESET_DB_HOST="${RESET_DB_HOST:-10.10.0.40}"
RESET_DB_PORT="${RESET_DB_PORT:-5432}"
RESET_DB_USER="${RESET_DB_USER:-user}"
RESET_DB_NAME="${RESET_DB_NAME:-hublink}"

export DELIVERY_BASE_URL
export PRODUCT_NAME
export RECEIVER_COMPANY_IDS
export SLEEP_SECONDS
export STAGES
export SUPPLIER_COMPANY_ID
export USER_ROLE

run_sql_file() {
  local phase="$1"
  local sql_file="$2"
  local sql_path="${sql_file}"

  if [ -z "${sql_path}" ]; then
    return 0
  fi

  if [[ "${sql_path}" != /* ]]; then
    sql_path="${REPO_ROOT}/${sql_path}"
  fi

  if [ ! -f "${sql_path}" ]; then
    echo "[cloud-run-k6] ${phase} SQL 없음: ${sql_path}" >&2
    return 1
  fi

  : "${RESET_DB_PASSWORD:?RESET_DB_PASSWORD가 필요합니다}"

  echo "[cloud-run-k6] ${phase} SQL 실행: ${sql_file}"
  PGPASSWORD="${RESET_DB_PASSWORD}" psql \
    --set ON_ERROR_STOP=1 \
    --host "${RESET_DB_HOST}" \
    --port "${RESET_DB_PORT}" \
    --username "${RESET_DB_USER}" \
    --dbname "${RESET_DB_NAME}" \
    --file "${sql_path}"
}

run_post_test_sql() {
  local exit_code=$?
  trap - EXIT

  if [ -n "${POST_TEST_SQL_FILE}" ] && ! run_sql_file "post-test reset" "${POST_TEST_SQL_FILE}"; then
    if [ "${exit_code}" -eq 0 ]; then
      exit_code=1
    fi
  fi

  exit "${exit_code}"
}

trap run_post_test_sql EXIT

run_sql_file "pre-test reset" "${PRE_TEST_SQL_FILE}"

echo "[cloud-run-k6] script=${SCRIPT_NAME} vus=${K6_VUS} stages=${STAGES} sleep=${SLEEP_SECONDS}"
cd "${SCRIPT_DIR}"
k6 run "${SCRIPT_NAME}"
