package dev.searchforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProdProfileTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("searchforge.index-dir", () -> TestIndex.dir().toString());
        r.add("searchforge.memory-map", () -> "false");
        r.add("searchforge.rate-limit-per-second", () -> "1");
        r.add("searchforge.rate-limit-burst", () -> "3");
    }

    @Value("${local.server.port}")
    int port;

    private int status(String path, String xff, String proto) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (xff != null) {
            b.header("X-Forwarded-For", xff);
        }
        if (proto != null) {
            b.header("X-Forwarded-Proto", proto);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    void metricsArePrivateAndRateLimitUsesTheRealClientIp() throws Exception {
        assertThat(status("/actuator/health", null, "https")).isEqualTo(200);
        assertThat(status("/actuator/prometheus", null, null)).isEqualTo(404);

        // Visitor 1.1.1.1 behind one proxy; a spoofed left-most entry must not help.
        for (int i = 0; i < 3; i++) {
            assertThat(status("/api/search?q=java", "9.9.9." + i + ", 1.1.1.1", null))
                    .isEqualTo(200);
        }
        assertThat(status("/api/search?q=java", "8.8.8.8, 1.1.1.1", null)).isEqualTo(429);
        // A different visitor has its own bucket.
        assertThat(status("/api/search?q=java", "2.2.2.2", null)).isEqualTo(200);
        // Static pages are never rate limited.
        assertThat(status("/actuator/health", "1.1.1.1", null)).isEqualTo(200);
    }
}
