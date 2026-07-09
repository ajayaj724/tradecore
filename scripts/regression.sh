#!/usr/bin/env bash
# The single command to run every time — the full automated regression:
#
#   1. Quality gate + in-process suite  (scripts/gate.sh → mvn verify):
#      Spotless, Error Prone/NullAway, Checkstyle, PMD, ArchUnit + ApplicationModules.verify(),
#      every unit + Testcontainers integration test, the jqwik engine property test, 80% coverage.
#   2. End-to-end smoke suite  (scripts/smoke.sh): black-box HTTP assertions against the live app.
#      Runs automatically when the app is already up, or on demand with --e2e.
#
# The perf suites (Gatling load/SLOs — `mvn -Pgatling ...`; JMH engine benchmarks — `mvn -Pjmh ...`)
# are separate and intentionally not part of every run; see the README.
#
# Usage:  scripts/regression.sh          # gate; + smoke if the app happens to be running
#         scripts/regression.sh --e2e    # gate; require the app to be up and run smoke too
source "$(dirname "$0")/_common.sh"
here="$(dirname "$0")"

want_e2e=0
[[ "${1:-}" == "--e2e" ]] && want_e2e=1

note "regression 1/2: quality gate + full in-process suite (mvn verify)"
"$here/gate.sh" || die "regression FAILED at the quality gate"

app_up() { curl -sf "$APP_URL/actuator/health" >/dev/null 2>&1; }

if [[ $want_e2e -eq 1 ]] && ! app_up; then
  die "--e2e requested but the app is not reachable at $APP_URL — start it: scripts/up.sh && scripts/run.sh"
fi

if [[ $want_e2e -eq 1 ]] || app_up; then
  note "regression 2/2: end-to-end smoke suite (live app)"
  "$here/smoke.sh" || die "regression FAILED at the smoke suite"
else
  note "regression 2/2: skipped e2e smoke — app not running (start it and pass --e2e to include it)"
fi

note "regression PASSED"
