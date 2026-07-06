---
name: tradecore-quality-gate
description: Use before every git commit in the tradecore repo — including "quick" commits, end-of-session wrap-ups, commits where mvn verify is already green, and commits you plan to "let CI check." Also use when tempted to run scoped tests instead of the full gate, to add any skip/quiet flag to a Maven command, or to commit code that works but hasn't been reviewed for quality.
metadata:
  author: ajayaj724
  version: "2.1"
---

# tradecore Quality Gate

## Overview

The gate runs **locally, before every commit**. CI is the backstop for environment drift —
it is never a substitute for the local gate. A commit exists only after a green gate on the
exact content being committed. The gate has two halves: a **judgment review** (what machines
cannot check) and the **machine gate** (what they can). Green machines with unreviewed
quality is a red gate.

## The Gate (in order)

**1. Diff review** — `git status` && `git diff --staged`: forgotten files, debug leftovers,
stray TODOs. Stage what belongs, with explicit paths.

**2. Quality review — the judgment gate.** Read the staged diff as a reviewer, not the
author. Any hit below blocks the commit until fixed (fix now — this review is free; `verify`
costs minutes, so quality comes first):

- **Silent failure:** no catch-log-continue, ever — in an `@ApplicationModuleListener` a
  swallowed exception marks the publication complete and the update is silently lost. Errors
  propagate (the registry redelivers) or map to Problem Details at the API edge.
- **Decomposition:** a method doing more than one job, or too long to read on one screen
  (~30 lines), gets extracted. A diff mixing unrelated concerns becomes separate commits.
- **Test strength:** every new test would fail if the behavior broke. Execution-only tests
  (`assertDoesNotThrow`, assertion-free calls) don't count as tests. Every new/changed
  listener has a duplicate-delivery test asserting a single effect.
- **API altitude:** new public types/methods in a module — could they be package-private?
  Smallest surface wins; Modulith treats public as contract.
- **Dead weight:** no commented-out code ("keeping for reference" — git is the reference),
  leftover debug logging, unused imports, or TODO without an issue link.
- **Domain integrity:** money stays in `BIGINT` minor units end-to-end (weighted averages
  and P&L math are where division sneaks in); state changes write audit records; new
  endpoints carry authorization and Problem Details.
- **Dependency currency:** any new or changed version pin is verified against the official
  source *at commit time* — `repo1.maven.org` maven-metadata.xml or official release notes
  (Context7 and search indexes can lag) — never recalled from memory. Latest stable GA only;
  milestones/RCs require an ADR. Never pin what the Boot BOM manages; an unmanaged addition
  carries OWASP/Trivy evidence and a PR justification.

**3.** `mvn spotless:apply` — auto-fix formatting; stage any files it changed.

**4.** `mvn verify` — the machine gate: Error Prone, Checkstyle (method length, cyclomatic
complexity), unit + Testcontainers ITs, `ApplicationModules.verify()`, JaCoCo 80% line
coverage. **No scoping, no skip flags.**

**5.** Green? Commit (conventional message). Red? Fix and rerun from step 3. Never commit red.

**Conditionals (observable predicates):**
- If `pom.xml` does not exist yet (pre-scaffold phase): only `docs:`/`ci:`/`build:` commits
  are permitted; steps 3–4 do not exist yet — say so in the commit body.
- If `git diff --staged --name-only` lists only `docs/**` and `*.md` paths: steps 3–4 do not
  apply; steps 1–2 still do (dead weight and honesty apply to docs too).
- If Docker isn't running, Testcontainers ITs fail: start Docker and rerun. An environment
  problem is fixed, not skipped past.

## Forbidden Flags

Never in the gate: `-DskipTests`, `-DskipITs`, `-Dspotless.check.skip`, `-Djacoco.skip`,
`-Dcheckstyle.skip`, `-DfailIfNoTests=false`, `--fail-never`, `-Dmaven.test.failure.ignore`.
A flag that hides a failure is a red gate you chose not to see.

## Rationalization Table

| Excuse (verbatim from baseline testing) | Reality |
|---|---|
| "I'd let CI carry the full suite — this is a local commit, not a push" | CI catches environment drift, not skipped discipline. A red pipeline from a skippable local failure costs a full round-trip. |
| "`mvn verify` is green, so it's fine" | Green machines prove what machines can measure. Coverage cannot measure assertion strength; Error Prone cannot see a method doing six jobs. Step 2 exists for exactly this gap. |
| "A local commit is reversible" | The habit is the point. Gates skipped when reversible get skipped under deadline pressure too. |
| "The human already reviewed the work" | Review checks intent; the gate checks correctness. Neither replaces the other. |
| "I ran the scoped tests all session" | Scoped tests cannot catch cross-module breakage — that is precisely what `ApplicationModules.verify()` and the ITs exist for. |
| "It works — I'll refactor/decompose later" | Later never has a greener window than now: tests green, context loaded, diff small. Extract now. |
| "It compiles and the smoke test passes, the version is fine" | A stale pin can be green today while carrying known CVEs or a wrong-generation artifact (a Boot-3-line starter on a Boot 4 app). Currency is verified against the registry, not inferred from green. |
| "Full verify takes too long this late" | tradecore is one Maven module, built to keep `verify` in single-digit minutes. If it's slower, fix the build — file it, don't skip it. |

## Red Flags — STOP

- About to type `git commit` and `mvn verify` hasn't run green on the current content
- About to type `git commit` and you haven't read the staged diff against step 2's checklist
- Any forbidden flag in your command line
- "Just this once" / "I'll run it in the morning" / "wrap it up quickly"

All of these mean: run the gate now, commit after green.
