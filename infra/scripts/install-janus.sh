#!/bin/bash
set -euxo pipefail

dnf update -y
dnf install -y docker

systemctl enable --now docker

# Placeholder bootstrap for the Janus host.
# Next step: add a pinned Janus Docker image and TLS reverse proxy config.
cat >/etc/motd <<'EOF'
Janus host created by Terraform.
Install/configure Janus and HTTPS/WSS proxy before production use.
EOF
