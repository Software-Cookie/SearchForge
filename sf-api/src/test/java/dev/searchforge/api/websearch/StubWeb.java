package dev.searchforge.api.websearch;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// A local HTTP server that answers canned JSON by path, standing in for the real sources.
final class StubWeb implements AutoCloseable {

    record Reply(int status, String body) {
    }

    final Map<String, Reply> routes = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> tokens = new CopyOnWriteArrayList<>();
    volatile long delayMs; // lets tests make concurrent requests overlap
    private final HttpServer server;

    StubWeb() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String target = ex.getRequestURI().toString();
            requests.add(target);
            String token = ex.getRequestHeaders().getFirst("X-Subscription-Token");
            if (token != null) {
                tokens.add(token);
            }
            Reply r = routes.entrySet().stream()
                    .filter(e -> target.startsWith(e.getKey()))
                    .max(Comparator.comparingInt(e -> e.getKey().length())) // longest prefix
                    .map(Map.Entry::getValue)
                    .orElse(new Reply(404, "{}"));
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubWeb on(String pathPrefix, String json) {
        routes.put(pathPrefix, new Reply(200, json));
        return this;
    }

    StubWeb fail(String pathPrefix, int status) {
        routes.put(pathPrefix, new Reply(status, "oops"));
        return this;
    }

    static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    WebSearchProperties props(String provider) {
        String u = url();
        return new WebSearchProperties(provider, "test-key", u + "/sx", 2000, 100, null,
                u + "/brave", u + "/wiki", u + "/hn", u + "/ov", u + "/commons");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
