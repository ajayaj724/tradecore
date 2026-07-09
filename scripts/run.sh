#!/usr/bin/env bash
# Run the tradecore app (foreground). Ensures the platform is up and the app port is free first.
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

clear_port "$APP_PORT"

curl -sf "$KC_URL/realms/$KC_REALM/.well-known/openid-configuration" >/dev/null 2>&1 \
  || "$(dirname "$0")/up.sh"

note "starting tradecore on $APP_URL (Ctrl-C to stop)"
# 'local' profile opens /actuator/prometheus for the compose scraper (ADR-0017); prod is secure by default
exec mvn spring-boot:run -Dspring-boot.run.profiles=local
