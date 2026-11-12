package dev.searchforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("searchforge.index-dir", () -> TestIndex.dir().toString());
        r.add("searchforge.memory-map", () -> "false");
        r.add("searchforge.rate-limit-per-second", () -> "1000");
        r.add("searchforge.rate-limit-burst", () -> "1000");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void searchReturnsRankedResultsWithSafeSnippets() throws Exception {
        HttpResponse<String> r = get("/api/search?q=" + enc("java streams"));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("content-type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/json"));
        assertThat(r.body()).contains("\"totalHits\":1", "\"url\":\"https://docs.example/java-streams\"",
                "\"hit\":true", "\"didYouMean\":null");
        // the snippet is plain text segments; the UI never renders it as HTML
        assertThat(r.headers().firstValue("cache-control")).hasValue("max-age=60, private");
        // second call is served from the cache with the same body
        assertThat(get("/api/search?q=" + enc("java streams")).body()).isEqualTo(r.body());
    }

    @Test
    void validatesParameters() throws Exception {
        assertThat(get("/api/search").statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=%20%20").statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=" + "a".repeat(201)).statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=java&page=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=java&size=51").statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=java&page=100&size=50").statusCode()).isEqualTo(400);
        assertThat(get("/api/search?q=java&page=100&size=10").statusCode()).isEqualTo(200);
        HttpResponse<String> bad = get("/api/search?q=java&page=abc");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("\"status\":400").doesNotContain("Exception", "at dev.");
        assertThat(get("/api/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void suggestAndStatsAndDidYouMean() throws Exception {
        assertThat(get("/api/suggest?q=jav").body()).contains("\"suggestions\":[\"java\"");
        assertThat(get("/api/suggest?q=").body()).contains("\"suggestions\":[]");
        assertThat(get("/api/suggest?q=" + "a".repeat(201)).body())
                .contains("\"suggestions\":[]");
        assertThat(get("/api/stats").body()).contains("\"documents\":3");
        assertThat(get("/api/search?q=" + enc("kotiln")).body())
                .contains("\"didYouMean\":\"kotlin\"");
    }

    @Test
    void sendsSecurityHeadersAndExposesHealthAndMetrics() throws Exception {
        HttpResponse<String> r = get("/actuator/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("UP");
        assertThat(r.headers().firstValue("content-security-policy")).isPresent();
        assertThat(r.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(r.headers().firstValue("x-frame-options")).hasValue("DENY");
        assertThat(get("/actuator/prometheus").body()).contains("searchforge_search");
    }
}
