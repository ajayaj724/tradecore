#!/usr/bin/env bash
# tradecore end-to-end smoke / regression suite.
#
# Black-box assertions against the RUNNING app over HTTP — this is the deployed-stack regression
# layer that complements the in-process Testcontainers suite (`mvn verify`). It catches wiring,
# security, and integration regressions that in-process tests can't (real Keycloak tokens, the real
# filter chain, the real matching path end to end).
#
# Run every time before a deploy. To cover a NEW feature: add a check_<feature> function and list it
# in RUN_CHECKS — nothing else to wire up.
#
# Usage:  scripts/up.sh && scripts/run.sh   # in another terminal
#         scripts/smoke.sh
source "$(dirname "$0")/_common.sh"
set +e # a failing assertion must never abort the suite; run them all and report every result

PASS=0
FAIL=0
green() { printf '\033[32m%s\033[0m' "$1"; }
red() { printf '\033[31m%s\033[0m' "$1"; }
ok() {
  PASS=$((PASS + 1))
  printf '    %s %s\n' "$(green ✓)" "$1"
}
bad() {
  FAIL=$((FAIL + 1))
  printf '    %s %s\n        %s\n' "$(red ✗)" "$1" "$2"
}

# call METHOD PATH TOKEN [JSON_BODY] -> sets STATUS and BODY globals
call() {
  local method=$1 path=$2 token=$3 json=${4:-} tmp
  tmp=$(mktemp)
  local args=(-s -o "$tmp" -w '%{http_code}' -X "$method" "$APP_URL$path")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$json" ]] && args+=(-H "Content-Type: application/json" -d "$json")
  [[ "$method" == POST ]] && args+=(-H "Idempotency-Key: $(uuidgen)")
  STATUS=$(curl "${args[@]}")
  BODY=$(cat "$tmp")
  rm -f "$tmp"
}

expect_status() { # expect_status NAME EXPECTED_CODE
  if [[ "$STATUS" == "$2" ]]; then ok "$1"; else bad "$1" "expected HTTP $2, got $STATUS — $BODY"; fi
}
expect_body() { # expect_body NAME SUBSTRING
  if grep -qF "$2" <<<"$BODY"; then ok "$1"; else bad "$1" "response missing '$2' — $BODY"; fi
}

# ── checks: one function per shipped capability ────────────────────────────────────────────────

check_health_is_public() {
  call GET /actuator/health ""
  expect_status "health endpoint is public" 200
  expect_body "health reports UP" '"status":"UP"'
}

check_unauthenticated_is_problem_json_401() {
  call GET /api/v1/orders/1 ""
  expect_status "unauthenticated API call is rejected" 401
  local ct
  ct=$(curl -s -o /dev/null -w '%{content_type}' "$APP_URL/api/v1/orders/1")
  if grep -qF "application/problem+json" <<<"$ct"; then
    ok "401 renders RFC 9457 problem+json"
  else
    bad "401 renders RFC 9457 problem+json" "content-type was: $ct"
  fi
}

check_openapi_and_swagger_are_public() {
  call GET /v3/api-docs ""
  expect_status "OpenAPI spec is public" 200
  expect_body "OpenAPI spec documents the orders API" '/api/v1/orders'
  call GET /swagger-ui/index.html ""
  expect_status "Swagger UI is reachable" 200
}

check_prometheus_scrape_is_public() {
  call GET /actuator/prometheus ""
  expect_status "Prometheus scrape is public" 200
}

check_order_fills_end_to_end() {
  local t1 t2 buy_id
  t2=$(scripts/token.sh trader2)
  t1=$(scripts/token.sh trader1)
  call POST /api/v1/orders "$t2" '{"symbol":"ACME","side":"SELL","price":10000,"quantity":1}'
  expect_status "trader2 SELL is accepted" 201
  call POST /api/v1/orders "$t1" '{"symbol":"ACME","side":"BUY","price":10000,"quantity":1}'
  expect_status "trader1 BUY is accepted" 201
  buy_id=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' <<<"$BODY")
  # matching and fill-settlement are event-driven (async), so poll for eventual consistency
  local i
  for i in $(seq 1 20); do
    call GET "/api/v1/orders/$buy_id" "$t1"
    grep -qF '"status":"FILLED"' <<<"$BODY" && break
    sleep 0.5
  done
  expect_status "the buy is readable by its owner" 200
  expect_body "the buy matched to FILLED (async settle)" '"status":"FILLED"'
}

check_risk_rejects_insufficient_cash() {
  local t1
  t1=$(scripts/token.sh trader1)
  # cost far exceeds the seeded cash → a domain rejection (a 201 carrying status REJECTED, not a fault)
  call POST /api/v1/orders "$t1" '{"symbol":"ACME","side":"BUY","price":10000,"quantity":1000000}'
  expect_status "an over-cash order is a domain outcome, not a fault" 201
  expect_body "the over-cash order is REJECTED" '"status":"REJECTED"'
}

check_cancel_releases_a_resting_order() {
  local t1 order_id i
  t1=$(scripts/token.sh trader1)
  # a BUY at 1 paise never crosses, so it rests as ACCEPTED and is cancellable
  call POST /api/v1/orders "$t1" '{"symbol":"ACME","side":"BUY","price":1,"quantity":1}'
  expect_status "a resting buy is accepted" 201
  expect_body "the resting buy is ACCEPTED" '"status":"ACCEPTED"'
  order_id=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' <<<"$BODY")
  call POST "/api/v1/orders/$order_id/cancel" "$t1"
  expect_status "the cancellation is accepted (202, async)" 202
  # cancellation is event-driven, so poll for the terminal CANCELLED state
  for i in $(seq 1 20); do
    call GET "/api/v1/orders/$order_id" "$t1"
    grep -qF '"status":"CANCELLED"' <<<"$BODY" && break
    sleep 0.5
  done
  expect_status "the cancelled order is readable by its owner" 200
  expect_body "the buy reached CANCELLED (async release)" '"status":"CANCELLED"'
}

check_market_order_is_ioc() {
  local t1 order_id i
  t1=$(scripts/token.sh trader1)
  # a MARKET buy capped at 1 paise can never cross any realistic ask, so IOC cancels the whole order —
  # a deterministic exercise of the market-order path over the real stack (no resting order to race)
  call POST /api/v1/orders "$t1" '{"symbol":"ACME","side":"BUY","price":1,"quantity":1,"type":"MARKET"}'
  expect_status "a market buy is accepted" 201
  expect_body "the market buy is ACCEPTED" '"status":"ACCEPTED"'
  order_id=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' <<<"$BODY")
  # immediate-or-cancel is event-driven, so poll for the terminal CANCELLED state
  for i in $(seq 1 20); do
    call GET "/api/v1/orders/$order_id" "$t1"
    grep -qF '"status":"CANCELLED"' <<<"$BODY" && break
    sleep 0.5
  done
  expect_status "the market order is readable by its owner" 200
  expect_body "the unmarketable market order became CANCELLED (IOC)" '"status":"CANCELLED"'
}

RUN_CHECKS=(
  check_health_is_public
  check_unauthenticated_is_problem_json_401
  check_openapi_and_swagger_are_public
  check_prometheus_scrape_is_public
  check_order_fills_end_to_end
  check_cancel_releases_a_resting_order
  check_market_order_is_ioc
  check_risk_rejects_insufficient_cash
)

note "tradecore smoke suite → $APP_URL"
curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1 \
  || die "app not reachable at $APP_URL — start it first: scripts/up.sh && scripts/run.sh"

for check in "${RUN_CHECKS[@]}"; do
  printf '\n  %s\n' "$(tr '_' ' ' <<<"${check#check_}")"
  "$check"
done

printf '\n────────────────────────────\n'
if [[ $FAIL -eq 0 ]]; then
  printf '%s — %s checks passed\n' "$(green PASS)" "$PASS"
else
  printf '%s — %s passed, %s failed\n' "$(red FAIL)" "$PASS" "$FAIL"
fi
[[ $FAIL -eq 0 ]]
