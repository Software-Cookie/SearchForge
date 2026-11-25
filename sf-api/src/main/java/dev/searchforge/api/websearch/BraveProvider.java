package dev.searchforge.api.websearch;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Brave Search API (https://api-dashboard.search.brave.com): an independent web index with
// web pages, blogs, news sites and images, the closest free-tier match to a Google page.
public final class BraveProvider implements WebSearchProvider {

    static final int WEB_COUNT = 10;
    static final int IMAGE_COUNT = 40;
    static final int MAX_IMAGES = 200;

    private final Http http;
    private final String base;
    private final Map<String, String> auth;

    public BraveProvider(Http http, WebSearchProperties props) {
        this.http = http;
        this.base = props.braveUrl();
        this.auth = Map.of("X-Subscription-Token", props.braveApiKey());
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public WebPage web(String query, int page) {
        JsonNode root = http.getJson(base + "/res/v1/web/search?q=" + Http.enc(query)
                + "&count=" + WEB_COUNT + "&offset=" + (page - 1)
                + "&safesearch=moderate&text_decorations=true&spellcheck=true", auth);
        List<WebHit> hits = new ArrayList<>();
        for (JsonNode r : root.path("web").path("results")) {
            String url = Http.safeUrl(Http.text(r, "url"));
            if (url == null) {
                continue;
            }
            String site = Http.text(r.path("profile"), "name");
            hits.add(new WebHit(url, Highlights.plain(Http.text(r, "title")),
                    site.isBlank() ? Http.host(url) : site,
                    Http.safeUrl(Http.text(r.path("meta_url"), "favicon")),
                    Http.safeUrl(Http.text(r.path("thumbnail"), "src")),
                    Http.text(r, "age"),
                    Highlights.fromHtml(Http.text(r, "description"))));
        }
        JsonNode q = root.path("query");
        String altered = Http.text(q, "altered");
        return new WebPage(hits, q.path("more_results_available").asBoolean(false),
                altered.isBlank() || altered.equalsIgnoreCase(query) ? null : altered);
    }

    @Override
    public ImagePage images(String query, int page) {
        int want = Math.min(MAX_IMAGES, page * IMAGE_COUNT);
        JsonNode root = http.getJson(base + "/res/v1/images/search?q=" + Http.enc(query)
                + "&count=" + want + "&safesearch=strict&spellcheck=true", auth);
        List<ImageHit> all = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String thumb = Http.safeUrl(Http.text(r.path("thumbnail"), "src"));
            String full = Http.safeUrl(Http.text(r.path("properties"), "url"));
            if (thumb == null) {
                continue;
            }
            String pageUrl = Http.safeUrl(Http.text(r, "url"));
            String site = Http.text(r, "source");
            all.add(new ImageHit(Highlights.plain(Http.text(r, "title")),
                    full == null ? thumb : full, thumb, pageUrl,
                    site.isBlank() ? Http.host(pageUrl) : site,
                    r.path("properties").path("width").asInt(0),
                    r.path("properties").path("height").asInt(0)));
        }
        int from = Math.min(all.size(), (page - 1) * IMAGE_COUNT);
        List<ImageHit> slice = all.subList(from, Math.min(all.size(), from + IMAGE_COUNT));
        boolean more = all.size() >= want && want < MAX_IMAGES;
        return new ImagePage(List.copyOf(slice), more);
    }
}
