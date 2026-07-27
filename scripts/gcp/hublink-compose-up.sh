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
  docker-compose.platform-monitoring.yml)
    ROLE="platform-monitoring"
    ;;
  docker-compose.platform.yml)
    ROLE="platform"
    ;;
  docker-compose.domain-a.yml)
    ROLE="domain-a"
    ;;
  docker-compose.domain-b.yml)
    ROLE="domain-b"
    ;;
  docker-compose.delivery.yml)
    ROLE="delivery"
    ;;
  docker-compose.data.yml)
    ROLE="data"
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

wait_config_service() {
  local service="$1"
  local max_seconds="${2:-600}"

  wait_http "Config ${service}" "${CONFIG_URL}/${service}/default" "${max_seconds}"
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

wait_eureka_service() {
  local service="$1"
  local app="$2"
  local max_seconds="${3:-600}"

  echo "waiting for current Eureka instance ${app}"
  for _ in $(seq 1 "${max_seconds}"); do
    local container_id
    local container_hostname
    local response

    container_id="$(docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" ps -q "${service}" 2>/dev/null || true)"
    if [ -n "${container_id}" ]; then
      container_hostname="$(docker inspect --format '{{.Config.Hostname}}' "${container_id}" 2>/dev/null || true)"
      response="$(curl -fsS -H 'Accept: application/json' "${EUREKA_URL}/eureka/apps/${app}" 2>/dev/null || true)"
      if [ -n "${container_hostname}" ] \
        && grep -Fq "\"instanceId\":\"${container_hostname}:" <<< "${response}" \
        && grep -Fq '"status":"UP"' <<< "${response}"; then
        echo "current Eureka instance ${app} is ready"
        return 0
      fi
    fi
    sleep 1
  done

  echo "current Eureka instance ${app} is not ready after ${max_seconds}s" >&2
  return 1
}

wait_registered_service() {
  local service="$1"
  local app="$2"
  local health_url="$3"

  wait_http "${service}" "${health_url}"
  wait_eureka_service "${service}" "${app}"
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
      wait_registered_service "api-gateway" "API-GATEWAY" "http://localhost:19091/actuator/health"
      ;;
    user-service)
      wait_registered_service "user-service" "USER-SERVICE" "http://localhost:19093/actuator/health"
      ;;
    company-service)
      wait_registered_service "company-service" "COMPANY-SERVICE" "http://localhost:19096/actuator/health"
      ;;
    hub-service)
      wait_registered_service "hub-service" "HUB-SERVICE" "http://localhost:19095/actuator/health"
      ;;
    product-service)
      wait_registered_service "product-service" "PRODUCT-SERVICE" "http://localhost:19097/actuator/health"
      ;;
    order-service)
      wait_registered_service "order-service" "ORDER-SERVICE" "http://localhost:19094/actuator/health"
      ;;
    stock-service)
      wait_registered_service "stock-service" "STOCK-SERVICE" "http://localhost:19098/actuator/health"
      ;;
    delivery-service)
      wait_registered_service "delivery-service" "DELIVERY-SERVICE" "http://localhost:19099/actuator/health"
      ;;
    slack-service)
      wait_registered_service "slack-service" "SLACK-SERVICE" "http://localhost:19100/actuator/health"
      ;;
    ai-service)
      wait_registered_service "ai-service" "AI-SERVICE" "http://localhost:19101/actuator/health"
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

start_promtail_before_wait() {
  case "${ROLE}" in
    platform|platform-monitoring|domain-a|domain-b|delivery)
      echo "starting promtail before readiness checks"
      docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" up -d --no-deps promtail
      ;;
  esac
}

start_promtail_before_wait

case "${ROLE}" in
  platform-monitoring)
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    ;;
  domain-a)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_config_service "user-service"
    wait_config_service "company-service"
    wait_config_service "hub-service"
    wait_config_service "product-service"
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Redis" "${DATA_HOST}" 6379
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    ;;
  domain-b)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_config_service "order-service"
    wait_config_service "stock-service"
    wait_config_service "slack-service"
    wait_config_service "ai-service"
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Redis" "${DATA_HOST}" 6379
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    if [ "${#target_services[@]}" -eq 0 ]; then
      wait_eureka_app "PRODUCT-SERVICE"
    fi
    ;;
  delivery)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_config_service "delivery-service"
    wait_tcp "PostgreSQL" "${DATA_HOST}" 5432
    wait_tcp "Redis" "${DATA_HOST}" 6379
    wait_tcp "Kafka" "${DATA_HOST}" 9092
    # 배송 서비스 선행 등록 상태 확인
    wait_eureka_app "COMPANY-SERVICE"
    wait_eureka_app "HUB-SERVICE"
    wait_eureka_app "USER-SERVICE"
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
  platform-monitoring)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_config_service "api-gateway"
    wait_compose_service "api-gateway"
    wait_http "Prometheus" "http://localhost:9090/-/healthy"
    wait_http "Loki" "http://localhost:3100/ready"
    wait_http "Grafana" "http://localhost:3000/api/health"
    ;;
  platform)
    wait_http "Eureka" "${EUREKA_URL}/actuator/health"
    wait_http "Config Server" "${CONFIG_URL}/actuator/health"
    wait_config_service "api-gateway"
    wait_compose_service "api-gateway"
    ;;
  domain-a)
    wait_compose_service "company-service"
    wait_compose_service "hub-service"
    wait_compose_service "user-service"
    wait_compose_service "product-service"
    ;;
  domain-b)
    wait_compose_service "order-service"
    wait_compose_service "stock-service"
    wait_compose_service "slack-service"
    wait_compose_service "ai-service"
    ;;
  delivery)
    wait_compose_service "delivery-service"
    ;;
esac
