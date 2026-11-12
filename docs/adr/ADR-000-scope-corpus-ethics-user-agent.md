# ADR-000: Scope, Corpus Selection, Crawling Ethics, and User-Agent Policy

- **Status**: Accepted
- **Date**: 2026-10-25
- **Deciders**: Staff Engineer (Search Infrastructure)

## Context & Problem Statement

Autonomous web crawling consumes external network bandwidth, CPU, and memory on target servers. Poorly designed crawlers risk triggering Denial-of-Service (DoS) conditions, crawling private or sensitive user endpoints, following infinite dynamic trap URLs (e.g. `/calendar?year=2026&month=13`), and violating international copyright laws and website Terms of Service.

SearchForge requires a well-defined crawling scope, corpus boundary, and strict ethical crawling policy to safely ingest $\ge 50,000$ pages.

## Decision Drivers

1. **Safety & Zero Disruption**: Never overload any target server or cause degradation to target services.
2. **RFC Compliance**: Comply strictly with RFC 9309 (Robots Exclusion Protocol).
3. **Transparent Identification**: Clearly identify the crawler, purpose, and contact operator in all outbound HTTP requests.
4. **Reproducibility**: Provide a consistent corpus baseline (selected Wikipedia technical subtrees and open documentation sites).

## Considered Options

- **Option 1**: Unconstrained open-web crawl with no host caps. *(Rejected: High risk of hitting crawler traps, infinite loops, and triggering IP blocks).*
- **Option 2**: Polite bounded crawl with explicit seed lists, per-host delays, domain caps, and RFC 9309 parser. *(Accepted)*

## Decision & Policy Specifications

### 1. Identifying User-Agent
Every HTTP request initiated by `sf-crawler` MUST send the standard header:
```http
User-Agent: SearchForgeBot/1.0 (+https://github.com/Software-Cookie/searchforge; sivaprasadpearlapaati@gmail.com)
Accept: text/html,application/xhtml+xml
```

### 2. Robots.txt Compliance (RFC 9309)
- Before fetching any URL on host `H`, `sf-crawler` checks its in-memory `RobotsTxtParser` cache.
- If not cached, it fetches `http(s)://H/robots.txt`.
- Directives for `User-agent: SearchForgeBot` and `User-agent: *` are strictly honored.
- Per RFC 9309, if an `Allow` and `Disallow` rule match with equal length, `Allow` takes precedence.

### 3. Politeness & Rate Limits
- Minimum host politeness delay: **2,000 ms** between successive requests to the same hostname.
- If `robots.txt` specifies `Crawl-delay: X`, the delay is set to $\max(2000, X \times 1000)\text{ ms}$.
- Concurrency across *different* hostnames is unbounded (powered by Java 21 Virtual Threads).

### 4. Corpus Boundary & Crawl Bounds
- **Maximum Depth**: 4 hops from seed URLs.
- **Per-Domain Cap**: Maximum 1,000 pages per domain to avoid crawler traps.
- **Maximum Document Size**: 2.0 MB. Payloads exceeding 2 MB are truncated or dropped.
- **MIME Type Filter**: Only `Content-Type: text/html` is retained; binaries, images, and PDFs are skipped immediately.

## Consequences

- **Positive**: SearchForge behaves as an exemplary web citizen with zero risk of IP blacklisting or server disruption.
- **Negative**: Politeness delays mean single-domain crawls are rate-limited to 0.5 req/s. High overall throughput ($\ge 45\text{ pages/sec}$) is achieved by multiplexing across hundreds of unique hostnames in parallel.
