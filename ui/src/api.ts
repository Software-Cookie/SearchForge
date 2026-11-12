// Typed client for the SearchForge API. Response shapes mirror SearchResult.java.

export type Segment = { text: string; hit: boolean };

export type Hit = { url: string; title: string; score: number; snippet: Segment[] };

export type SearchResponse = {
  query: string;
  totalHits: number;
  tookMs: number;
  page: number;
  size: number;
  results: Hit[];
  didYouMean: string | null;
};

export type Stats = {
  documents: number;
  terms: number;
  vocabulary: number;
  indexCreatedAt: string;
};

export type ErrorKind = "rate-limited" | "too-slow" | "bad-query" | "server" | "network";

export class SearchError extends Error {
  constructor(public kind: ErrorKind) {
    super(kind);
  }
}

export const PAGE_SIZE = 10;
export const MAX_QUERY_LENGTH = 200;

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, { signal, headers: { Accept: "application/json" } });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new SearchError("network");
  }
  if (res.status === 429) throw new SearchError("rate-limited");
  if (res.status === 503) throw new SearchError("too-slow");
  if (res.status === 400) throw new SearchError("bad-query");
  if (!res.ok) throw new SearchError("server");
  return (await res.json()) as T;
}

export function search(q: string, page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ q, page: String(page), size: String(PAGE_SIZE) });
  return getJson<SearchResponse>(`/api/search?${params}`, signal);
}

export async function suggest(q: string, signal?: AbortSignal): Promise<string[]> {
  const data = await getJson<{ suggestions: string[] }>(
    `/api/suggest?${new URLSearchParams({ q })}`,
    signal,
  );
  return data.suggestions;
}

export function stats(signal?: AbortSignal) {
  return getJson<Stats>("/api/stats", signal);
}
