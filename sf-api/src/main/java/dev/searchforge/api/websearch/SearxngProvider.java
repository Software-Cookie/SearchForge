package dev.searchforge.api.websearch;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

// SearXNG (https://docs.searxng.org): a self-hosted metasearch engine that queries several
// public engines and merges their results. Free, no key; the instance must allow
// "format=json" (see searxng/settings.yml in this repo).
public final class SearxngProvider implements WebSearchProvider {

    static final int MAX_PAGE = 10;

    private final Http http;
    private final String base;

    public SearxngProvider(Http http, WebSearchProperties props) {
        this.http = http;
        this.base = props.searxngUrl();
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public WebPage web(String query, int page) {
        JsonNode root = search(query, page, "general");
        List<WebHit> hits = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String url = Http.safeUrl(Http.text(r, "url"));
            if (url == null) {
                continue;
            }
            String date = Http.text(r, "publishedDate");
            hits.add(new WebHit(url, Highlights.plain(Http.text(r, "title")), Http.host(url),
                    null, Http.safeUrl(Http.text(r, "thumbnail")),
                    date.length() >= 10 ? date.substring(0, 10) : "",
                    Highlights.fromPlain(snippet(Http.text(r, "content")), query)));
        }
        String fix = root.path("corrections").path(0).asString("");
        return new WebPage(hits, !hits.isEmpty() && page < MAX_PAGE, fix.isBlank() ? null : fix);
    }

    @Override
    public ImagePage images(String query, int page) {
        JsonNode root = search(query, page, "images");
        List<ImageHit> images = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String full = Http.safeUrl(Http.text(r, "img_src"));
            String thumb = Http.safeUrl(Http.text(r, "thumbnail_src"));
            if (full == null && thumb == null) {
                continue;
            }
            String pageUrl = Http.safeUrl(Http.text(r, "url"));
            int[] size = resolution(Http.text(r, "resolution"));
            images.add(new ImageHit(Highlights.plain(Http.text(r, "title")),
                    full == null ? thumb : full, thumb == null ? full : thumb, pageUrl,
                    pageUrl == null ? Http.host(full == null ? thumb : full) : Http.host(pageUrl),
                    size[0], size[1]));
        }
        return new ImagePage(images, !images.isEmpty() && page < MAX_PAGE);
    }

    private JsonNode search(String query, int page, String category) {
        return http.getJson(base + "/search?q=" + Http.enc(query) + "&format=json&pageno="
                + page + "&categories=" + category + "&safesearch=1&language=en");
    }

    // Plain text without the "...Read more" some engines append.
    static String snippet(String content) {
        return Highlights.plain(content).replaceFirst("\\s*(\\.{3}|\u2026)?\\s*Read more$", "");
    }

    // "1920x1080" or "1920 x 1080" -> {1920, 1080}; anything else -> {0, 0}
    static int[] resolution(String s) {
        String[] p = s.replace(" ", "").split("[x×]");
        try {
            return p.length == 2 ? new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])}
                    : new int[] {0, 0};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }
}
