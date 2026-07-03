#!/usr/bin/env bash

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

ROLE="${role}"
echo "$ROLE" >/etc/hublink-role

case "$ROLE" in
  platform)
    echo "docker-compose.platform.yml" >/etc/hublink-compose-file
    ;;
  domain-a)
    echo "docker-compose.domain-a.yml" >/etc/hublink-compose-file
    ;;
  domain-b)
    echo "docker-compose.domain-b.yml" >/etc/hublink-compose-file
    ;;
  data|data-monitor)
    echo "docker-compose.data.yml" >/etc/hublink-compose-file
    ;;
  monitoring|monitor)
    echo "docker-compose.monitoring.yml" >/etc/hublink-compose-file
    ;;
esac

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
${compose_up_script}
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
TimeoutStartSec=20min
Restart=on-failure
RestartSec=30s

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
