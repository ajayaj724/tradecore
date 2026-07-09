# Security Policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Report privately via GitHub:
**Security → Advisories → Report a vulnerability** on this repository (GitHub Private Vulnerability
Reporting). Include reproduction steps and affected versions. You'll get an acknowledgement and a
fix timeline; please allow a reasonable disclosure window before any public discussion.

## Supported versions

This is a portfolio/reference project under active development; only the tip of `main` is supported.
There are no released versions with backported fixes.

## Known non-issues (please don't report these)

- **Demo Keycloak credentials** (`trader1/demo`, `admin/admin`, the local Postgres password, etc.)
  exist **only** in the local `docker compose` stack, are never used outside it, and are intentionally
  allowlisted in [`.gitleaks.toml`](.gitleaks.toml) as non-secrets. Never point the compose stack at a
  non-local network.
- **`/actuator/prometheus` is unauthenticated — locally only**, a deliberate scrape convenience
  documented in [ADR-0002](docs/adr/0002-actuator-prometheus-scrape-exposure.md). It must be locked
  down before any non-local deployment; that is tracked, not a finding.

## Security posture

- **AuthN/Z:** OAuth2 resource server validating Keycloak-issued JWTs; roles (`TRADER`, `OPS`, `ADMIN`)
  from the `realm_access.roles` claim. Every endpoint requires authentication except health, the
  OpenAPI docs, and (locally only) the Prometheus scrape. Auth failures render as RFC 9457
  `application/problem+json`.
- **Per-user rate limiting** on `/api/v1/**` (Bucket4j); over-limit requests get a `429` Problem Detail.
- **Automated scanning in CI:** CodeQL (Java), Semgrep (`p/java`, `p/owasp-top-ten`), gitleaks (secrets),
  Trivy (image + dependency CVEs), and OWASP Dependency-Check (enabled when an `NVD_API_KEY` repository
  secret is configured). GitHub Actions are pinned to commit SHAs and kept current by Dependabot.
- **Money safety:** all monetary values are integer minor units end-to-end — no floating point touches
  money.
