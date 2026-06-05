
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
