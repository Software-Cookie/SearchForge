package dev.searchforge.api.websearch;

import dev.searchforge.api.TestIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiIntegrationTest {

    static final StubWeb STUB = start();

    static StubWeb start() {
        try {
            return new StubWeb()
                    .on("/wiki/w/api.php?action=query", WebSearchTest.WIKI)
                    .on("/hn/", WebSearchTest.HN)
                    .on("/ov/", WebSearchTest.OPENVERSE)
                    .on("/commons/", WebSearchTest.COMMONS)
                    .on("/wiki/w/api.php?action=opensearch",
                            StubWeb.json("['hou',['House','Houston']]"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("searchforge.index-dir", () -> TestIndex.dir().toString());
        r.add("searchforge.memory-map", () -> "false");
        r.add("searchforge.rate-limit-per-second", () -> "1000");
        r.add("searchforge.rate-limit-burst", () -> "1000");
        r.add("searchforge.web.provider", () -> "open");
        r.add("searchforge.web.wikipedia-url", () -> STUB.url() + "/wiki");
        r.add("searchforge.web.hacker-news-url", () -> STUB.url() + "/hn");
        r.add("searchforge.web.openverse-url", () -> STUB.url() + "/ov");
        r.add("searchforge.web.commons-url", () -> STUB.url() + "/commons");
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesLiveWebAndImageResults() throws Exception {
        HttpResponse<String> web = get("/api/web?q=house");
        assertThat(web.statusCode()).isEqualTo(200);
        assertThat(web.body()).contains("\"source\":\"open\"", "\"type\":\"web\"",
                "https://en.wikipedia.org/wiki/House", "https://blog.example/house");
        assertThat(web.headers().firstValue("cache-control")).hasValue("max-age=300, private");
        assertThat(web.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(v -> assertThat(v).contains("img-src 'self' data: https:"));

        assertThat(get("/api/web?q=house&type=images&page=2").body())
                .contains("\"type\":\"images\"", "https://live.flickr/1.jpg");
        assertThat(get("/api/web/suggest?q=hou").body()).contains("\"house\"", "\"houston\"");
        assertThat(get("/api/web/suggest?q=" + "a".repeat(201)).body())
                .contains("\"suggestions\":[]");
        assertThat(get("/api/web/info").body()).contains("\"provider\":\"open\"");
    }

    @Test
    void validatesParametersAndReportsUpstreamFailures() throws Exception {
        assertThat(get("/api/web").statusCode()).isEqualTo(400);
        assertThat(get("/api/web?q=%20").statusCode()).isEqualTo(400);
        assertThat(get("/api/web?q=" + "a".repeat(201)).statusCode()).isEqualTo(400);
        assertThat(get("/api/web?q=x&type=video").statusCode()).isEqualTo(400);
        assertThat(get("/api/web?q=x&page=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/web?q=x&page=11").statusCode()).isEqualTo(400);

        STUB.fail("/wiki/w/api.php?action=query", 500).fail("/hn/", 500);
        try {
            HttpResponse<String> down = get("/api/web?q=nothing+works");
            assertThat(down.statusCode()).isEqualTo(502);
            assertThat(down.body()).contains("Web results are unavailable")
                    .doesNotContain("Exception", "127.0.0.1");
        } finally {
            STUB.on("/wiki/w/api.php?action=query", WebSearchTest.WIKI)
                    .on("/hn/", WebSearchTest.HN);
        }
    }
}
