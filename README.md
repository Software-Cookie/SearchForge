# SearchForge

A web search engine written from first principles in Java 21: a polite crawler, a compressed
positional inverted index built with external merge sort, BM25F ranking with a PageRank prior,
autocomplete, spelling correction, and a React UI. No search libraries (no Lucene, no
Elasticsearch).

**Live demo:** not deployed yet. See `docs/OPERATIONS.md` and the Go-Live plan.
Free hosting note: the first visit after 15 idle minutes takes about a minute to wake the server.

## What is in the code

| Module | What it does |
|---|---|
| `sf-core` | MurmurHash3 (x64 128), Bloom filter, SimHash near-duplicate index, URL normalizer, VByte + d-gap codec, crash-safe page store (CRC32 records), PageRank power iteration |
| `sf-crawler` | RFC 9309 robots.txt parser (longest match, wildcards, `$`), per-host politeness frontier (min-heap of host ready times, one request in flight per host), virtual-thread workers, SSRF guard, redirect handling, `noindex`/`nofollow`, resumable crawls |
| `sf-indexer` | Unicode tokenizer, stopwords, Porter stemmer (1980), memory-bounded postings buffer that spills sorted runs and k-way merges them, VByte-compressed postings with positions, memory-mapped reader |
| `sf-query` | Query syntax (AND, `OR`, `"phrases"`, `-exclude`), galloping intersection, BM25F (title 3x, headings 2x, body 1x) + PageRank prior, bounded top-K heap, snippet windows, top-K autocomplete trie, BK-tree spelling, deadline checks |
| `sf-api` | Spring Boot 4.1: `/api/search`, `/api/suggest`, `/api/stats`, Caffeine result cache, per-IP token bucket (proxy-aware), security headers + CSP, RFC 9457 errors, Micrometer metrics |
| `ui` | React 19 + TypeScript + Vite: autocomplete combobox, live "how your query is read" line, highlighted snippets, did-you-mean, paging, light/dark themes |
| `sf-bench` | JMH latency benchmarks and a P@10 / nDCG@10 relevance evaluator |

## Targets and measured results

Numbers appear here only after a reproducible run. History and commands: [docs/RESULTS.md](docs/RESULTS.md).

| Requirement | Target | Measured |
|---|---|---|
| Crawl corpus size | >= 50,000 docs | not yet (demo index: 78 sections of the build guide) |
| Politeness | 1 req / 2 s / host | enforced in code and tests; not yet measured on a real crawl |
| Index compression | >= 3x vs raw text | not yet on a real corpus (demo: 1.95x incl. positions) |
| Index build time (50k docs) | < 120 s | not yet |
| Query latency p50 / p95 / p99 | < 30 / 100 / 150 ms | not yet |
| Autocomplete p99 | < 5 ms | not yet |
| Spell correction p99 | < 15 ms | not yet |
| Precision@10 / nDCG@10 | >= 0.70 / 0.75 | not yet |
| Line coverage | >= 80% per module | 96-99% per module (enforced by the build) |
| Memory in a 512 MB container | < 512 MB | 157 MiB after 200 searches (demo index) |
| Hosting cost | $0 / month | not yet deployed |

## Run locally

Requirements: JDK 21, Node 24. Windows PowerShell shown; use `./gradlew` on macOS/Linux.

```powershell
# 1. Build an offline demo index from this repo's own guide (no crawling needed)
.\gradlew.bat :sf-indexer:run --args="--markdown docs/guide/parts --base-url https://github.com/Software-Cookie/SearchForge/blob/main/docs/guide/parts/ --out data/index/demo"

# 2. Build the UI and start the API (serves the UI too) on http://localhost:8080
cd ui; npm ci; npm run build; cd ..
.\gradlew.bat :sf-api:bootRun
```

Port 8080 taken? `$env:PORT = "8090"` before `bootRun`. For UI development with hot reload,
run `npm run dev` in `ui/` (set `$env:SF_API = "http://localhost:8090"` if you changed the port).

Crawl real pages (only sites whose robots.txt and terms allow it):

```powershell
Copy-Item seeds\seeds.example.txt data\seeds.txt   # edit it first
.\gradlew.bat :sf-crawler:run --args="--seeds data/seeds.txt --out data/crawl/run1 --max-pages 5000 --user-agent 'SearchForgeBot/1.0 (+https://github.com/Software-Cookie/SearchForge; you@example.com)'"
.\gradlew.bat :sf-indexer:run --args="--in data/crawl/run1 --out data/index/run1"
$env:SEARCHFORGE_INDEX_DIR = "data/index/run1"; .\gradlew.bat :sf-api:bootRun
```

Production image (the same one CI smoke-tests):

```powershell
docker build -t searchforge .
docker run --rm -p 8080:8080 -m 512m --cpus 0.5 searchforge
```

## Search syntax

`java streams` both words, `java OR kotlin` either, `"stream api"` exact phrase,
`java -android` leave a word out.

## Quality gates

`.\gradlew.bat build` runs every test (JUnit 5/6 + jqwik property tests), Checkstyle, and a
JaCoCo gate of 80% line coverage per module. CI also runs the UI type check, tests, build and
`npm audit`, then builds the Docker image and smoke-tests it with 512 MB of memory.

## Design decisions

- [ADR-000](docs/adr/ADR-000-scope-corpus-ethics-user-agent.md): crawl scope, ethics, User-Agent
- [ADR-001](docs/adr/ADR-001-hosting-render-free.md): free hosting on Render, index inside the image
- [ADR-002](docs/adr/ADR-002-no-database-in-v1.md): no PostgreSQL or Redis in v1
- [Architecture](docs/architecture.md), [Operations](docs/OPERATIONS.md), [Security](SECURITY.md)
- Theory for every algorithm: [docs/guide/](docs/guide/)
