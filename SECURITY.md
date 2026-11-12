# Security

## Reporting a problem

Please do not open a public issue for a vulnerability. Use GitHub's
"Report a vulnerability" button (Security tab, private vulnerability reporting) on this
repository. Expect a reply within 7 days.

## What the service does to protect itself and its visitors

- **No secrets**: the app needs no passwords, tokens or keys. Keep it that way; `.env` files
  are ignored by git.
- **Input limits**: queries 1-200 characters, `page` 1-100, `size` 1-50, a 500 ms deadline per
  query (503 when exceeded), and a per-IP token bucket (10/s, burst 20, 429 + `Retry-After`).
- **Client IP**: only the configured number of trusted proxy hops in `X-Forwarded-For` is
  used, so visitors cannot dodge the rate limit by spoofing the header.
- **Browser hardening**: strict Content-Security-Policy (`'self'` only, no inline scripts,
  `frame-ancestors 'none'`), `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
  `Permissions-Policy`, `Cross-Origin-Opener-Policy`, HSTS over HTTPS.
- **No HTML injection**: snippets are plain-text segments rendered as text nodes; result links
  are rendered only for `http(s)` URLs.
- **Errors**: RFC 9457 problem JSON without stack traces or internals.
- **Metrics**: `/actuator/prometheus` is not exposed in the `prod` profile; only
  `/actuator/health` is public.
- **Container**: runs as a non-root user (uid 10001) on a JRE-only image; a downloaded index
  must match `INDEX_SHA256`.
- **Crawler**: refuses hosts that resolve to private, loopback, link-local or CGNAT addresses
  (SSRF guard), honors robots.txt, identifies itself, and never goes below 2 s per host.
- **Supply chain**: Dependabot for Gradle, npm, GitHub Actions and Docker; CI fails on
  high-severity `npm audit` findings.
