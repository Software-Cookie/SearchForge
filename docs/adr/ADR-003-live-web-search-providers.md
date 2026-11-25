# ADR-003: Live internet results through pluggable search providers

**Status:** accepted, 2026-11-26

## Context

The public UI searched only the demo index, which is built from this repository's own guide.
Users expect a search box to return the live web: for "house" they expect blogs, real-estate
sites, articles and photos of houses. Crawling and indexing the open web to that standard is
out of reach for a free single-instance deployment (ADR-001), so the page needs results from
an existing web index.

## Decision

`sf-api` gets a `websearch` package with one interface, `WebSearchProvider`, and three
implementations. The provider is chosen at startup (`searchforge.web.provider=auto`):

| Order | Provider | Needs | What users get |
|---|---|---|---|
| 1 | Brave Search API | `BRAVE_API_KEY` | Google-like web pages, blogs, news, favicons, thumbnails, images |
| 2 | SearXNG (self-hosted) | `SEARXNG_URL` | Merged results from its working engines (Google CSE for web; Bing Images, Commons for images) |
| 3 | Open sources | nothing | Wikipedia articles, sites linked from Hacker News, Openverse and Wikimedia Commons images |

The UI calls `/api/web` (web or images, pages 1-10) and `/api/web/suggest` (Wikipedia
title search). The crawler, indexer and `/api/search` stay as they were; they remain the
engineering core of the project and can be pointed at a real crawl later.

## Consequences

- Upstream HTML is never sent to the browser: snippets are parsed with jsoup into plain
  text segments, and only `http(s)` URLs with a host are passed on.
- The CSP allows `img-src https:` so thumbnails load from the sites that host them; every
  image is requested with `referrerpolicy="no-referrer"`.
- Each answer is cached for 15 minutes (Caffeine) to protect upstream quotas.
- Upstream failures return 502 with a friendly message; in open mode one failed source does
  not fail the search.
- Running SearXNG means following the terms of the engines it queries; keep it private
  (bound to localhost or a private network) and low volume. Brave is the recommended
  production choice because it is an official API with clear terms.
