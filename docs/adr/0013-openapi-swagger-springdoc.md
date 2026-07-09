# ADR-0013: OpenAPI 3 + Swagger UI via springdoc

- Status: Accepted
- Date: 2026-07-09

## Context

The API (`/api/v1/orders`) had no machine-readable contract or interactive docs — a spec §8 gap
tracked in the README. We want OpenAPI 3 generation and Swagger UI without hand-maintaining a spec.

## Decisions

**springdoc-openapi `3.0.3` (the Spring Boot 4 line).** springdoc's `2.8.x` line targets Boot 3
(Framework 6); the `3.0.x` line is for Boot 4 — verified, not assumed: `springdoc-openapi:3.0.3`
declares `spring-boot-starter-parent:4.0.5` as its parent and depends on the Boot-4-modularized
artifacts (`spring-boot-actuator-autoconfigure`, `spring-boot-webmvc-test`, …). 3.0.3 is the latest
GA (verified 2026-07-09 via `repo1.maven.org` maven-metadata). Our app is Boot 4.1; 4.0→4.1 is a
minor, and an integration test (`OpenApiDocsIT`) confirms it auto-configures and scans the
(package-private) controllers at runtime. Only the `springdoc-openapi-starter-webmvc-ui` starter is
declared; it transitively brings the `-api` starter and the swagger-ui webjar.

**OpenAPI docs are an unauthenticated exception, consistent with the security invariant.**
`/v3/api-docs`, `/v3/api-docs.yaml`, and `/swagger-ui/**` are `permitAll` in `SecurityConfig` — the
project invariant already lists "OpenAPI docs" alongside health as the sanctioned unauthenticated
exceptions. A programmatic `OpenAPI` bean (`OpenApiConfig`) supplies the title/description and a
Bearer-JWT security scheme, so Swagger UI's **Authorize** control sends a Keycloak token and secured
endpoints are callable from the browser.

## Consequences

- Interactive Swagger UI at `/swagger-ui.html` and a machine-readable spec at `/v3/api-docs`,
  generated from the controllers (no hand-maintained document).
- One new compile dependency (springdoc), Boot-BOM-aligned except its own pinned version.
- The docs endpoints are publicly readable; that is intended per the invariant, but a non-local
  deploy that wants them locked down would move them behind auth (same as the `/actuator/prometheus`
  note in ADR-0002).
