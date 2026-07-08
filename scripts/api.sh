#!/usr/bin/env bash
# Authenticated API call. Usage: api.sh METHOD PATH [JSON_BODY] [USERNAME]
#   api.sh GET  /api/v1/orders
#   api.sh POST /api/v1/orders '{"symbol":"INFY",...}' trader1
# POSTs get a generated Idempotency-Key; every call gets an X-Correlation-Id.
source "$(dirname "$0")/_common.sh"

METHOD="${1:?usage: api.sh METHOD PATH [JSON_BODY] [USERNAME]}"
API_PATH="${2:?usage: api.sh METHOD PATH [JSON_BODY] [USERNAME]}"
BODY="${3:-}"
USER_NAME="${4:-trader1}"

TOKEN=$("$(dirname "$0")/token.sh" "$USER_NAME")
CORRELATION_ID=$(uuidgen)

ARGS=(-si -X "$METHOD" "$APP_URL$API_PATH"
  -H "Authorization: Bearer $TOKEN"
  -H "X-Correlation-Id: $CORRELATION_ID")

if [[ -n "$BODY" ]]; then
  ARGS+=(-H "Content-Type: application/json" -d "$BODY")
fi
if [[ "$METHOD" == "POST" ]]; then
  ARGS+=(-H "Idempotency-Key: $(uuidgen)")
fi

note "$METHOD $API_PATH as $USER_NAME (correlation: $CORRELATION_ID)"
curl "${ARGS[@]}"
echo
