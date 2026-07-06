#!/usr/bin/env bash
# Tail platform logs. Usage: logs.sh [service]  (default: all; e.g. logs.sh keycloak)
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

exec docker compose logs -f --tail=100 "${1:-}"
