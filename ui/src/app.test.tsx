import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { readQuery } from "./queryParts";
import { displayUrl, safeHref } from "./components/Results";

const result = {
  query: "porter stemmer",
  totalHits: 23,
  tookMs: 4,
  page: 1,
  size: 10,
  didYouMean: null,
  results: [
    {
      url: "https://docs.example.com/guide/porter.html",
      title: "The Porter Stemmer",
      score: 7.9,
      snippet: [
        { text: "… the ", hit: false },
        { text: "Porter", hit: true },
        { text: " algorithm <img src=x onerror=alert(1)> …", hit: false },
      ],
    },
  ],
};

function mockFetch(handler: (url: string) => Response | Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(handler(url))));
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const stats = { documents: 78, terms: 2291, vocabulary: 2691, indexCreatedAt: "2026-11-12T10:00:00Z" };

beforeEach(() => window.history.replaceState(null, "", "/"));
afterEach(() => vi.unstubAllGlobals());

describe("readQuery", () => {
  it("reads words, phrases, alternatives and exclusions", () => {
    expect(readQuery('java OR kotlin "stream api" -android')).toEqual([
      { kind: "either", options: ["java", "kotlin"] },
      { kind: "phrase", text: "stream api" },
      { kind: "exclude", text: "android" },
    ]);
    expect(readQuery("OR -")).toEqual([{ kind: "word", text: "-" }]);
    expect(readQuery("a OR b OR c")).toEqual([{ kind: "either", options: ["a", "b", "c"] }]);
    expect(readQuery('"single"')).toEqual([{ kind: "word", text: "single" }]);
    expect(readQuery("")).toEqual([]);
  });
});

describe("result links", () => {
  it("only allows http and https and shortens long paths", () => {
    expect(safeHref("javascript:alert(1)")).toBeUndefined();
    expect(safeHref("not a url")).toBeUndefined();
    expect(safeHref("https://a.com/x")).toBe("https://a.com/x");
    expect(displayUrl("https://a.com/one/two/three/four.html")).toBe("a.com › … › three › four.html");
    expect(displayUrl("bad")).toBe("bad");
  });
});

describe("App", () => {
  it("searches from the home page and renders highlighted, safe snippets", async () => {
    mockFetch((url) => (url.startsWith("/api/stats") ? json(stats)
      : url.startsWith("/api/suggest") ? json({ query: "", suggestions: [] }) : json(result)));
    render(<App />);
    expect(await screen.findByText(/78 pages and 2,291 terms indexed/)).toBeInTheDocument();

    await userEvent.type(screen.getByRole("combobox"), "porter stemmer{Enter}");
    expect(await screen.findByText("The Porter Stemmer")).toBeInTheDocument();
    expect(screen.getByText("23 results in 4 ms")).toBeInTheDocument();
    expect(screen.getByText("Porter", { selector: "mark" })).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull(); // snippet text is never parsed as HTML
    expect(window.location.search).toBe("?q=porter+stemmer");
    expect(screen.getByRole("navigation", { name: "Result pages" })).toBeInTheDocument();
  });

  it("explains rate limiting and offers spelling corrections", async () => {
    window.history.replaceState(null, "", "/?q=crawlr");
    let calls = 0;
    mockFetch((url) => {
      if (url.startsWith("/api/stats")) return json(stats);
      calls++;
      return calls === 1 ? json({}, 429)
        : json({ ...result, query: "crawler", totalHits: 0, results: [], didYouMean: "crawler" });
    });
    const { unmount } = render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("more than 10 searches a second");
    unmount();

    render(<App />);
    expect(await screen.findByText(/No pages match all of/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "crawler" }));
    await waitFor(() => expect(window.location.search).toBe("?q=crawler"));
  });

  it("shows autocomplete suggestions and picks one with the keyboard", async () => {
    mockFetch((url) => (url.startsWith("/api/suggest")
      ? json({ query: "cra", suggestions: ["crawler", "crawl"] })
      : url.startsWith("/api/stats") ? json(stats) : json(result)));
    render(<App />);
    const box = screen.getByRole("combobox");
    await userEvent.type(box, "cra");
    expect(await screen.findByRole("option", { name: "crawler" })).toBeInTheDocument();
    await userEvent.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    await waitFor(() => expect(window.location.search).toBe("?q=crawl"));
  });

  it("tells the user when the server cannot be reached", async () => {
    window.history.replaceState(null, "", "/?q=java&page=2");
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("offline"))));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("can't be reached");
  });
});
