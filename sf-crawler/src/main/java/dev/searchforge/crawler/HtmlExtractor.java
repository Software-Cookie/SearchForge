package dev.searchforge.crawler;

import dev.searchforge.core.url.UrlNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// Turns raw HTML into the fields the indexer needs, and the out-links the crawler follows.
// Honors <meta name="robots" content="noindex/nofollow"> and rel="nofollow".
public final class HtmlExtractor {

    public record Extracted(String title, String headings, String body, List<String> links,
                            boolean noindex, boolean nofollow) {
    }

    static final int MAX_LINKS = 500;
    static final int MAX_BODY_CHARS = 200_000;

    private HtmlExtractor() {
    }

    public static Extracted extract(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        String robots = "";
        for (Element meta : doc.select("meta[name]")) {
            String name = meta.attr("name").toLowerCase(Locale.ROOT);
            if (name.equals("robots") || name.equals(CrawlerConfig.AGENT_TOKEN)) {
                robots += " " + meta.attr("content").toLowerCase(Locale.ROOT);
            }
        }
        boolean noindex = robots.contains("noindex") || robots.contains("none");
        boolean nofollow = robots.contains("nofollow") || robots.contains("none");

        String title = doc.title().strip();
        StringBuilder headings = new StringBuilder();
        for (Element h : doc.select("h1, h2, h3")) {
            headings.append(h.text()).append(". ");
        }
        if (title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 == null ? "" : h1.text();
        }

        List<String> links = nofollow ? List.of() : links(doc, baseUrl);
        doc.select("script, style, noscript, template, svg, nav, footer, aside, form").remove();
        String body = doc.body() == null ? "" : doc.body().text();
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS);
        }
        return new Extracted(title, headings.toString().strip(), body, links, noindex, nofollow);
    }

    private static List<String> links(Document doc, String baseUrl) {
        Set<String> out = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            if (a.attr("rel").toLowerCase(Locale.ROOT).contains("nofollow")) {
                continue;
            }
            Optional<String> url = UrlNormalizer.resolve(baseUrl, a.attr("href"));
            url.ifPresent(out::add);
            if (out.size() >= MAX_LINKS) {
                break;
            }
        }
        return new ArrayList<>(out);
    }
}
