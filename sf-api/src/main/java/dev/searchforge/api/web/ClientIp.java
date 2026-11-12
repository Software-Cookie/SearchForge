package dev.searchforge.api.web;

import jakarta.servlet.http.HttpServletRequest;

// The visitor's IP for rate limiting. Behind a proxy, getRemoteAddr() is the proxy, and
// the client can put anything at the START of X-Forwarded-For. Each trusted proxy appends
// one entry at the END, so with N trusted proxies the client is the N-th entry from the
// right. With 0 trusted proxies the header is ignored entirely.
public final class ClientIp {

    private static final int MAX_LENGTH = 64;

    private ClientIp() {
    }

    public static String from(HttpServletRequest request, int trustedProxyHops) {
        String remote = request.getRemoteAddr();
        if (trustedProxyHops <= 0) {
            return remote;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return remote;
        }
        String[] parts = header.split(",");
        int idx = parts.length - trustedProxyHops;
        if (idx < 0) {
            idx = 0; // fewer entries than proxies: take the left-most one we have
        }
        String ip = parts[idx].strip();
        if (ip.isEmpty() || ip.length() > MAX_LENGTH) {
            return remote;
        }
        return ip;
    }
}
