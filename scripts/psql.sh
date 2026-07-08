#!/usr/bin/env bash
# Open psql inside the platform database. Extra args pass through:
#   psql.sh -c 'select * from event_publication;'
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

exec docker compose exec postgres psql -U tradecore -d tradecore "$@"
