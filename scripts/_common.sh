#!/usr/bin/env bash
# Shared defaults for tradecore management scripts. Source, don't execute.
set -euo pipefail

APP_URL="${TRADECORE_APP_URL:-http://localhost:8080}"
APP_PORT="${TRADECORE_APP_PORT:-8080}"
KC_URL="${TRADECORE_KC_URL:-http://localhost:8081}"
KC_REALM="tradecore"
KC_CLIENT="tradecore-api"
PLATFORM_PORTS=(5432 8081 4318 9090 3001)

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '==> %s\n' "$*"; }

repo_root() { git -C "$(dirname "${BASH_SOURCE[1]}")" rev-parse --show-toplevel; }

# Free a port before use. Our own stale processes (java/maven) are killed;
# Docker's forwarders are left alone (compose reuses them); anything foreign
# stops us with identification unless FORCE=1.
clear_port() {
  local port=$1 pids pid cmd
  pids=$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
  [[ -z "$pids" ]] && return 0
  for pid in $pids; do
    cmd=$(ps -o comm= -p "$pid" 2>/dev/null || true)
    case "$cmd" in
      *java*|*mvn*)
        note "port $port held by stale ${cmd##*/} (pid $pid) — clearing"
        kill "$pid" 2>/dev/null || true
        sleep 2
        kill -9 "$pid" 2>/dev/null || true
        ;;
      *docker*|*com.docker*|*vpnkit*)
        # our compose stack's forwarder — compose up -d reuses it
        ;;
      *)
        if [[ "${FORCE:-0}" == "1" ]]; then
          note "port $port held by '${cmd##*/}' (pid $pid) — FORCE=1, clearing"
          kill "$pid" 2>/dev/null || true
        else
          die "port $port is held by '${cmd##*/}' (pid $pid) — not a tradecore process. Stop it yourself, or rerun with FORCE=1 to kill it."
        fi
        ;;
    esac
  done
}
