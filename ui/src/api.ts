// Typed client for the SearchForge live web search API (/api/web).
// Response shapes mirror WebSearchResponse.java.

export type Segment = { text: string; hit: boolean };

export type WebHit = {
  url: string;
  title: string;
  site: string;
  favicon: string | null;
  thumbnail: string | null;
  age: string;
  snippet: Segment[];
};

export type ImageHit = {
  title: string;
  imageUrl: string;
  thumbnailUrl: string;
  pageUrl: string | null;
  site: string;
  width: number;
  height: number;
};

export type SearchType = "web" | "images";

export type WebResponse = {
  query: string;
  type: SearchType;
  page: number;
  tookMs: number;
  source: string;
  hasMore: boolean;
  didYouMean: string | null;
  results: WebHit[];
  images: ImageHit[];
};

export type ErrorKind = "rate-limited" | "upstream" | "bad-query" | "server" | "network";

export class SearchError extends Error {
  constructor(public kind: ErrorKind) {
    super(kind);
  }
}

export const MAX_QUERY_LENGTH = 200;
export const MAX_PAGE = 10;

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, { signal, headers: { Accept: "application/json" } });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new SearchError("network");
  }
  if (res.status === 429) throw new SearchError("rate-limited");
  if (res.status === 502 || res.status === 503) throw new SearchError("upstream");
  if (res.status === 400) throw new SearchError("bad-query");
  if (!res.ok) throw new SearchError("server");
  return (await res.json()) as T;
}

export function webSearch(q: string, type: SearchType, page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ q, type, page: String(page) });
  return getJson<WebResponse>(`/api/web?${params}`, signal);
}

export async function suggest(q: string, signal?: AbortSignal): Promise<string[]> {
  const data = await getJson<{ suggestions: string[] }>(
    `/api/web/suggest?${new URLSearchParams({ q })}`,
    signal,
  );
  return data.suggestions;
}

export const SOURCE_NAMES: Record<string, string> = {
  brave: "Brave Search",
  searxng: "SearXNG",
  open: "Wikipedia, Hacker News, Openverse and Wikimedia Commons",
};
