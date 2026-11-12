# ADR-001: Host on Render's free web service, index inside the image

- **Status**: Accepted
- **Date**: 2026-11-12

## Context

SearchForge must be publicly reachable at zero cost, without a credit card, and with HTTPS.
The index is immutable once built, and the whole engine fits in 512 MB when postings are
memory-mapped (measured: 157 MiB after 200 searches on the demo index).

## Decision

- One Docker image holds the React UI, the Spring Boot API and exactly one index.
- The index comes from a GitHub Release (`INDEX_URL` build argument, default set in the
  Dockerfile through a PR) and is verified with `INDEX_SHA256` before use; without it the
  image ships a demo index built from the guide.
- Render builds the Dockerfile from `main` and deploys automatically. `render.yaml` describes
  the service (free plan, health check `/actuator/health`, `SPRING_PROFILES_ACTIVE=prod`).
- Behind Render's proxy, the client IP is the last `X-Forwarded-For` entry
  (`SEARCHFORGE_PROXY_HOPS=1`). Verify this after the first deploy (OPERATIONS.md).

## Consequences

- No servers, firewall, TLS certificates or domains to manage.
- 512 MB memory budget; the CI smoke test runs the image with `-m 512m` to catch regressions.
- A free service sleeps after 15 idle minutes; the first visit then takes about a minute.
  The UI shows a "waking up" message after 3 s.
- Changing or rolling back the index is a normal pull request (change the build arguments).
- Fallback hosts that run the same image: Koyeb, Google Cloud e2-micro (see the Go-Live plan).
