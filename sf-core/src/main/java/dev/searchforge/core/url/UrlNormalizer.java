package dev.searchforge.core.url;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// Canonical form for crawl URLs so that trivially different spellings dedupe to one key:
// http/https only, lowercase scheme+host, no default port, no fragment, dot-segments
// removed, empty path -> "/", tracking parameters (utm_*, fbclid, gclid) dropped.
public final class UrlNormalizer {

    private static final int MAX_URL_LENGTH = 2048;

    private UrlNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.strip());
            return normalize(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    // Resolves href against base (as a browser would) and normalizes the result.
    public static Optional<String> resolve(String base, String href) {
        if (href == null || href.isBlank()) {
            return Optional.empty();
        }
        try {
            URI resolved = new URI(base).resolve(href.strip());
            return normalize(resolved);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String host(String normalizedUrl) {
        return URI.create(normalizedUrl).getHost();
    }

    private static Optional<String> normalize(URI uri) throws URISyntaxException {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        int port = uri.getPort();
        if ((port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"))) {
            port = -1;
        }
        String path = uri.normalize().getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        while (path.startsWith("/../")) {
            path = path.substring(3);
        }
        String query = cleanQuery(uri.getRawQuery());
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null) {
            sb.append('?').append(query);
        }
        String out = sb.toString();
        return out.length() > MAX_URL_LENGTH ? Optional.empty() : Optional.of(out);
    }

    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            if (param.isEmpty()) {
                continue;
            }
            String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || name.equals("fbclid") || name.equals("gclid")) {
                continue;
            }
            kept.add(param);
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }
}
