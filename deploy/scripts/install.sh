#!/usr/bin/env bash
# First installation on a VM, as root: install.sh <mcp-confluence.jar>. Review before running.
set -euo pipefail

APP_USER=mcp
APP_DIR=/opt/mcp-confluence
CFG_DIR=/etc/mcp-confluence
JAR_SRC="${1:?usage: install.sh <path-to-mcp-confluence.jar>}"

id -u "$APP_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"

install -d -o root -g "$APP_USER" -m 0750 "$APP_DIR" "$CFG_DIR" "$CFG_DIR/tls"
install -o root -g "$APP_USER" -m 0640 "$JAR_SRC" "$APP_DIR/mcp-confluence.jar"
install -o root -g root -m 0644 deploy/systemd/mcp-confluence.service /etc/systemd/system/mcp-confluence.service
systemctl daemon-reload
echo "Installed. Next: $CFG_DIR/mcp-confluence.env (from deploy/systemd/mcp-confluence.env.template),"
echo "keystore and truststore in $CFG_DIR/tls, then: systemctl enable --now mcp-confluence"
