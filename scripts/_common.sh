#!/usr/bin/env bash
# Shared defaults for tradecore management scripts. Source, don't execute.
set -euo pipefail

APP_URL="${TRADECORE_APP_URL:-http://localhost:8080}"
KC_URL="${TRADECORE_KC_URL:-http://localhost:8081}"
KC_REALM="tradecore"
KC_CLIENT="tradecore-api"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '==> %s\n' "$*"; }

repo_root() { git -C "$(dirname "${BASH_SOURCE[1]}")" rev-parse --show-toplevel; }
