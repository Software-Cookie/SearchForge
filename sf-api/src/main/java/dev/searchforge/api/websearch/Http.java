package dev.searchforge.api.websearch;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// Small JSON-over-HTTPS client shared by the providers. Every failure becomes an
// UpstreamException so callers handle one error type.
// Two guards protect the small instance under load: at most MAX_IN_FLIGHT upstream calls run
// at once (a spike queues briefly, then fails fast instead of piling up thousands of sockets),
// and an upstream body larger than MAX_BODY_BYTES is rejected instead of filling the heap.
public final class Http {

    static final int MAX_IN_FLIGHT = 64;
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final long QUEUE_WAIT_MS = 2000;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern WEB_URL = Pattern.compile(
            "https?://[\\p{L}\\p{N}.-]+(:\\d+)?([/?#]|$)", Pattern.CASE_INSENSITIVE);

    private final HttpClient client;
    private final Duration timeout;
    private final String userAgent;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public Http(WebSearchProperties props) {
        this.timeout = Duration.ofMillis(props.timeoutMs());
        this.userAgent = props.userAgent();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public JsonNode getJson(String url, Map<String, String> headers) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
        headers.forEach(req::header);
        boolean acquired = false;
        try {
            acquired = inFlight.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new UpstreamException(host(url) + " skipped: too many calls in flight");
            }
            HttpResponse<InputStream> res = client.send(req.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = res.body()) {
                if (res.statusCode() != 200) {
                    throw new UpstreamException(host(url) + " answered HTTP " + res.statusCode());
                }
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new UpstreamException(host(url) + " sent more than "
                            + MAX_BODY_BYTES + " bytes");
                }
                return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException | JacksonException e) {
            throw new UpstreamException(host(url) + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(host(url) + " interrupted", e);
        } finally {
            if (acquired) {
                inFlight.release();
            }
        }
    }

    public JsonNode getJson(String url) {
        return getJson(url, Map.of());
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // Text of a field, or "" when it is missing or null.
    public static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString();
    }

    // "https://www.example.com/a" -> "example.com"
    public static String host(String url) {
        try {
            String h = url == null ? null : URI.create(url).getHost();
            if (h == null) {
                return "";
            }
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // Only absolute http(s) URLs with a host are passed on to the browser.
    public static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        if (u.startsWith("//")) {
            u = "https:" + u;
        }
        // lenient on the rest: real image URLs often hold characters URI rejects, like "|"
        return WEB_URL.matcher(u).lookingAt() ? u : null;
    }
}
