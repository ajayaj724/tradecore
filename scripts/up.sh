#!/usr/bin/env bash
# Start the local platform (Postgres, Keycloak, observability when present) and wait until usable.
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

note "starting compose services"
docker compose up -d

note "waiting for postgres"
for _ in $(seq 1 30); do
  docker compose exec -T postgres pg_isready -U tradecore >/dev/null 2>&1 && break
  sleep 2
done
docker compose exec -T postgres pg_isready -U tradecore >/dev/null 2>&1 || die "postgres not ready after 60s"

note "waiting for keycloak realm import"
for _ in $(seq 1 45); do
  curl -sf "$KC_URL/realms/$KC_REALM/.well-known/openid-configuration" >/dev/null 2>&1 && break
  sleep 2
done
curl -sf "$KC_URL/realms/$KC_REALM/.well-known/openid-configuration" >/dev/null 2>&1 \
  || die "keycloak realm '$KC_REALM' not ready after 90s (see scripts/logs.sh keycloak)"

docker compose ps
note "ready — app: scripts/run.sh · token: scripts/token.sh · keycloak admin: $KC_URL (admin/admin, local only)"
