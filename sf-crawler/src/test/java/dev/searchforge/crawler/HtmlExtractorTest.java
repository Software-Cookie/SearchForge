package dev.searchforge.crawler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlExtractorTest {

    @Test
    void extractsFieldsAndDropsBoilerplate() {
        String html = """
                <html><head><title> Java Streams </title>
                <script>var secret = 'do not index';</script><style>.x{}</style></head>
                <body><nav>Menu Home About</nav>
                <h1>Streams</h1><h2>Collectors</h2>
                <p>A stream is a sequence of elements.</p>
                <a href="/next.html">next</a>
                <a href="other.html#frag">other</a>
                <a href="/sponsored" rel="sponsored nofollow">ad</a>
                <a href="mailto:x@y.z">mail</a>
                <footer>Copyright</footer></body></html>
                """;
        HtmlExtractor.Extracted e = HtmlExtractor.extract(html, "https://docs.example.com/a/b.html");
        assertThat(e.title()).isEqualTo("Java Streams");
        assertThat(e.headings()).isEqualTo("Streams. Collectors.");
        assertThat(e.body()).contains("A stream is a sequence of elements.")
                .doesNotContain("secret").doesNotContain("Menu").doesNotContain("Copyright");
        assertThat(e.links()).containsExactly("https://docs.example.com/next.html",
                "https://docs.example.com/a/other.html");
        assertThat(e.noindex()).isFalse();
        assertThat(e.nofollow()).isFalse();
    }

    @Test
    void honorsMetaRobotsAndFallsBackToH1Title() {
        String html = "<html><head><meta name=\"robots\" content=\"NOINDEX, nofollow\"></head>"
                + "<body><h1>Only heading</h1><a href=\"/x\">x</a></body></html>";
        HtmlExtractor.Extracted e = HtmlExtractor.extract(html, "https://e.com/");
        assertThat(e.noindex()).isTrue();
        assertThat(e.nofollow()).isTrue();
        assertThat(e.links()).isEmpty();
        assertThat(e.title()).isEqualTo("Only heading");

        String none = "<meta name=\"SearchForgeBot\" content=\"none\"><p>x</p>";
        assertThat(HtmlExtractor.extract(none, "https://e.com/").noindex()).isTrue();
    }

    @Test
    void capsLinksAndBodySize() {
        StringBuilder sb = new StringBuilder("<body>");
        for (int i = 0; i < HtmlExtractor.MAX_LINKS + 50; i++) {
            sb.append("<a href=\"/p").append(i).append("\">l</a>");
        }
        sb.append("<p>").append("word ".repeat(HtmlExtractor.MAX_BODY_CHARS / 4)).append("</p>");
        HtmlExtractor.Extracted e = HtmlExtractor.extract(sb.toString(), "https://e.com/");
        assertThat(e.links()).hasSize(HtmlExtractor.MAX_LINKS);
        assertThat(e.body()).hasSize(HtmlExtractor.MAX_BODY_CHARS);
        assertThat(e.title()).isEmpty();
    }
}
