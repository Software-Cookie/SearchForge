import type { Hit, SearchResponse } from "../api";
import { PAGE_SIZE } from "../api";

type Props = {
  data: SearchResponse;
  onSearch: (q: string, page?: number) => void;
};

export function Results({ data, onSearch }: Props) {
  const pages = Math.min(100, Math.ceil(data.totalHits / PAGE_SIZE));
  return (
    <section className="results" aria-labelledby="results-heading">
      <h2 id="results-heading" className="visually-hidden">
        Results
      </h2>
      {data.didYouMean && (
        <p className="dym">
          Did you mean{" "}
          <button type="button" className="link" onClick={() => onSearch(data.didYouMean!)}>
            {data.didYouMean}
          </button>
          ?
        </p>
      )}
      {data.results.length === 0 ? (
        <NoResults query={data.query} page={data.page} />
      ) : (
        <ol className="hits" start={(data.page - 1) * PAGE_SIZE + 1}>
          {data.results.map((h) => (
            <HitItem key={h.url} hit={h} />
          ))}
        </ol>
      )}
      {pages > 1 && (
        <Pager page={data.page} pages={pages} go={(p) => onSearch(data.query, p)} />
      )}
    </section>
  );
}

function HitItem({ hit }: { hit: Hit }) {
  return (
    <li className="hit">
      <a className="hit__link" href={safeHref(hit.url)} rel="noopener noreferrer">
        <span className="hit__url">{displayUrl(hit.url)}</span>
        <span className="hit__title">{hit.title}</span>
      </a>
      <p className="hit__snippet">
        {hit.snippet.map((s, i) => (s.hit ? <mark key={i}>{s.text}</mark> : s.text))}
      </p>
    </li>
  );
}

// Only http(s) links are rendered; anything else (javascript:, data:) is dropped.
export function safeHref(url: string): string | undefined {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.href : undefined;
  } catch {
    return undefined;
  }
}

// "https://docs.example.com/guide/intro.html#x" -> "docs.example.com › guide › intro.html"
export function displayUrl(url: string): string {
  try {
    const u = new URL(url);
    const path = u.pathname.split("/").filter(Boolean).map(decodeURIComponent);
    const tail = path.length > 3 ? ["…", ...path.slice(-2)] : path;
    return [u.host, ...tail].join(" › ");
  } catch {
    return url;
  }
}

function NoResults({ query, page }: { query: string; page: number }) {
  if (page > 1) {
    return <p className="empty">There are no more results for this search.</p>;
  }
  return (
    <div className="empty">
      <p className="empty__title">No pages match all of “{query}”.</p>
      <p>Try fewer words, check the spelling, or put OR between words that mean the same.</p>
    </div>
  );
}

function Pager({ page, pages, go }: { page: number; pages: number; go: (p: number) => void }) {
  const from = Math.max(1, Math.min(page - 4, pages - 9));
  const nums = Array.from({ length: Math.min(10, pages) }, (_, i) => from + i);
  return (
    <nav className="pager" aria-label="Result pages">
      <button type="button" className="pager__step" disabled={page <= 1}
        onClick={() => go(page - 1)}>
        Previous
      </button>
      <ul className="pager__nums">
        {nums.map((n) => (
          <li key={n}>
            <button type="button" className="pager__num" aria-current={n === page ? "page" : undefined}
              onClick={() => go(n)}>
              {n}
            </button>
          </li>
        ))}
      </ul>
      <button type="button" className="pager__step" disabled={page >= pages}
        onClick={() => go(page + 1)}>
        Next
      </button>
    </nav>
  );
}
