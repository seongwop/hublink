#!/usr/bin/env bash
set -euo pipefail

# 실행 대상 스크립트
SCRIPT_NAME="${1:-gateway-smoke.js}"
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
if [ -n "${STAGES:-}" ]; then
  docker_args+=(-e "STAGES=${STAGES}")
fi

# k6 Docker 실행
"${docker_bin[@]}" "${docker_args[@]}" grafana/k6:latest run "/scripts/${SCRIPT_NAME}" "$@"
