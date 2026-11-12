package dev.searchforge.crawler.fetch;

import dev.searchforge.crawler.CrawlerConfig;
import dev.searchforge.crawler.robots.RobotsRules;
import dev.searchforge.crawler.robots.RobotsTxtParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

// HTTP fetcher on java.net.http.HttpClient. Redirects are NOT followed automatically:
// the crawler re-enqueues the target so scope, robots.txt and politeness apply to it too.
public final class PageFetcher {

    private static final int MAX_ROBOTS_BYTES = 500 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final CrawlerConfig config;
    private final HostGuard guard;
    private final HttpClient client;

    public PageFetcher(CrawlerConfig config, HostGuard guard) {
        this.config = config;
        this.guard = guard;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchResult fetch(String url) {
        URI uri = URI.create(url);
        if (!guard.isAllowed(uri.getHost())) {
            return FetchResult.of(FetchResult.Kind.BLOCKED, 0);
        }
        try {
            HttpRequest req = request(uri, "text/html,application/xhtml+xml");
            HttpResponse<InputStream> res = client.send(req,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                int status = res.statusCode();
                if (status >= 300 && status < 400) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    return new FetchResult(loc == null ? FetchResult.Kind.HTTP_ERROR
                            : FetchResult.Kind.REDIRECT, status, null, loc);
                }
                if (status != 200) {
                    return FetchResult.of(FetchResult.Kind.HTTP_ERROR, status);
                }
                String type = res.headers().firstValue("content-type").orElse("")
                        .toLowerCase(Locale.ROOT);
                if (!type.startsWith("text/html") && !type.startsWith("application/xhtml+xml")) {
                    return FetchResult.of(FetchResult.Kind.NOT_HTML, status);
                }
                byte[] bytes = body.readNBytes(config.maxBytes()); // truncate oversized pages
                return new FetchResult(FetchResult.Kind.OK, status,
                        new String(bytes, charsetOf(type)), null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.of(FetchResult.Kind.ERROR, 0);
        } catch (IOException | IllegalArgumentException e) {
            return FetchResult.of(FetchResult.Kind.ERROR, 0);
        }
    }

    // RFC 9309 2.3.1: 2xx -> parse; 4xx -> no restrictions; 5xx or unreachable -> disallow.
    // Up to 5 redirects are followed for robots.txt only.
    public RobotsRules fetchRobots(String origin) {
        String url = origin + "/robots.txt";
        for (int hop = 0; hop < 5; hop++) {
            URI uri = URI.create(url);
            if (!guard.isAllowed(uri.getHost())) {
                return RobotsRules.DISALLOW_ALL;
            }
            try {
                HttpResponse<InputStream> res = client.send(request(uri, "text/plain"),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = res.body()) {
                    int status = res.statusCode();
                    if (status >= 200 && status < 300) {
                        String text = new String(body.readNBytes(MAX_ROBOTS_BYTES),
                                StandardCharsets.UTF_8);
                        return RobotsTxtParser.parse(text, CrawlerConfig.AGENT_TOKEN);
                    }
                    if (status >= 300 && status < 400
                            && res.headers().firstValue("location").isPresent()) {
                        url = uri.resolve(res.headers().firstValue("location").get()).toString();
                        continue;
                    }
                    return status >= 400 && status < 500 ? RobotsRules.ALLOW_ALL
                            : RobotsRules.DISALLOW_ALL;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RobotsRules.DISALLOW_ALL;
            } catch (IOException | IllegalArgumentException e) {
                return RobotsRules.DISALLOW_ALL;
            }
        }
        return RobotsRules.DISALLOW_ALL;
    }

    private HttpRequest request(URI uri, String accept) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", config.userAgent())
                .header("Accept", accept)
                .GET()
                .build();
    }

    static Charset charsetOf(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i < 0) {
            return StandardCharsets.UTF_8;
        }
        String name = contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", "");
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
