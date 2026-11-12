import { useCallback, useEffect, useState } from "react";
import { search, stats, SearchError, type ErrorKind, type SearchResponse, type Stats } from "./api";
import { SearchBox } from "./components/SearchBox";
import { QueryReading } from "./components/QueryReading";
import { Results } from "./components/Results";

const SOURCE_URL = "https://github.com/Software-Cookie/SearchForge";
const EXAMPLES = ["porter stemmer", '"page rank"', "crawler OR robots", "index -oracle"];
const WAKE_UP_AFTER_MS = 3000;

const ERROR_TEXT: Record<ErrorKind, string> = {
  "rate-limited": "That was more than 10 searches a second. Wait a moment, then search again.",
  "too-slow": "That search took too long to finish. Add a more specific word and try again.",
  "bad-query": "Searches can be 1 to 200 characters long. Shorten the query and try again.",
  server: "The search server hit an error. Try again in a minute.",
  network: "The search server can't be reached. Check your connection and try again.",
};

type State =
  | { status: "idle" }
  | { status: "loading"; slow: boolean }
  | { status: "done"; data: SearchResponse }
  | { status: "error"; kind: ErrorKind };

function readUrl() {
  const p = new URLSearchParams(window.location.search);
  const page = Number.parseInt(p.get("page") ?? "1", 10);
  return { q: (p.get("q") ?? "").trim(), page: Number.isFinite(page) && page > 0 ? page : 1 };
}

export default function App() {
  const [{ q, page }, setLocation] = useState(readUrl);
  const [draft, setDraft] = useState(q);
  const [state, setState] = useState<State>({ status: "idle" });
  const [index, setIndex] = useState<Stats | null>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    stats(ctrl.signal).then(setIndex).catch(() => setIndex(null));
    const onPop = () => {
      const loc = readUrl();
      setLocation(loc);
      setDraft(loc.q);
    };
    window.addEventListener("popstate", onPop);
    return () => {
      ctrl.abort();
      window.removeEventListener("popstate", onPop);
    };
  }, []);

  useEffect(() => {
    document.title = q ? `${q} – SearchForge` : "SearchForge";
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
    search(q, page, ctrl.signal)
      .then((data) => {
        setState({ status: "done", data });
        window.scrollTo({ top: 0 });
      })
      .catch((e: unknown) => {
        if (e instanceof SearchError) setState({ status: "error", kind: e.kind });
        else if (!(e instanceof DOMException && e.name === "AbortError")) {
          setState({ status: "error", kind: "server" });
        }
      })
      .finally(() => window.clearTimeout(slowTimer));
    return () => {
      ctrl.abort();
      window.clearTimeout(slowTimer);
    };
  }, [q, page]);

  const go = useCallback((query: string, toPage = 1) => {
    const url = `?${new URLSearchParams({ q: query, ...(toPage > 1 ? { page: String(toPage) } : {}) })}`;
    window.history.pushState(null, "", url);
    setLocation({ q: query, page: toPage });
    setDraft(query);
  }, []);

  const goHome = useCallback(() => {
    window.history.pushState(null, "", window.location.pathname);
    setLocation({ q: "", page: 1 });
    setDraft("");
  }, []);

  if (!q) {
    return (
      <div className="home">
        <main className="home__main">
          <h1 className="wordmark wordmark--large">SearchForge</h1>
          <p className="home__lede">
            A web search engine written from first principles in Java: polite crawler,
            compressed inverted index, BM25 ranking with PageRank, spelling and autocomplete.
          </p>
          <SearchBox initial={draft} onSearch={go} size="large" autoFocus onChange={setDraft} />
          <QueryReading query={draft} />
          <div className="examples">
            <span className="examples__label">Try</span>
            <ul>
              {EXAMPLES.map((ex) => (
                <li key={ex}>
                  <button type="button" className="link" onClick={() => go(ex)}>
                    {ex}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </main>
        <Footer index={index} />
      </div>
    );
  }

  return (
    <div className="serp">
      <header className="serp__header">
        <a href="./" className="wordmark wordmark--small" onClick={(e) => {
          e.preventDefault();
          goHome();
        }}>
          SearchForge
        </a>
        <div className="serp__search">
          <SearchBox initial={q} onSearch={go} size="compact" onChange={setDraft} />
          <QueryReading query={draft} />
        </div>
      </header>
      <div className={state.status === "loading" ? "progress is-on" : "progress"} aria-hidden="true" />
      <main className="serp__main" aria-busy={state.status === "loading"}>
        <Summary state={state} />
        {state.status === "loading" && state.slow && (
          <p className="notice" role="status">
            Waking up the server. Free hosting pauses it when nobody has searched for a while,
            and the first search after that can take up to a minute.
          </p>
        )}
        {state.status === "error" && (
          <p className="notice notice--error" role="alert">
            {ERROR_TEXT[state.kind]}
          </p>
        )}
        {state.status === "done" && <Results data={state.data} onSearch={go} />}
      </main>
      <Footer index={index} />
    </div>
  );
}

function Summary({ state }: { state: State }) {
  if (state.status !== "done") return <p className="summary" aria-hidden="true">&nbsp;</p>;
  const { totalHits, tookMs, page } = state.data;
  const n = totalHits.toLocaleString("en");
  return (
    <p className="summary" role="status">
      {totalHits === 1 ? "1 result" : `${n} results`}
      {page > 1 ? `, page ${page}` : ""} in {tookMs} ms
    </p>
  );
}

function Footer({ index }: { index: Stats | null }) {
  return (
    <footer className="footer">
      {index && (
        <p>
          {index.documents.toLocaleString("en")} pages and {index.terms.toLocaleString("en")} terms
          indexed
          {index.indexCreatedAt && <>, built {formatDate(index.indexCreatedAt)}</>}.
        </p>
      )}
      <p>
        <a href={SOURCE_URL} rel="noopener noreferrer">Source code and design notes</a>
      </p>
    </footer>
  );
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" });
}
