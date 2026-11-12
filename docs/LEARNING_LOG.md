# SearchForge Engineering & Learning Log

This log documents the phase-by-phase implementation, algorithmic trade-offs, test results, and Staff-Engineer design insights throughout the SearchForge project.

---

## Phase 0: Project Setup, Skeleton, CI/CD, and Crawling Policy (ADR-000)

### What Was Done
1. **Multi-Module Gradle Kotlin DSL Architecture**: Configured 6 decoupled modules (`sf-core`, `sf-crawler`, `sf-indexer`, `sf-query`, `sf-api`, `sf-bench`).
2. **Code Quality & Style**: Added `.editorconfig`, Checkstyle configuration (`config/checkstyle/checkstyle.xml`), and strict compilation flags (`-Xlint:all`, `-parameters`).
3. **CI/CD Pipeline**: Configured GitHub Actions `.github/workflows/ci.yml` running test matrix on Java 21, Checkstyle validation, and JaCoCo coverage reports.
4. **ADR-000 (Ethics & User-Agent)**: Established strict RFC 9309 compliance, 2,000 ms host delay, 4-hop depth bounds, and transparent bot identification (`SearchForgeBot/1.0`).
5. **Architecture Specifications**: Documented end-to-end data flow with Mermaid diagrams in `docs/architecture.md`.

### Verification & Test Results
- `.\gradlew.bat projects`: Verified all 6 modules loaded cleanly.
- `.\gradlew.bat build`: Successful baseline build across all modules with zero compilation errors.

---

## Phases 1-8: Working engine, API and UI (2026-11-12)

### What was done
- Crawler: RFC 9309 robots.txt, per-host politeness frontier, SSRF guard, resumable page store.
- Indexer: Porter stemmer, spill-and-merge index construction, VByte postings with positions,
  memory-mapped reader, Markdown importer for an offline demo index.
- Query: AND / OR / phrases / exclusions, galloping intersection, BM25F + PageRank prior,
  top-K heap, snippets, autocomplete trie, BK-tree spelling, per-query deadline.
- API on Spring Boot 4.1 with cache, rate limiting, security headers; React 19 UI; Docker image
  verified under 512 MB; CI builds and smoke-tests the image.

### Hardest bugs and fixes
- Spring Boot failed to start: every module pinned `logback-classic` 1.5.12 while Boot 4
  manages a newer `logback-core`. Fix: libraries depend on `slf4j-api` only; logback is added
  at runtime only where a program runs.
- SimHash on a test page made of 8 repeated words flagged unrelated pages: TF weights made
  many bits hinge on tiny features. Real pages have hundreds of distinct words; tests now use
  realistic text and the fingerprint uses TF-weighted words (Manku et al. 2007).
- Inside Docker Desktop on Windows, Java could not reach GitHub's release-asset hosts while
  curl could, so the Gradle wrapper download failed. Fix: build with the official
  `gradle:8.14.3-jdk21` image.

### What I'd do differently
- Write the multi-run vs single-run equivalence test (byte-identical output) first; it caught
  nothing this time but made refactoring the merger safe.

### Numbers
See docs/RESULTS.md (2026-11-12 rows).
