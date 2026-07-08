#!/usr/bin/env bash
# Stop the local platform. --wipe also removes volumes (fresh DB + realm on next up).
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

if [[ "${1:-}" == "--wipe" ]]; then
  note "stopping and removing volumes (full reset)"
  docker compose down -v
else
  note "stopping (data kept; use --wipe for a full reset)"
  docker compose down
fi
