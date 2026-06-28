#!/usr/bin/env bash
set -euo pipefail

LINUX_USER="${1:?linux user is required}"
COMPOSE_FILE="${2:-}"
REMOTE_DIR="${REMOTE_DIR:-/opt/hublink}"

export DEBIAN_FRONTEND=noninteractive

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  apt-get update
  apt-get install -y ca-certificates curl gnupg lsb-release

  install -m 0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.gpg ]; then
    curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
  fi

  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" \
    >/etc/apt/sources.list.d/docker.list

  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

systemctl enable --now docker

if id "${LINUX_USER}" >/dev/null 2>&1; then
  usermod -aG docker "${LINUX_USER}"
fi

mkdir -p "${REMOTE_DIR}"
if id "${LINUX_USER}" >/dev/null 2>&1; then
  chown -R "${LINUX_USER}:${LINUX_USER}" "${REMOTE_DIR}"
fi

if [ -n "${COMPOSE_FILE}" ]; then
  echo "${COMPOSE_FILE}" >/etc/hublink-compose-file
fi

cat >/usr/local/bin/hublink-compose-up <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/opt/hublink}"
COMPOSE_FILE="$(cat /etc/hublink-compose-file 2>/dev/null || true)"

if [ -z "${COMPOSE_FILE}" ]; then
  exit 0
fi

if [ ! -f "${REMOTE_DIR}/.env.gcp" ] || [ ! -f "${REMOTE_DIR}/${COMPOSE_FILE}" ]; then
  exit 0
fi

cd "${REMOTE_DIR}"
docker compose --env-file .env.gcp -f "${COMPOSE_FILE}" up -d
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
