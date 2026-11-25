import { useCallback, useEffect, useState } from "react";
import {
  SOURCE_NAMES, SearchError, webSearch, type ErrorKind, type ImageHit, type SearchType,
  type WebResponse,
} from "./api";
import { SearchBox } from "./components/SearchBox";
import { DidYouMean, Results } from "./components/Results";
import { ImageGrid } from "./components/Images";
import { Logo } from "./components/Logo";

const SOURCE_URL = "https://github.com/Software-Cookie/SearchForge";
const WAKE_UP_AFTER_MS = 4000;

const ERROR_TEXT: Record<ErrorKind, string> = {
  "rate-limited": "Too many searches in a short time. Wait a moment, then try again.",
  upstream: "Web results are unavailable right now. Please try again in a moment.",
  "bad-query": "Searches can be 1 to 200 characters long. Shorten the query and try again.",
  server: "Something went wrong on our side. Please try again in a minute.",
  network: "Can't reach SearchForge. Check your connection and try again.",
};

type State =
  | { status: "idle" }
  | { status: "loading"; slow: boolean }
  | { status: "done"; data: WebResponse; loadingMore?: boolean }
  | { status: "error"; kind: ErrorKind };

type Location = { q: string; type: SearchType; page: number };

function readUrl(): Location {
  const p = new URLSearchParams(window.location.search);
  const page = Number.parseInt(p.get("page") ?? "1", 10);
  return {
    q: (p.get("q") ?? "").trim(),
    type: p.get("type") === "images" ? "images" : "web",
    page: Number.isFinite(page) && page > 0 ? Math.min(page, 10) : 1,
  };
}

function toUrl({ q, type, page }: Location) {
  const p = new URLSearchParams({ q });
  if (type === "images") p.set("type", "images");
  if (page > 1) p.set("page", String(page));
  return `?${p}`;
}

function errorKind(e: unknown): ErrorKind | null {
  if (e instanceof SearchError) return e.kind;
  if (e instanceof DOMException && e.name === "AbortError") return null;
  return "server";
}

export default function App() {
  const [loc, setLoc] = useState<Location>(readUrl);
  const [state, setState] = useState<State>({ status: "idle" });
  const [strip, setStrip] = useState<ImageHit[]>([]);
  const { q, type, page } = loc;

  useEffect(() => {
    const onPop = () => setLoc(readUrl());
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  useEffect(() => {
    document.title = q ? `${q} - SearchForge` : "SearchForge";
    if (!q) {
      setState({ status: "idle" });
      return;
    }
    const ctrl = new AbortController();
    setState({ status: "loading", slow: false });
    const slowTimer = window.setTimeout(
      () => setState((s) => (s.status === "loading" ? { status: "loading", slow: true } : s)),
      WAKE_UP_AFTER_MS,
    );
    webSearch(q, type, page, ctrl.signal)
      .then((data) => {
        setState({ status: "done", data });
        window.scrollTo({ top: 0 });
      })
      .catch((e: unknown) => {
        const kind = errorKind(e);
        if (kind) setState({ status: "error", kind });
      })
      .finally(() => window.clearTimeout(slowTimer));
    return () => {
      ctrl.abort();
      window.clearTimeout(slowTimer);
    };
  }, [q, type, page]);

  // The image row on the first page of All results; optional, so errors are ignored.
  useEffect(() => {
    setStrip([]);
    if (!q || type !== "web" || page !== 1) return;
    const ctrl = new AbortController();
    webSearch(q, "images", 1, ctrl.signal)
      .then((d) => setStrip(d.images))
      .catch(() => setStrip([]));
    return () => ctrl.abort();
  }, [q, type, page]);

  const navigate = useCallback((next: Location) => {
    window.history.pushState(null, "", next.q ? toUrl(next) : window.location.pathname);
    setLoc(next);
  }, []);

  const go = useCallback(
    (query: string, toPage = 1) => navigate({ q: query, type: loc.type, page: toPage }),
    [navigate, loc.type],
  );

  const moreImages = useCallback(() => {
    if (state.status !== "done" || state.loadingMore) return;
    const current = state.data;
    setState({ status: "done", data: current, loadingMore: true });
    webSearch(current.query, "images", current.page + 1)
      .then((next) => {
        const seen = new Set(current.images.map((i) => i.imageUrl));
        const fresh = next.images.filter((i) => !seen.has(i.imageUrl));
        setState({ status: "done", data: { ...next, images: [...current.images, ...fresh] } });
      })
      .catch(() => setState({ status: "done", data: { ...current, hasMore: false } }));
  }, [state]);

  if (!q) {
    return (
      <div className="home">
        <main className="home__main">
          <h1 className="home__logo">
            <Logo size="large" />
          </h1>
          <SearchBox initial="" size="large" autoFocus
            onSearch={(s) => navigate({ q: s, type: "web", page: 1 })}
            onImageSearch={(s) => navigate({ q: s, type: "images", page: 1 })} />
        </main>
        <Footer />
      </div>
    );
  }

  return (
    <div className="serp">
      <header className="serp__header">
        <div className="serp__bar">
          <a href="./" className="serp__logo" aria-label="SearchForge home" onClick={(e) => {
            e.preventDefault();
            navigate({ q: "", type: "web", page: 1 });
          }}>
            <Logo size="small" />
          </a>
          <div className="serp__search">
            <SearchBox initial={q} onSearch={(s) => go(s)} size="compact" />
          </div>
        </div>
        <nav className="tabs" aria-label="Search type">
          <Tab label="All" active={type === "web"} icon="all"
            onClick={() => navigate({ q, type: "web", page: 1 })} />
          <Tab label="Images" active={type === "images"} icon="images"
            onClick={() => navigate({ q, type: "images", page: 1 })} />
        </nav>
        <div className={state.status === "loading" ? "progress is-on" : "progress"}
          aria-hidden="true" />
      </header>
      <main className={type === "images" ? "serp__main serp__main--wide" : "serp__main"}
        aria-busy={state.status === "loading"}>
        {state.status === "loading" && <Skeleton images={type === "images"} slow={state.slow} />}
        {state.status === "error" && (
          <p className="notice" role="alert">{ERROR_TEXT[state.kind]}</p>
        )}
        {state.status === "done" && type === "web" && (
          <Results data={state.data} images={strip} onSearch={go}
            onImages={() => navigate({ q, type: "images", page: 1 })} />
        )}
        {state.status === "done" && type === "images" && (
          <>
            <DidYouMean data={state.data} onSearch={go} />
            <ImageGrid images={state.data.images} hasMore={state.data.hasMore}
              loadingMore={!!state.loadingMore} onMore={moreImages} />
          </>
        )}
      </main>
      <Footer source={state.status === "done" ? state.data.source : undefined} />
    </div>
  );
}

function Tab({ label, active, icon, onClick }: {
  label: string;
  active: boolean;
  icon: "all" | "images";
  onClick: () => void;
}) {
  return (
    <button type="button" className={active ? "tab is-active" : "tab"}
      aria-current={active ? "page" : undefined} onClick={onClick}>
      <svg className="icon" viewBox="0 0 24 24" aria-hidden="true">
        {icon === "all" ? (
          <>
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="M15.5 15.5 21 21" />
          </>
        ) : (
          <>
            <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
            <circle cx="9" cy="10" r="1.8" />
            <path d="m4 18 5-5 4 4 3-3 4 4" />
          </>
        )}
      </svg>
      {label}
    </button>
  );
}

function Skeleton({ images, slow }: { images: boolean; slow: boolean }) {
  return (
    <div role="status" aria-label="Loading results">
      {slow && (
        <p className="notice notice--quiet">
          Waking up the server. The first search after a quiet spell can take up to a minute.
        </p>
      )}
      {images ? (
        <ul className="grid" aria-hidden="true">
          {Array.from({ length: 12 }, (_, i) => (
            <li key={i} className="grid__item skel skel--tile" />
          ))}
        </ul>
      ) : (
        Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="skel-hit" aria-hidden="true">
            <span className="skel skel--line skel--short" />
            <span className="skel skel--line skel--title" />
            <span className="skel skel--line" />
            <span className="skel skel--line skel--mid" />
          </div>
        ))
      )}
    </div>
  );
}

function Footer({ source }: { source?: string }) {
  return (
    <footer className="footer">
      <span>{source ? `Results from ${SOURCE_NAMES[source] ?? source}` : "SearchForge"}</span>
      <a href={SOURCE_URL} rel="noopener noreferrer">About</a>
    </footer>
  );
}
