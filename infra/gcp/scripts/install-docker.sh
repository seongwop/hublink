
#!/usr/bin/env bash

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "hublink role: ${role}" >/etc/hublink-role

apt-get update
apt-get install -y ca-certificates curl gnupg lsb-release git

install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
  curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
fi

. /etc/os-release
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $${VERSION_CODENAME} stable" \
  >/etc/apt/sources.list.d/docker.list

apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable --now docker

if id "${linux_user}" >/dev/null 2>&1; then
  usermod -aG docker "${linux_user}"
fi

mkdir -p /opt/hublink
if id "${linux_user}" >/dev/null 2>&1; then
  chown -R "${linux_user}:${linux_user}" /opt/hublink
fi

if command -v gcloud >/dev/null 2>&1; then
  gcloud auth configure-docker "${region}-docker.pkg.dev" --quiet || true
fi

cat >/usr/local/bin/hublink-compose-up <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

ROLE="$(cat /etc/hublink-role 2>/dev/null || true)"
REMOTE_DIR="/opt/hublink"
EUREKA_URL="http://10.10.0.10:19090"
CONFIG_URL="http://10.10.0.10:19092"
DATA_HOST="10.10.0.40"

wait_http() {
  local name="$1"
  local url="$2"
  local max_seconds="$${3:-600}"

  echo "waiting for $name: $url"
  for _ in $(seq 1 "$max_seconds"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$name is ready"
      return 0
    fi
    sleep 1
  done

  echo "$name is not ready after $${max_seconds}s" >&2
  return 1
}

wait_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local max_seconds="$${4:-600}"

  echo "waiting for $name: $host:$port"
  for _ in $(seq 1 "$max_seconds"); do
    if timeout 1 bash -c ":</dev/tcp/$host/$port" >/dev/null 2>&1; then
      echo "$name is ready"
      return 0
    fi
    sleep 1
  done

  echo "$name is not ready after $${max_seconds}s" >&2
  return 1
}

wait_eureka_app() {
  local app="$1"
  local max_seconds="$${2:-600}"

  wait_http "Eureka app $app" "$EUREKA_URL/eureka/apps/$app" "$max_seconds"
}

case "$ROLE" in
  platform)
    COMPOSE_FILE="docker-compose.platform.yml"
    ;;
  domain-a)
    COMPOSE_FILE="docker-compose.domain-a.yml"
    ;;
  domain-b)
    COMPOSE_FILE="docker-compose.domain-b.yml"
    ;;
  data|data-monitor)
    COMPOSE_FILE="docker-compose.data.yml"
    ;;
  monitoring|monitor)
    COMPOSE_FILE="docker-compose.monitoring.yml"
    ;;
  *)
    exit 0
    ;;
esac

if [ ! -f "$REMOTE_DIR/.env.gcp" ] || [ ! -f "$REMOTE_DIR/$COMPOSE_FILE" ]; then
  exit 0
fi

cd "$REMOTE_DIR"

case "$ROLE" in
  domain-a)
    wait_http "Eureka" "$EUREKA_URL/actuator/health"
    wait_http "Config Server" "$CONFIG_URL/actuator/health"
    wait_tcp "PostgreSQL" "$DATA_HOST" 5432
    wait_tcp "Redis" "$DATA_HOST" 6379
    wait_tcp "Kafka" "$DATA_HOST" 9092
    ;;
  domain-b)
    wait_http "Eureka" "$EUREKA_URL/actuator/health"
    wait_http "Config Server" "$CONFIG_URL/actuator/health"
    wait_tcp "PostgreSQL" "$DATA_HOST" 5432
    wait_tcp "Redis" "$DATA_HOST" 6379
    wait_tcp "Kafka" "$DATA_HOST" 9092
    wait_eureka_app "COMPANY-SERVICE"
    wait_eureka_app "HUB-SERVICE"
    wait_eureka_app "USER-SERVICE"
    wait_eureka_app "PRODUCT-SERVICE"
    ;;
esac

docker compose --env-file .env.gcp -f "$COMPOSE_FILE" up -d

case "$ROLE" in
  platform)
    wait_http "Eureka" "$EUREKA_URL/actuator/health"
    wait_http "Config Server" "$CONFIG_URL/actuator/health"
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
esac
SCRIPT

chmod +x /usr/local/bin/hublink-compose-up

cat >/etc/systemd/system/hublink-compose.service <<'EOF'
[Unit]
Description=HubLink Docker Compose startup
After=docker.service network-online.target
Wants=docker.service network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/hublink-compose-up
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable hublink-compose.service
systemctl start hublink-compose.service || true

if [ "${role}" = "load-test" ]; then
  cat >/usr/local/bin/hublink-k6-smoke <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

TARGET_URL="$${1:-http://10.10.0.10:19091/actuator/health}"

docker run --rm -i grafana/k6:latest run -e TARGET_URL="$TARGET_URL" - <<'K6'
import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
};

export default function () {
  http.get(__ENV.TARGET_URL);
  sleep(1);
}
K6
SCRIPT
  chmod +x /usr/local/bin/hublink-k6-smoke
fi
