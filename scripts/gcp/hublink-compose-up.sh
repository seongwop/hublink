#!/usr/bin/env bash
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/opt/hublink}"
COMPOSE_FILE="$(cat /etc/hublink-compose-file 2>/dev/null || true)"
EUREKA_URL="http://10.10.0.10:19090"
CONFIG_URL="http://10.10.0.10:19092"
DATA_HOST="10.10.0.40"
DEPLOY_MODE="${HUBLINK_DEPLOY_MODE:-full}"
DEPLOY_SERVICES="${HUBLINK_DEPLOY_SERVICES:-}"
FORCE_RECREATE="${HUBLINK_FORCE_RECREATE:-false}"

if [ -z "${COMPOSE_FILE}" ]; then
  exit 0
fi

case "${COMPOSE_FILE}" in
  docker-compose.platform.yml)
    ROLE="platform"
    ;;
  docker-compose.domain-a.yml)
    ROLE="domain-a"
    ;;
  docker-compose.domain-b.yml)
    ROLE="domain-b"
    ;;
  docker-compose.data.yml)
    ROLE="data"
    ;;
  docker-compose.monitoring.yml)
    ROLE="monitoring"
    ;;
  *)
    ROLE=""
    ;;
esac

if [ ! -f "${REMOTE_DIR}/.env.gcp" ] || [ ! -f "${REMOTE_DIR}/${COMPOSE_FILE}" ]; then
  exit 0
fi

cd "${REMOTE_DIR}"

target_services=()
if [ -n "${DEPLOY_SERVICES}" ]; then
  read -r -a target_services <<< "${DEPLOY_SERVICES}"
fi

wait_http() {
  local name="$1"
  local url="$2"
  local max_seconds="${3:-600}"

  echo "waiting for ${name}: ${url}"
  for _ in $(seq 1 "${max_seconds}"); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${name} is ready"
      return 0
    fi
    sleep 1
  done

  echo "${name} is not ready after ${max_seconds}s" >&2
  return 1
}

wait_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local max_seconds="${4:-600}"

  echo "waiting for ${name}: ${host}:${port}"
  for _ in $(seq 1 "${max_seconds}"); do
    if timeout 1 bash -c ":</dev/tcp/${host}/${port}" >/dev/null 2>&1; then
      echo "${name} is ready"
      return 0
    fi
    sleep 1
  done

  echo "${name} is not ready after ${max_seconds}s" >&2
  return 1
}

wait_eureka_app() {
  local app="$1"
  local max_seconds="${2:-600}"

  wait_http "Eureka app ${app}" "${EUREKA_URL}/eureka/apps/${app}" "${max_seconds}"
}

wait_compose_service() {
  local service="$1"

  case "${service}" in
    eureka-server)
      wait_http "Eureka" "${EUREKA_URL}/actuator/health"
      ;;
    config-server)
      wait_http "Config Server" "${CONFIG_URL}/actuator/health"
      ;;
    api-gateway)
      wait_eureka_app "API-GATEWAY"
      ;;
    user-service)
      wait_eureka_app "USER-SERVICE"
      ;;
    company-service)
      wait_eureka_app "COMPANY-SERVICE"
      ;;
    hub-service)
      wait_eureka_app "HUB-SERVICE"
      ;;
    product-service)
      wait_eureka_app "PRODUCT-SERVICE"
      ;;
    order-service)
      wait_eureka_app "ORDER-SERVICE"
      ;;
    stock-service)
      wait_eureka_app "STOCK-SERVICE"
      ;;
    delivery-service)
      wait_eureka_app "DELIVERY-SERVICE"
      ;;
    postgres)
      wait_tcp "PostgreSQL" "localhost" 5432
      ;;
    redis)
      wait_tcp "Redis" "localhost" 6379
      ;;
    kafka)
      wait_tcp "Kafka" "localhost" 9092
      ;;
    prometheus)
      wait_http "Prometheus" "http://localhost:9090/-/healthy"
      ;;
    loki)
      wait_http "Loki" "http://localhost:3100/ready"
      ;;
    grafana)
      wait_http "Grafana" "http://localhost:3000/api/health"
      ;;
  esac
}

wait_target_services() {
  for service in "${target_services[@]}"; do
    wait_compose_service "${service}"
  done
}

stop_compose_before_wait() {
  if [ "${#target_services[@]}" -gt 0 ]; then
    return
  fi

  case "${ROLE}" in
    platform|domain-a|domain-b)
      echo "stopping existing compose services before readiness checks"
      docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" stop || true
      ;;
  esac
}

stop_compose_before_wait

case "${ROLE}" in
  domain-a)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Redis" "${DATA_HOST}" 6379
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    ;;
  domain-b)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Redis" "${DATA_HOST}" 6379
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    wait_eureka_app "COMPANY-SERVICE"
    wait_eureka_app "HUB-SERVICE"
    wait_eureka_app "USER-SERVICE"
    wait_eureka_app "PRODUCT-SERVICE"
    ;;
  monitoring)
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    ;;
esac

if [ "${#target_services[@]}" -gt 0 ]; then
  up_args=(up -d --no-deps)
  if [ "${DEPLOY_MODE}" = "config" ] || [ "${FORCE_RECREATE}" = "true" ]; then
    up_args+=(--force-recreate)
  fi

  docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" "${up_args[@]}" "${target_services[@]}"
  wait_target_services
  exit 0
fi

docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" up -d --remove-orphans

case "${ROLE}" in
  platform)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    ;;
  domain-a)
    wait_eureka_app "COMPANY-SERVICE"
    wait_eureka_app "HUB-SERVICE"
    wait_eureka_app "USER-SERVICE"
    wait_eureka_app "PRODUCT-SERVICE"
    ;;
  domain-b)
    wait_eureka_app "DELIVERY-SERVICE"
    wait_eureka_app "ORDER-SERVICE"
    wait_eureka_app "STOCK-SERVICE"
    ;;
  monitoring)
    wait_http "Prometheus" "http://localhost:9090/-/healthy"
    wait_http "Loki" "http://localhost:3100/ready"
    wait_http "Grafana" "http://localhost:3000/api/health"
    ;;
esac
