#!/usr/bin/env bash
# Local dev control server: serves the console with working Manage + Operations buttons.
# Open http://localhost:8090/console.html after starting.
set -euo pipefail
cd "$(dirname "$0")/.."
exec bun scripts/console-server.mjs
