
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
  mkdir -p /root/.docker
  printf '{"credHelpers":{"%s-docker.pkg.dev":"gcloud"}}' "${region}" >/root/.docker/config.json
fi
