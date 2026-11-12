# ADR-002: No PostgreSQL or Redis in v1

- **Status**: Accepted
- **Date**: 2026-11-12

## Context

Earlier drafts listed PostgreSQL (page catalog) and Redis (query cache, rate limiting).
Both cost money or memory on free hosting, and both add failure modes.

## Decision

- Crawled pages are stored in an append-only, checksummed log (`pages.bin`), which the
  crawler can resume from after a crash.
- The index is a set of immutable files (dictionary, postings, documents, norms, vocabulary).
- Result caching and per-IP rate limiting use Caffeine inside the single API process.

## Consequences

- Nothing to provision; the whole service is one container.
- Rate-limit buckets and the result cache reset on restart. That is acceptable for one
  instance.
- Running several instances would need a shared rate limiter (for example Redis or the
  host's edge rate limiting). Revisit this decision then.
