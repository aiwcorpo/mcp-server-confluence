#!/usr/bin/env bash
# Replaces the jar and restarts the service, keeping the previous jar for rollback. Run as root.
set -euo pipefail
APP_DIR=/opt/mcp-confluence
JAR_SRC="${1:?usage: update.sh <path-to-new.jar>}"
cp -a "$APP_DIR/mcp-confluence.jar" "$APP_DIR/mcp-confluence.jar.bak.$(date +%Y%m%d%H%M%S)"
install -o root -g mcp -m 0640 "$JAR_SRC" "$APP_DIR/mcp-confluence.jar"
systemctl restart mcp-confluence
systemctl --no-pager status mcp-confluence | head -n 15
