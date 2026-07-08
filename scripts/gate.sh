#!/usr/bin/env bash
# The full local quality gate — exactly what the tradecore-quality-gate skill requires
# before every commit. Fails loudly; never add skip flags here.
source "$(dirname "$0")/_common.sh"
cd "$(repo_root)"

note "spotless:apply (auto-format)"
mvn -q spotless:apply

note "mvn verify (full machine gate — format, Error Prone+NullAway, Checkstyle, tests, ITs, module verification, coverage)"
mvn verify

note "gate GREEN — review the staged diff against the quality checklist, then commit"
