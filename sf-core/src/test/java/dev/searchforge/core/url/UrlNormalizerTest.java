package dev.searchforge.core.url;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlNormalizerTest {

    @ParameterizedTest
    @CsvSource({
        "HTTP://Example.COM, http://example.com/",
        "https://example.com:443/a/./b/../c, https://example.com/a/c",
        "http://example.com:80/x#frag, http://example.com/x",
        "http://example.com:8080/x, http://example.com:8080/x",
        "https://example.com./p?utm_source=x&a=1&fbclid=2, https://example.com/p?a=1",
        "https://example.com/p?utm_medium=y, https://example.com/p",
        "https://example.com/p?&a=1, https://example.com/p?a=1",
        "https://example.com/../a, https://example.com/a"
    })
    void normalizes(String in, String expected) {
        assertThat(UrlNormalizer.normalize(in)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "  ", "mailto:bob@example.com", "javascript:alert(1)", "ftp://example.com/",
        "http://user:pw@example.com/", "not a url", "/relative/only", "http:///nohost"
    })
    void rejects(String in) {
        assertThat(UrlNormalizer.normalize(in)).isEmpty();
    }

    @Test
    void rejectsNullAndOverlongUrls() {
        assertThat(UrlNormalizer.normalize(null)).isEmpty();
        assertThat(UrlNormalizer.normalize("https://e.com/" + "a".repeat(3000))).isEmpty();
    }

    @Test
    void resolvesRelativeLinks() {
        String base = "https://docs.example.com/guide/intro.html";
        assertThat(UrlNormalizer.resolve(base, "next.html"))
                .contains("https://docs.example.com/guide/next.html");
        assertThat(UrlNormalizer.resolve(base, "../api/"))
                .contains("https://docs.example.com/api/");
        assertThat(UrlNormalizer.resolve(base, "//other.org/x"))
                .contains("https://other.org/x");
        assertThat(UrlNormalizer.resolve(base, "#top"))
                .contains("https://docs.example.com/guide/intro.html");
        assertThat(UrlNormalizer.resolve(base, " ")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, null)).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "http://[bad")).isEmpty();
    }

    @Test
    void extractsHost() {
        assertThat(UrlNormalizer.host("https://docs.example.com/a")).isEqualTo("docs.example.com");
    }
}
