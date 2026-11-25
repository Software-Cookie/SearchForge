import { useState } from "react";
import type { ImageHit, WebHit, WebResponse } from "../api";
import { MAX_PAGE } from "../api";
import { ImageStrip } from "./Images";

type Props = {
  data: WebResponse;
  images: ImageHit[];
  onSearch: (q: string, page?: number) => void;
  onImages: () => void;
};

export function Results({ data, images, onSearch, onImages }: Props) {
  if (data.results.length === 0) {
    return (
      <>
        <DidYouMean data={data} onSearch={onSearch} />
        <NoResults query={data.query} page={data.page} />
      </>
    );
  }
  const first = data.results.slice(0, 3);
  const rest = data.results.slice(3);
  const showStrip = data.page === 1 && images.length >= 4;
  return (
    <section className="results" aria-labelledby="results-heading">
      <h2 id="results-heading" className="visually-hidden">
        Search results
      </h2>
      <DidYouMean data={data} onSearch={onSearch} />
      <ol className="hits">
        {first.map((h) => <HitItem key={h.url} hit={h} />)}
      </ol>
      {showStrip && <ImageStrip query={data.query} images={images} onMore={onImages} />}
      {rest.length > 0 && (
        <ol className="hits">
          {rest.map((h) => <HitItem key={h.url} hit={h} />)}
        </ol>
      )}
      <Pager page={data.page} hasMore={data.hasMore} go={(p) => onSearch(data.query, p)} />
    </section>
  );
}

export function DidYouMean({ data, onSearch }: Pick<Props, "data" | "onSearch">) {
  if (!data.didYouMean) return null;
  return (
    <p className="dym">
      Did you mean:{" "}
      <button type="button" className="link dym__fix" onClick={() => onSearch(data.didYouMean!)}>
        {data.didYouMean}
      </button>
    </p>
  );
}

function HitItem({ hit }: { hit: WebHit }) {
  const href = safeHref(hit.url);
  return (
    <li className="hit">
      <div className="hit__body">
        <a className="hit__link" href={href} rel="noopener noreferrer">
          <span className="hit__source">
            <Favicon src={hit.favicon} name={hit.site || hostOf(hit.url)} />
            <span className="hit__source-text">
              <span className="hit__site">{hit.site || hostOf(hit.url)}</span>
              <cite className="hit__url">{displayUrl(hit.url)}</cite>
            </span>
          </span>
          <h3 className="hit__title">{hit.title || hostOf(hit.url)}</h3>
        </a>
        {(hit.snippet.length > 0 || hit.age) && (
          <p className="hit__snippet">
            {hit.age && <span className="hit__age">{formatAge(hit.age)} — </span>}
            {hit.snippet.map((s, i) => (s.hit ? <em key={i}>{s.text}</em> : s.text))}
          </p>
        )}
      </div>
      {hit.thumbnail && <Thumb src={hit.thumbnail} href={href} />}
    </li>
  );
}

function Thumb({ src, href }: { src: string; href?: string }) {
  const [broken, setBroken] = useState(false);
  const url = safeHref(src);
  if (!url || broken) return null;
  return (
    <a className="hit__thumb" href={href} rel="noopener noreferrer" tabIndex={-1}
      aria-hidden="true">
      <img src={url} alt="" loading="lazy" referrerPolicy="no-referrer"
        onError={() => setBroken(true)} />
    </a>
  );
}

// The site's icon in a soft circle, or its first letter when there is no icon.
function Favicon({ src, name }: { src: string | null; name: string }) {
  const [broken, setBroken] = useState(false);
  const url = src ? safeHref(src) : undefined;
  return (
    <span className="favicon" aria-hidden="true">
      {url && !broken ? (
        <img src={url} alt="" width={18} height={18} loading="lazy" referrerPolicy="no-referrer"
          onError={() => setBroken(true)} />
      ) : (
        <span className="favicon__letter">{(name.trim()[0] ?? "?").toUpperCase()}</span>
      )}
    </span>
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

export function hostOf(url: string): string {
  try {
    return new URL(url).host.replace(/^www\./, "");
  } catch {
    return url;
  }
}

// "https://docs.example.com/guide/intro.html#x" -> "https://docs.example.com › guide › intro.html"
export function displayUrl(url: string): string {
  try {
    const u = new URL(url);
    const path = u.pathname.split("/").filter(Boolean).map(safeDecode);
    const tail = path.length > 3 ? ["…", ...path.slice(-2)] : path;
    return [`${u.protocol}//${u.host}`, ...tail].join(" › ");
  } catch {
    return url;
  }
}

function safeDecode(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

// "2026-03-04" -> "4 Mar 2026"; relative strings like "3 days ago" pass through.
export function formatAge(age: string): string {
  if (!/^\d{4}-\d{2}-\d{2}/.test(age)) return age;
  const d = new Date(age.slice(0, 10) + "T00:00:00Z");
  return Number.isNaN(d.getTime())
    ? age
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric",
      timeZone: "UTC" });
}

export function NoResults({ query, page }: { query: string; page: number }) {
  if (page > 1) {
    return <p className="empty">There are no more results for this search.</p>;
  }
  return (
    <div className="empty">
      <p>
        Your search – <strong>{query}</strong> – did not match any documents.
      </p>
      <p>Suggestions:</p>
      <ul>
        <li>Make sure all words are spelled correctly.</li>
        <li>Try different or more general keywords.</li>
        <li>Try fewer keywords.</li>
      </ul>
    </div>
  );
}

function Pager({ page, hasMore, go }: { page: number; hasMore: boolean; go: (p: number) => void }) {
  const last = Math.min(MAX_PAGE, hasMore ? page + 1 : page);
  if (last <= 1) return null;
  const nums = Array.from({ length: last }, (_, i) => i + 1);
  return (
    <nav className="pager" aria-label="Result pages">
      {page > 1 && (
        <button type="button" className="pager__step" onClick={() => go(page - 1)}>
          ‹ Previous
        </button>
      )}
      <ul className="pager__nums">
        {nums.map((n) => (
          <li key={n}>
            <button type="button" className="pager__num"
              aria-current={n === page ? "page" : undefined} onClick={() => go(n)}>
              {n}
            </button>
          </li>
        ))}
      </ul>
      {page < last && (
        <button type="button" className="pager__step" onClick={() => go(page + 1)}>
          Next ›
        </button>
      )}
    </nav>
  );
}
