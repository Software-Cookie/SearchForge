package dev.searchforge.crawler;

import dev.searchforge.core.dedup.BloomFilter;
import dev.searchforge.core.page.PageRecord;
import dev.searchforge.core.page.PageStore;
import dev.searchforge.crawler.fetch.HostGuard;
import dev.searchforge.crawler.fetch.PageFetcher;
import dev.searchforge.crawler.frontier.UrlFrontier;
import dev.searchforge.crawler.robots.RobotsCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlerTest {

    @TempDir
    Path dir;

    static TestSite site() throws java.io.IOException {
        TestSite s = new TestSite();
        // Realistic pages have many distinct words; SimHash is tuned for that.
        String filler = java.util.stream.IntStream.range(0, 1500)
                .mapToObj(i -> " word" + i).collect(java.util.stream.Collectors.joining());
        s.route("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /private\n", null);
        s.html("/", "<title>Home</title><p>welcome to the home page of this small test site</p>"
                + "<a href='/a'>a</a><a href='/b'>b</a><a href='/private/x'>p</a>"
                + "<a href='/old'>old</a><a href='/hidden'>h</a><a href='/file.pdf'>pdf</a>"
                + "<a href='https://elsewhere.example.org/'>out of scope</a>");
        s.html("/a", "<title>Page A</title><p>alpha streams java" + filler
                + "</p><a href='/c'>c</a>");
        s.html("/b", "<title>Page B</title><p>alpha streams java" + filler
                + "</p>"); // near-duplicate of /a
        s.html("/c", "<title>Page C</title><p>gamma depth two pythons are great " + "x ".repeat(50)
                + "</p><a href='/d'>d</a>");
        s.html("/d", "<title>Page D</title><p>too deep</p>");
        s.route("/old", 301, "text/html", "", "/new");
        s.html("/new", "<title>New</title><p>redirect target with different words entirely</p>");
        s.html("/hidden", "<meta name='robots' content='noindex'><a href='/e'>e</a>");
        s.html("/e", "<title>E</title><p>found through a noindex page epsilon</p>");
        s.route("/file.pdf", 200, "application/pdf", "%PDF", null);
        return s;
    }

    private Crawler.Stats crawl(TestSite s, CrawlerConfig config, List<PageRecord> existing)
            throws Exception {
        UrlFrontier frontier = new UrlFrontier(new BloomFilter(10_000, 0.001),
                config.perHostCap(), System::currentTimeMillis);
        PageFetcher fetcher = new PageFetcher(config, new HostGuard(true));
        RobotsCache robots = new RobotsCache(fetcher::fetchRobots, 60_000, 100,
                System::currentTimeMillis);
        try (PageStore store = PageStore.openForAppend(dir)) {
            return new Crawler(config, fetcher, robots, store, frontier)
                    .run(List.of(s.base() + "/"), existing);
        }
    }

    @Test
    @Timeout(30)
    void crawlsPolitelyWithinScopeAndPolicy() throws Exception {
        try (TestSite s = site()) {
            CrawlerConfig config = CrawlerConfig.defaults().forPrivateTesting(5).withMaxDepth(2);
            Crawler.Stats stats = crawl(s, config, List.of());

            List<String> urls = PageStore.readAll(dir).stream().map(PageRecord::url).toList();
            String b = s.base();
            assertThat(urls).contains(b + "/", b + "/a", b + "/c", b + "/new")
                    .doesNotContain(b + "/b", b + "/d", b + "/private/x", b + "/hidden")
                    .hasSize(5);
            assertThat(urls).contains(b + "/e"); // links of noindex pages are followed
            assertThat(s.requests).doesNotContain("/private/x", "/d");
            assertThat(s.requests.get(0)).isEqualTo("/robots.txt");
            assertThat(stats.duplicates()).isEqualTo(1);
            assertThat(stats.robotsBlocked()).isEqualTo(1);
            assertThat(stats.errors()).isGreaterThanOrEqualTo(1); // the PDF
            assertThat(stats.stored()).isEqualTo(5);
        }
    }

    @Test
    @Timeout(30)
    void stopsAtMaxPagesAndResumesWhereItLeftOff() throws Exception {
        try (TestSite s = site()) {
            CrawlerConfig config = CrawlerConfig.defaults().forPrivateTesting(5).withMaxDepth(2)
                    .withMaxPages(2);
            crawl(s, config, List.of());
            assertThat(PageStore.readAll(dir)).hasSize(2);

            List<PageRecord> existing = PageStore.readAll(dir);
            s.requests.clear();
            crawl(s, config.withMaxPages(10), existing);
            List<PageRecord> all = PageStore.readAll(dir);
            assertThat(all).hasSize(5);
            assertThat(all.stream().map(PageRecord::url).distinct()).hasSize(5);
            assertThat(s.requests).doesNotContain("/");
        }
    }

    @Test
    void configRejectsImpoliteSettings() {
        assertThatThrownBy(() -> new CrawlerConfig("Mozilla/5.0", 2000, 4, 10, 10, 4096, 1,
                false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrawlerConfig(null, 2000, 4, 10, 10, 4096, 1,
                false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrawlerConfig(CrawlerConfig.DEFAULT_USER_AGENT, 100, 4, 10,
                10, 4096, 1, false, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CrawlerConfig.defaults().withMaxPages(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CrawlerConfig.defaults().withScope(false).stayOnSeedHosts()).isFalse();
        assertThat(CrawlerConfig.defaults().withPerHostCap(3).perHostCap()).isEqualTo(3);
    }

    @Test
    @Timeout(30)
    void commandLineRunsACrawlAndValidatesArguments() throws Exception {
        try (TestSite s = site()) {
            Path seeds = dir.resolve("seeds.txt");
            Files.writeString(seeds, "# seeds\n\n" + s.base() + "/\n");
            Path out = dir.resolve("crawl");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int code = CrawlerMain.run(new String[] {"--seeds", seeds.toString(), "--out",
                out.toString(), "--max-pages", "3", "--max-depth", "2", "--per-host-cap", "50",
                "--allow-private", "--delay-ms", "5", "--user-agent",
                "SearchForgeBot/1.0 (+test)"}, new PrintStream(buf, true, StandardCharsets.UTF_8));
            assertThat(code).isZero();
            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("stored=3");
            assertThat(PageStore.readAll(out)).hasSize(3);
        }
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
        assertThat(CrawlerMain.run(new String[] {}, sink)).isEqualTo(2);
        assertThat(CrawlerMain.run(new String[] {"stray"}, sink)).isEqualTo(2);
        assertThat(CrawlerMain.run(new String[] {"--seeds"}, sink)).isEqualTo(2);
        assertThat(CrawlerMain.run(new String[] {"--seeds", "s", "--out", "o", "--delay-ms", "5"},
                sink)).isEqualTo(2);
        assertThat(CrawlerMain.configFrom(Map.of("any-host", "true")).stayOnSeedHosts())
                .isFalse();
    }
}
