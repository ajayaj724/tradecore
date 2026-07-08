# Plan 1B inputs — deferred findings from the final Phase 1A review

Source: final Phase 1A whole-branch review (branch `feat/phase1a-foundation`). These
findings were explicitly out of scope for the Phase 1A fix pass and are recorded here
verbatim so Plan 1B picks them up rather than losing them.

- `spring-boot-starter-web` → `spring-boot-starter-webmvc` rename.
- UTF-8 charset on `ProblemDetailsAuthHandlers` responses.
- Test pinning `/actuator/prometheus` public + 404-problem+json assertion.
- Controllers-never-touch-repositories + package-private-by-default ArchUnit rules.
- CPD RED-proof against real domain code.
- Named postgres volume (or fix `down.sh` message).
- CI top-level least-privilege permissions block.
- `.gitignore` root entry for `.superpowers/`.
