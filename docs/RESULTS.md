# Results ledger

Every number that appears in the README is recorded here first: date, commit, machine,
command. Never edit an old row; add a new one.

Machine "laptop" = Windows 11, Temurin 21.0.13, the author's laptop.

| Date | Commit | Metric | Value | Machine | Command |
|---|---|---|---|---|---|
| 2026-11-12 | (uncommitted) | Tests | sf-core 41, sf-crawler 22, sf-indexer 93, sf-query 17, sf-api 11, ui 6; all passing | laptop | `.\gradlew.bat build`; `cd ui; npm test` |
| 2026-11-12 | (uncommitted) | Line coverage | core 97.9%, crawler 96.3%, indexer 98.6%, query 99.8%, api 97.3% | laptop | `.\gradlew.bat build` (JaCoCo XML) |
| 2026-11-12 | (uncommitted) | Demo index | 78 docs, 2,291 terms, 60,342 postings bytes, 117,841 raw text bytes (1.95x), built in 0.3 s | laptop | `:sf-indexer:run --args="--markdown docs/guide/parts ..."` |
| 2026-11-12 | (uncommitted) | Container memory | 157 MiB of 512 MiB after 200 searches; ready in ~20 s at 0.5 CPU | laptop, Docker 29.9 | `docker run -m 512m --cpus 0.5 searchforge:local` + 200 curl searches |
| 2026-11-12 | (uncommitted) | Rate limit | 40 parallel requests from one IP: 30 x 200, 10 x 429 | laptop, Docker 29.9 | `xargs -P 40 curl ...` |
| 2026-11-27 | fix/maven-mirror | Load, free-plan size | 100 users: p95 6,610 ms, p99 12,182 ms, 0% errors, CPU at limit, 192 MiB; startup 156 s | laptop, Docker 29.9, `--cpus 0.1 -m 512m` | `k6 run -e USERS=100 -e HOLD=2m loadtest/journey.js` + `loadtest/upstream_stub.py` |
| 2026-11-27 | fix/maven-mirror | Load, 1 CPU | 500 users: 105 req/s, p95 8 ms, p99 219 ms, 0% errors, 199 MiB | laptop, Docker 29.9, `--cpus 1 -m 2g` | same, `USERS=500 HOLD=3m` |
| 2026-11-27 | fix/maven-mirror | Load, 1 CPU | 1,000 users: 212 req/s, p95 6 ms, p99 16 ms, 0% errors, CPU 45%, 223 MiB | same | same, `USERS=1000 HOLD=3m` |
| 2026-11-27 | fix/maven-mirror | Load, 1 CPU | 1,500 users for 10 min: 354 req/s, p95 21 ms, p99 93 ms, 0% errors, CPU peak 103%, 298 MiB | same | same, `USERS=1500 HOLD=10m` |
| 2026-11-27 | fix/maven-mirror | Stress, 1 CPU | 2,000 users: 436 req/s, p95 19 ms, p99 73 ms, 0% errors, 340 MiB | same | same, `USERS=2000 HOLD=4m` |
| 2026-11-27 | fix/maven-mirror | Load, 1 CPU, cold cache | 1,500 users, every search new: 274 req/s, p95 2,259 ms, p99 3,952 ms, 1.39% errors (upstream limit of 64 calls in flight) | same | same, `COLD=1 USERS=1500 HOLD=5m` |

## Still to measure

Crawl size and pages/s, compression on a real corpus (target >= 3x), index build time for
50k docs, query/autocomplete/spelling latency (JMH), a 2-4 hour soak test, P@10 and nDCG@10 on 30 judged
queries, live hosting cost.
