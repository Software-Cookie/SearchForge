import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { displayUrl, formatAge, hostOf, safeHref } from "./components/Results";

const web = {
  query: "house",
  type: "web",
  page: 1,
  tookMs: 120,
  source: "brave",
  hasMore: true,
  didYouMean: null,
  images: [],
  results: [
    {
      url: "https://www.archdaily.com/houses/modern-house",
      title: "Modern House Designs",
      site: "ArchDaily",
      favicon: "https://imgs.example/fav.png",
      thumbnail: null,
      age: "2026-03-04",
      snippet: [
        { text: "A ", hit: false },
        { text: "house", hit: true },
        { text: " with a garden <img src=x onerror=alert(1)>", hit: false },
      ],
    },
    {
      url: "javascript:alert(1)",
      title: "Bad link",
      site: "",
      favicon: null,
      thumbnail: null,
      age: "",
      snippet: [],
    },
  ],
};

const image = (n: number) => ({
  title: `House ${n}`,
  imageUrl: `https://img.example/${n}.jpg`,
  thumbnailUrl: `https://img.example/${n}-t.jpg`,
  pageUrl: `https://site.example/${n}`,
  site: "site.example",
  width: 800,
  height: 600,
});

const images = { ...web, type: "images", results: [], images: [1, 2, 3, 4, 5].map(image) };

function mockFetch(handler: (url: string) => Response | Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(handler(url))));
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

beforeEach(() => window.history.replaceState(null, "", "/"));
afterEach(() => vi.unstubAllGlobals());

describe("helpers", () => {
  it("only allows http and https and formats urls and dates", () => {
    expect(safeHref("javascript:alert(1)")).toBeUndefined();
    expect(safeHref("not a url")).toBeUndefined();
    expect(safeHref("https://a.com/x")).toBe("https://a.com/x");
    expect(displayUrl("https://a.com/one/two/three/four.html"))
      .toBe("https://a.com › … › three › four.html");
    expect(displayUrl("bad")).toBe("bad");
    expect(hostOf("https://www.a.com/x")).toBe("a.com");
    expect(formatAge("2026-03-04")).toBe("4 Mar 2026");
    expect(formatAge("3 days ago")).toBe("3 days ago");
  });
});

describe("App", () => {
  it("searches from the home page and renders safe web results with an image row", async () => {
    mockFetch((url) => (url.includes("type=images") ? json(images)
      : url.startsWith("/api/web/suggest") ? json({ suggestions: [] }) : json(web)));
    render(<App />);
    await userEvent.type(screen.getByRole("combobox"), "house{Enter}");

    expect(await screen.findByText("Modern House Designs")).toBeInTheDocument();
    expect(screen.getByText("house", { selector: "em" })).toBeInTheDocument();
    expect(screen.getByText(/4 Mar 2026/)).toBeInTheDocument();
    expect(window.location.search).toBe("?q=house");
    // a javascript: url is never turned into a link
    expect(screen.getByText("Bad link").closest("a")).not.toHaveAttribute("href");
    // snippet text is never parsed as HTML: only result images exist
    expect(await screen.findByRole("region", { name: "Images for house" })).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
    expect(screen.getByText("Results from Brave Search")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Result pages" })).toBeInTheDocument();
  });

  it("switches to the Images tab, previews an image and loads more", async () => {
    window.history.replaceState(null, "", "/?q=house&type=images");
    mockFetch((url) => (url.includes("page=2")
      ? json({ ...images, page: 2, hasMore: false, images: [6, 7].map(image) })
      : json(images)));
    render(<App />);
    expect(await screen.findByRole("button", { name: /House 1/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Images" })).toHaveAttribute("aria-current", "page");

    await userEvent.click(screen.getByRole("button", { name: /House 2/ }));
    const dialog = await screen.findByRole("dialog", { name: "House 2" });
    expect(dialog).toHaveTextContent("800 × 600");
    expect(screen.getByRole("link", { name: "Visit page" }))
      .toHaveAttribute("href", "https://site.example/2");
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await userEvent.click(screen.getByRole("button", { name: "More images" }));
    expect(await screen.findByRole("button", { name: /House 7/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "More images" })).toBeNull();
  });

  it("explains rate limiting and offers spelling corrections", async () => {
    window.history.replaceState(null, "", "/?q=hosue");
    let calls = 0;
    mockFetch((url) => {
      if (url.includes("type=images")) return json(images);
      calls++;
      return calls === 1 ? json({}, 429)
        : json({ ...web, query: "hosue", results: [], hasMore: false, didYouMean: "house" });
    });
    const { unmount } = render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many searches");
    unmount();

    render(<App />);
    expect(await screen.findByText(/did not match any documents/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "house" }));
    await waitFor(() => expect(window.location.search).toBe("?q=house"));
  });

  it("shows autocomplete suggestions and picks one with the keyboard", async () => {
    mockFetch((url) => (url.startsWith("/api/web/suggest")
      ? json({ query: "hou", suggestions: ["house", "houston"] })
      : json(web)));
    render(<App />);
    await userEvent.type(screen.getByRole("combobox"), "hou");
    expect(await screen.findByRole("option", { name: "houston" })).toBeInTheDocument();
    await userEvent.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    await waitFor(() => expect(window.location.search).toBe("?q=houston"));
  });

  it("runs an image search from the home page button", async () => {
    mockFetch(() => json(images));
    render(<App />);
    await userEvent.type(screen.getByRole("combobox"), "cottage");
    await userEvent.click(screen.getByRole("button", { name: "Image Search" }));
    await waitFor(() => expect(window.location.search).toBe("?q=cottage&type=images"));
  });

  it("tells the user when the server or the web source fails", async () => {
    window.history.replaceState(null, "", "/?q=java&page=2");
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("offline"))));
    const { unmount } = render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Can't reach SearchForge");
    unmount();

    mockFetch(() => json({}, 502));
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Web results are unavailable");
  });
});
