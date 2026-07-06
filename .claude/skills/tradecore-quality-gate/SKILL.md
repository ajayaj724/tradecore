---
name: tradecore-quality-gate
description: Use before every git commit in the tradecore repo — including "quick" commits, end-of-session wrap-ups, and commits you plan to "let CI check." Also use when tempted to run scoped tests instead of the full gate, or to add any skip/quiet flag to a Maven command.
---

# tradecore Quality Gate

## Overview

The gate runs **locally, before every commit**. CI is the backstop for environment drift —
it is never a substitute for the local gate. A commit exists only after a green gate on the
exact content being committed.

## The Gate (in order)

1. `git status` && `git diff --staged` — review the actual diff: forgotten files, debug
   leftovers, stray TODOs. Stage what belongs, with explicit paths.
2. `mvn spotless:apply` — auto-fix formatting; stage any files it changed.
3. `mvn verify` — the full gate: Error Prone, unit + Testcontainers ITs,
   `ApplicationModules.verify()`, JaCoCo 80% line coverage. **No scoping, no skip flags.**
4. Green? Commit (conventional message). Red? Fix and rerun from step 2. Never commit red.

**Conditionals (observable predicates):**
- If `pom.xml` does not exist yet (pre-scaffold phase): only `docs:`/`ci:`/`build:` commits are
  permitted, and steps 2–3 do not exist yet — say so in the commit body.
- If `git diff --staged --name-only` lists only `docs/**` and `*.md` paths: steps 2–3 do not
  apply; step 1 still does.
- If Docker isn't running, Testcontainers ITs fail: start Docker and rerun. An environment
  problem is fixed, not skipped past.

## Forbidden Flags

Never in the gate: `-DskipTests`, `-DskipITs`, `-Dspotless.check.skip`, `-Djacoco.skip`,
`-DfailIfNoTests=false`, `--fail-never`, `-Dmaven.test.failure.ignore`. A flag that hides a
failure is a red gate you chose not to see.

## Rationalization Table

| Excuse (verbatim from baseline testing) | Reality |
|---|---|
| "I'd let CI carry the full suite — this is a local commit, not a push" | CI catches environment drift, not skipped discipline. A red pipeline from a skippable local failure costs a full round-trip. |
| "A local commit is reversible" | The habit is the point. Gates skipped when reversible get skipped under deadline pressure too. |
| "The human already reviewed the work" | Review checks intent; the gate checks correctness. Neither replaces the other. |
| "I ran the scoped tests all session" | Scoped tests cannot catch cross-module breakage — that is precisely what `ApplicationModules.verify()` and the ITs exist for. |
| "Full verify takes too long this late" | tradecore is one Maven module, built to keep `verify` in single-digit minutes. If it's slower, fix the build — file it, don't skip it. |

## Red Flags — STOP

- About to type `git commit` and `mvn verify` hasn't run green on the current content
- Any forbidden flag in your command line
- "Just this once" / "I'll run it in the morning" / "wrap it up quickly"

All of these mean: run the gate now, commit after green.
