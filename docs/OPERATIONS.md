# Operations runbook

Commands are PowerShell from the repo root. The service is one Docker image on Render
(ADR-001). Everything reaches production through a pull request with green CI.

## Deploy

Merge a pull request into `main`. CI (`build-and-test`, `ui`, `docker-build`) must be green;
Render then builds the Dockerfile and switches traffic when `/actuator/health` is UP.

Check after every deploy:

```powershell
$B = "https://<your-service>.onrender.com"
curl.exe "$B/actuator/health"                        # {"status":"UP",...}
curl.exe "$B/api/search?q=porter+stemmer"            # JSON with results (local index)
curl.exe "$B/api/web/info"                           # {"provider":"brave"} (or searxng/open)
curl.exe "$B/api/web?q=house"                        # live web results
curl.exe -s -o NUL -w "%{http_code}" "$B/actuator/prometheus"   # must print 404
```

## Live web results (ADR-003)

The provider is picked at startup and logged as `Live web search provider: <name>`.

| Want | Set on Render (Environment) |
|---|---|
| Google-like results (recommended) | `BRAVE_API_KEY` = key from https://api-dashboard.search.brave.com |
| Self-hosted metasearch | `SEARXNG_URL` = base URL of your SearXNG (needs `format: json`, see `searxng/settings.yml`) |
| No keys at all | nothing; Wikipedia, Hacker News, Openverse and Commons are used |

Rotate a Brave key: create a new key in the Brave dashboard, paste it into Render, let the
service redeploy, then delete the old key. Never commit keys; `.env` is git-ignored.

## Capacity (measured 2026-11-27, see RESULTS.md)

Load tests use `loadtest/journey.js` (people typing, searching, paging, opening images,
reading 5-15 s) against a local container and `loadtest/upstream_stub.py`. Never point them
at production or at the real search APIs.

| Render plan | What it handles |
|---|---|
| Free (0.1 CPU, 512 MB) | A demo. 100 active users already give p95 6.6 s; it also sleeps when idle. |
| 1 CPU (Standard) | 1,500-2,000 active users with p95 under 25 ms, as long as most searches repeat (cache hits). |

The real ceiling is the live-search provider, not this server: each new query costs one
upstream call. Brave's free plan allows about 1 call a second, so 1,500 users need a paid
Brave plan or your own SearXNG. The server allows 64 upstream calls at once; beyond that it
answers 502 "Web results are unavailable" within 2 s instead of piling up.
With 1,500 users all typing different queries, p95 was 2.3 s with 1.4% such errors.

## Roll back

Preferred: on GitHub, open the merged PR, click **Revert**, merge the revert PR.
Faster in an emergency: Render dashboard, service, **Events**, pick the last good deploy,
**Rollback** (if your plan offers it), then still revert the PR so `main` matches production.

## Refresh the index (each new crawl)

```powershell
$TS = Get-Date -Format "yyyyMMdd"
.\gradlew.bat :sf-crawler:run --args="--seeds data/seeds.txt --out data/crawl/$TS --max-pages 50000 --user-agent 'SearchForgeBot/1.0 (+https://github.com/Software-Cookie/SearchForge; <your email>)'"
.\gradlew.bat :sf-indexer:run --args="--in data/crawl/$TS --out data/index/$TS"

# Quality gate: must not be worse than the last row in docs/RESULTS.md
.\gradlew.bat :sf-bench:run --args="--index data/index/$TS --qrels docs/eval/qrels.tsv"

# Package, checksum, publish (asset name is always index.tgz; the tag carries the date)
tar -czf data\index.tgz -C "data\index\$TS" .
(Get-FileHash data\index.tgz -Algorithm SHA256).Hash.ToLower()
gh release create "index-$TS" data\index.tgz --latest=false --title "Index $TS" --notes "<docs, hosts, P@10, nDCG@10>"
```

Then point the image at it through a PR: in `Dockerfile`, set the defaults of the two
build arguments, `ARG INDEX_URL="https://github.com/Software-Cookie/SearchForge/releases/download/index-<TS>/index.tgz"`
and `ARG INDEX_SHA256="<hash printed above>"`. CI builds and smoke-tests that exact image;
merging deploys it. Rolling back the index = reverting that PR.

## Verify the client-IP setting (once, after the first deploy)

Send a few searches from your phone, then in Render, **Logs**, confirm rate limiting is per
visitor: hammering from one phone gives 429 while another device still gets 200. If everyone
shares one bucket, adjust `SEARCHFORGE_PROXY_HOPS` (environment variable) and record the
finding in ADR-001.

## Monitoring

- UptimeRobot (free): HTTP monitor on `/actuator/health` every 5 minutes, email alerts.
- Render, **Metrics** and **Logs**: memory must stay well under 512 MB; look weekly.
- GitHub, **Security**: Dependabot alerts on; merge its PRs through the normal flow.

## Backups

The Releases are the backups of every index. Keep every `index-*` release. Once a month,
copy `data\index-*.tgz` and `data\seeds.txt` to an external drive or cloud storage. Code and
history live in git.

## If something breaks

| Symptom | Likely cause | Fix |
|---|---|---|
| Deploy never turns healthy | Index missing or corrupt | Check build logs for the SHA-256 or download step; restore the previous `INDEX_*` values |
| Exit 137 / out of memory | Over 512 MB | Run `docker run -m 512m` locally, lower `MaxRAMPercentage`, reduce `cache-size` |
| Everyone gets 429 at once | Wrong proxy hop count | Adjust `SEARCHFORGE_PROXY_HOPS` |
| First visit takes a minute | Free instance was asleep | Expected; optional 5-minute uptime ping |
| A site owner complains | Crawl too aggressive or unwanted | Remove the host from seeds the same day, rebuild the index |
| "Web results are unavailable" | Upstream down, key wrong, or quota used up | Check logs for `Live search source failed`; check the key and quota in the Brave dashboard |
| SearXNG returns 0 results | Its engines are blocking the server's IP | Edit `keep_only` in `searxng/settings.yml`, restart; or switch to Brave |
