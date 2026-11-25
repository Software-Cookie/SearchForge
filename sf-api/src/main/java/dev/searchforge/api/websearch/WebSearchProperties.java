package dev.searchforge.api.websearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

// Bound from "searchforge.web.*". provider: auto | brave | searxng | open.
// auto picks Brave when a key is set, else SearXNG when a URL is set, else the keyless
// open sources (Wikipedia, Hacker News, Openverse, Wikimedia Commons).
// The *Url fields exist so tests can point the providers at a local stub server.
@ConfigurationProperties("searchforge.web")
public record WebSearchProperties(
        String provider,
        String braveApiKey,
        String searxngUrl,
        int timeoutMs,
        int cacheSize,
        String userAgent,
        String braveUrl,
        String wikipediaUrl,
        String hackerNewsUrl,
        String openverseUrl,
        String commonsUrl) {

    public WebSearchProperties {
        provider = orDefault(provider, "auto").toLowerCase(Locale.ROOT);
        braveApiKey = orDefault(braveApiKey, "");
        searxngUrl = trimSlash(orDefault(searxngUrl, ""));
        timeoutMs = timeoutMs <= 0 ? 5000 : timeoutMs;
        cacheSize = Math.max(0, cacheSize);
        userAgent = orDefault(userAgent,
                "SearchForge/2.0 (+https://github.com/Software-Cookie/SearchForge)");
        braveUrl = trimSlash(orDefault(braveUrl, "https://api.search.brave.com"));
        wikipediaUrl = trimSlash(orDefault(wikipediaUrl, "https://en.wikipedia.org"));
        hackerNewsUrl = trimSlash(orDefault(hackerNewsUrl, "https://hn.algolia.com"));
        openverseUrl = trimSlash(orDefault(openverseUrl, "https://api.openverse.org"));
        commonsUrl = trimSlash(orDefault(commonsUrl, "https://commons.wikimedia.org"));
    }

    // The provider that will actually serve searches.
    public String effectiveProvider() {
        return switch (provider) {
            case "brave", "searxng", "open" -> provider;
            default -> !braveApiKey.isBlank() ? "brave"
                    : !searxngUrl.isBlank() ? "searxng" : "open";
        };
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v.strip();
    }

    private static String trimSlash(String v) {
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
