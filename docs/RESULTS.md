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

## Still to measure

Crawl size and pages/s, compression on a real corpus (target >= 3x), index build time for
50k docs, query/autocomplete/spelling latency (JMH + k6), P@10 and nDCG@10 on 30 judged
queries, live hosting cost.
