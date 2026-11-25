package dev.searchforge.api.websearch;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

// Small JSON-over-HTTPS client shared by the providers. Every failure becomes an
// UpstreamException so callers handle one error type.
public final class Http {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern WEB_URL = Pattern.compile(
            "https?://[\\p{L}\\p{N}.-]+(:\\d+)?([/?#]|$)", Pattern.CASE_INSENSITIVE);

    private final HttpClient client;
    private final Duration timeout;
    private final String userAgent;

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
        try {
            HttpResponse<String> res = client.send(req.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                throw new UpstreamException(host(url) + " answered HTTP " + res.statusCode());
            }
            return JSON.readTree(res.body());
        } catch (IOException | JacksonException e) {
            throw new UpstreamException(host(url) + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(host(url) + " interrupted", e);
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
