#!/usr/bin/env bash
set -euo pipefail

# 실행 대상 스크립트
SCRIPT_NAME="${1:-delivery-create-kafka-load.js}"
shift || true

# 기본 실행 환경
ENV_FILE="${ENV_FILE:-.env.k6}"
BASE_URL="${BASE_URL:-http://10.10.0.10:19091}"
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
  -v "$PWD:/scripts"
)

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
)

for env_name in "${pass_env_vars[@]}"; do
  if [ -n "${!env_name:-}" ]; then
    docker_args+=(-e "${env_name}=${!env_name}")
  fi
done

# k6 Docker 실행
"${docker_bin[@]}" "${docker_args[@]}" grafana/k6:latest run "/scripts/${SCRIPT_NAME}" "$@"
