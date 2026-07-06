#!/usr/bin/env bash
# Print an access token for a demo user. Usage: token.sh [username] [password]
# Default user: trader1/demo. Compose with: TOKEN=$(scripts/token.sh ops1)
source "$(dirname "$0")/_common.sh"

USER_NAME="${1:-trader1}"
PASSWORD="${2:-demo}"

RESPONSE=$(curl -sf -X POST "$KC_URL/realms/$KC_REALM/protocol/openid-connect/token" \
  -d grant_type=password -d "client_id=$KC_CLIENT" \
  -d "username=$USER_NAME" -d "password=$PASSWORD") \
  || die "token request failed — is the platform up? (scripts/up.sh)"

python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])" <<<"$RESPONSE" \
  || die "no access_token in response: $RESPONSE"
