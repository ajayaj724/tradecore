---
name: tradecore-feature-debrief
description: Owner knowledge-transfer debrief for tradecore. Use when a feature, endpoint, migration, or module reaches done (tests green, committed), before planning or starting the next task — especially when the owner says "what's next", "continue", or moves on without asking about the finished work. Also use when the owner asks how or why something in the codebase was built.
metadata:
  author: ajayaj724
  version: "1.0"
---

# tradecore Feature Debrief

## Overview

The owner presents this project in senior/staff interviews. Code the owner cannot explain is
a liability, not an asset. Therefore: every completed feature is followed by a debrief,
delivered **before** any next-task planning — the reply to "what's next?" begins with the
debrief of what just finished.

## The Debrief (required sections, in order)

**1. What shipped** — 2–3 plain-language sentences. What the feature does and what a user of
the system gains. No file paths here; a recruiter should understand this part.

**2. How it works** — walk the code path end-to-end in execution order, each step anchored
with a clickable `file:line` reference. Cover: entry point → validation → persistence →
events → response. Short enough to read in two minutes.

**3. Why this way** — every significant decision as a pair: *chosen approach* vs *rejected
alternative*, with the reason. ("Unique constraint + insert-first, not check-then-insert,
because two concurrent replays would both pass the check.") This section is the owner's
interview ammunition — write it as defensible engineering judgment, not as changelog.

**4. Interview drill** — exactly 3 questions an interviewer would ask about *this specific
code*, posed to the owner. At least one must probe a failure mode ("what happens if the app
crashes between X and Y?"). Do not include the answers; invite the owner to attempt them and
offer model answers on request.

**5. Try it** — one copy-pasteable command (curl / test invocation / Grafana panel) the owner
can run to watch the feature behave, including the interesting case (the replay, the
rejection, the race), not just the happy path.

After section 5, next-task planning may begin.

## Edge cases

- Several small features completed in one stretch: one combined debrief, sections 3 and 4
  still per-decision — never skip "why this way" because changes were small.
- Owner explicitly answers the drill questions: check the answers honestly; correct wrong
  ones with the code open (`file:line`), not from memory.
- Pure refactor or docs change: sections 1–3 in brief; drill and try-it may be omitted only
  if no runtime behavior changed.
