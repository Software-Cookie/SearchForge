package dev.searchforge.crawler;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// A tiny local web site for crawler tests (JDK HttpServer, no external network).
public final class TestSite implements AutoCloseable {

    record Response(int status, String contentType, String body, String location) {
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    public final List<String> requests = new CopyOnWriteArrayList<>();
    public final List<String> userAgents = new CopyOnWriteArrayList<>();

    public TestSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String key = query == null ? path : path + "?" + query;
            requests.add(key);
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            Response r = routes.getOrDefault(key, new Response(404, "text/plain", "nope", null));
            if (r.location() != null) {
                exchange.getResponseHeaders().add("Location", r.location());
            }
            exchange.getResponseHeaders().add("Content-Type", r.contentType());
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public TestSite html(String path, String html) {
        routes.put(path, new Response(200, "text/html; charset=utf-8", html, null));
        return this;
    }

    public TestSite route(String path, int status, String type, String body, String location) {
        routes.put(path, new Response(status, type, body, location));
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
