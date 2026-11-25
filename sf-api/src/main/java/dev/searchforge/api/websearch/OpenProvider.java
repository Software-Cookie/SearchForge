package dev.searchforge.api.websearch;

import dev.searchforge.query.Snippets.Segment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

// Keyless live sources, used when no Brave key or SearXNG URL is configured:
//   web    = Wikipedia articles + websites and blogs linked from Hacker News
//   images = Openverse (openly licensed images) + Wikimedia Commons
// Each pair runs in parallel; if one source fails the other still answers.
public final class OpenProvider implements WebSearchProvider {

    static final int WIKI_PER_PAGE = 5;
    static final int HN_PER_PAGE = 6;
    static final int IMAGES_PER_SOURCE = 20;

    private static final Logger LOG = LoggerFactory.getLogger(OpenProvider.class);
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private final Http http;
    private final WebSearchProperties props;

    public OpenProvider(Http http, WebSearchProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public String name() {
        return "open";
    }

    @Override
    public WebPage web(String query, int page) {
        var wiki = async(() -> wikipedia(query, page));
        var hn = async(() -> hackerNews(query, page));
        WebPage a = join(wiki);
        WebPage b = join(hn);
        if (a == null && b == null) {
            throw new UpstreamException("All open web sources failed");
        }
        a = a == null ? new WebPage(List.of(), false, null) : a;
        b = b == null ? new WebPage(List.of(), false, null) : b;
        // Encyclopedia answers first, as a reader expects for a plain-word query.
        List<WebHit> hits = new ArrayList<>(a.hits().subList(0, Math.min(2, a.hits().size())));
        List<WebHit> restA = a.hits().subList(hits.size(), a.hits().size());
        for (int i = 0; i < Math.max(restA.size(), b.hits().size()); i++) {
            if (i < b.hits().size()) {
                hits.add(b.hits().get(i));
            }
            if (i < restA.size()) {
                hits.add(restA.get(i));
            }
        }
        return new WebPage(hits, a.hasMore() || b.hasMore(), a.didYouMean());
    }

    @Override
    public ImagePage images(String query, int page) {
        var ov = async(() -> openverse(query, page));
        var commons = async(() -> commons(query, page));
        ImagePage a = join(ov);
        ImagePage b = join(commons);
        if (a == null && b == null) {
            throw new UpstreamException("All open image sources failed");
        }
        List<ImageHit> x = a == null ? List.of() : a.images();
        List<ImageHit> y = b == null ? List.of() : b.images();
        List<ImageHit> all = new ArrayList<>();
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            if (i < x.size()) {
                all.add(x.get(i));
            }
            if (i < y.size()) {
                all.add(y.get(i));
            }
        }
        boolean more = (a != null && a.hasMore()) || (b != null && b.hasMore());
        return new ImagePage(all, more);
    }

    WebPage wikipedia(String query, int page) {
        String q = Http.enc(query);
        JsonNode root = http.getJson(props.wikipediaUrl() + "/w/api.php?action=query"
                + "&format=json&formatversion=2&generator=search&gsrsearch=" + q
                + "&gsrlimit=" + WIKI_PER_PAGE + "&gsroffset=" + (page - 1) * WIKI_PER_PAGE
                + "&prop=pageimages%7Cextracts%7Cinfo&inprop=url&piprop=thumbnail"
                + "&pithumbsize=240&pilimit=max&exintro=1&explaintext=1&exsentences=3"
                + "&exlimit=max&list=search&srsearch=" + q
                + "&srlimit=1&srprop=&srinfo=suggestion");
        List<JsonNode> pages = new ArrayList<>();
        root.path("query").path("pages").forEach(pages::add);
        pages.sort((p1, p2) -> Integer.compare(p1.path("index").asInt(), p2.path("index").asInt()));
        List<WebHit> hits = new ArrayList<>();
        for (JsonNode p : pages) {
            String url = Http.safeUrl(Http.text(p, "fullurl"));
            if (url != null) {
                hits.add(new WebHit(url, Http.text(p, "title"), "Wikipedia", null,
                        Http.safeUrl(Http.text(p.path("thumbnail"), "source")), "",
                        Highlights.fromPlain(Http.text(p, "extract"), query)));
            }
        }
        String fix = Http.text(root.path("query").path("searchinfo"), "suggestion");
        return new WebPage(hits, root.has("continue"), realCorrection(query, fix));
    }

    // Wikipedia suggests "houses" for "house"; only a change to the word itself counts.
    static String realCorrection(String query, String fix) {
        String q = query.toLowerCase(java.util.Locale.ROOT).strip();
        String f = fix.toLowerCase(java.util.Locale.ROOT).strip();
        if (f.isEmpty() || f.equals(q) || f.startsWith(q) || q.startsWith(f)) {
            return null;
        }
        return fix;
    }

    WebPage hackerNews(String query, int page) {
        JsonNode root = http.getJson(props.hackerNewsUrl() + "/api/v1/search?query="
                + Http.enc(query) + "&tags=story&hitsPerPage=" + HN_PER_PAGE
                + "&page=" + (page - 1));
        List<WebHit> hits = new ArrayList<>();
        for (JsonNode h : root.path("hits")) {
            String url = Http.safeUrl(Http.text(h, "url"));
            if (url == null) {
                continue;
            }
            String created = Http.text(h, "created_at");
            String about = Http.text(h, "story_text");
            List<Segment> snippet = about.isBlank()
                    ? Highlights.fromPlain(Http.host(url) + " · " + h.path("points").asInt(0)
                    + " points on Hacker News", query)
                    : Highlights.fromPlain(Highlights.plain(about), query);
            hits.add(new WebHit(url, Highlights.plain(Http.text(h, "title")), Http.host(url),
                    null, null, created.length() >= 10 ? created.substring(0, 10) : "",
                    snippet));
        }
        return new WebPage(hits, page < root.path("nbPages").asInt(0), null);
    }

    ImagePage openverse(String query, int page) {
        JsonNode root = http.getJson(props.openverseUrl() + "/v1/images/?q=" + Http.enc(query)
                + "&page=" + page + "&page_size=" + IMAGES_PER_SOURCE + "&mature=false");
        List<ImageHit> images = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String full = Http.safeUrl(Http.text(r, "url"));
            String thumb = Http.safeUrl(Http.text(r, "thumbnail"));
            if (full == null) {
                continue;
            }
            String pageUrl = Http.safeUrl(Http.text(r, "foreign_landing_url"));
            images.add(new ImageHit(Http.text(r, "title"), full, thumb == null ? full : thumb,
                    pageUrl, Http.host(pageUrl), r.path("width").asInt(0),
                    r.path("height").asInt(0)));
        }
        return new ImagePage(images, page < root.path("page_count").asInt(0));
    }

    ImagePage commons(String query, int page) {
        JsonNode root = http.getJson(props.commonsUrl() + "/w/api.php?action=query"
                + "&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrsearch="
                + Http.enc(query + " filetype:bitmap") + "&gsrlimit=" + IMAGES_PER_SOURCE
                + "&gsroffset=" + (page - 1) * IMAGES_PER_SOURCE
                + "&prop=imageinfo&iiprop=url%7Csize&iiurlwidth=400");
        List<JsonNode> pages = new ArrayList<>();
        root.path("query").path("pages").forEach(pages::add);
        pages.sort((p1, p2) -> Integer.compare(p1.path("index").asInt(), p2.path("index").asInt()));
        List<ImageHit> images = new ArrayList<>();
        for (JsonNode p : pages) {
            JsonNode info = p.path("imageinfo").path(0);
            String full = Http.safeUrl(Http.text(info, "url"));
            String thumb = Http.safeUrl(Http.text(info, "thumburl"));
            if (full == null) {
                continue;
            }
            String title = Http.text(p, "title").replaceFirst("^File:", "")
                    .replaceFirst("\\.[A-Za-z0-9]{2,5}$", "");
            images.add(new ImageHit(title, full, thumb == null ? full : thumb,
                    Http.safeUrl(Http.text(info, "descriptionurl")), "commons.wikimedia.org",
                    info.path("width").asInt(0), info.path("height").asInt(0)));
        }
        return new ImagePage(images, root.has("continue"));
    }

    private static <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, POOL);
    }

    // The source's answer, or null when that one source failed.
    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            LOG.warn("Open source skipped: {}", e.getCause().getMessage());
            return null;
        }
    }
}
