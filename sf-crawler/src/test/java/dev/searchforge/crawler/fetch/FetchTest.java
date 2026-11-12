package dev.searchforge.crawler.fetch;

import dev.searchforge.crawler.CrawlerConfig;
import dev.searchforge.crawler.TestSite;
import dev.searchforge.crawler.robots.RobotsRules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FetchTest {

    private static final CrawlerConfig LOCAL = CrawlerConfig.defaults().forPrivateTesting(0);

    @Test
    void hostGuardBlocksPrivateAndLocalAddresses() throws UnknownHostException {
        HostGuard strict = new HostGuard(false);
        assertThat(strict.isAllowed("localhost")).isFalse();
        assertThat(strict.isAllowed("127.0.0.1")).isFalse();
        assertThat(strict.isAllowed("")).isFalse();
        assertThat(strict.isAllowed(null)).isFalse();
        assertThat(strict.isAllowed("no-such-host.invalid")).isFalse();
        for (String ip : new String[] {"10.1.2.3", "192.168.0.1", "172.16.5.4", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "198.18.0.1", "240.0.0.1", "::1", "fd00::1"}) {
            assertThat(HostGuard.isNonPublic(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        assertThat(HostGuard.isNonPublic(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(HostGuard.isNonPublic(InetAddress.getByName("2606:4700::1"))).isFalse();

        HostGuard publicDns = new HostGuard(false,
                h -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        assertThat(publicDns.isAllowed("example.com")).isTrue();
        assertThat(new HostGuard(false, h -> new InetAddress[0]).isAllowed("x.com")).isFalse();
        assertThat(new HostGuard(true).isAllowed("localhost")).isTrue();
    }

    @Test
    void fetchesHtmlAndClassifiesOtherResponses() throws IOException {
        try (var site = new TestSite()) {
            site.html("/ok", "<html><title>Hi</title></html>");
            site.route("/moved", 301, "text/html", "", "/ok");
            site.route("/moved-nowhere", 302, "text/html", "", null);
            site.route("/pdf", 200, "application/pdf", "%PDF", null);
            site.route("/big", 200, "text/html", "x".repeat(5_000), null);
            site.route("/latin", 200, "text/html; charset=\"ISO-8859-1\"", "café", null);
            PageFetcher f = new PageFetcher(LOCAL.withMaxPages(10), new HostGuard(true));

            FetchResult ok = f.fetch(site.base() + "/ok");
            assertThat(ok.kind()).isEqualTo(FetchResult.Kind.OK);
            assertThat(ok.body()).contains("<title>Hi</title>");
            FetchResult moved = f.fetch(site.base() + "/moved");
            assertThat(moved.kind()).isEqualTo(FetchResult.Kind.REDIRECT);
            assertThat(moved.location()).isEqualTo("/ok");
            assertThat(f.fetch(site.base() + "/moved-nowhere").kind())
                    .isEqualTo(FetchResult.Kind.HTTP_ERROR);
            assertThat(f.fetch(site.base() + "/missing").status()).isEqualTo(404);
            assertThat(f.fetch(site.base() + "/pdf").kind()).isEqualTo(FetchResult.Kind.NOT_HTML);
            // The server sends UTF-8 bytes but declares ISO-8859-1: the declared charset wins.
            assertThat(f.fetch(site.base() + "/latin").body()).isEqualTo("cafÃ©");
            assertThat(f.fetch("http://127.0.0.1:1/").kind()).isEqualTo(FetchResult.Kind.ERROR);
            assertThat(site.userAgents).allMatch(ua -> ua.contains("SearchForgeBot"));

            PageFetcher strict = new PageFetcher(CrawlerConfig.defaults(), new HostGuard(false));
            assertThat(strict.fetch(site.base() + "/ok").kind())
                    .isEqualTo(FetchResult.Kind.BLOCKED);
            assertThat(strict.fetchRobots(site.base())).isEqualTo(RobotsRules.DISALLOW_ALL);
        }
    }

    @Test
    void truncatesOversizedBodies() throws IOException {
        try (var site = new TestSite()) {
            site.route("/big", 200, "text/html", "y".repeat(3_000), null);
            CrawlerConfig small = new CrawlerConfig(CrawlerConfig.DEFAULT_USER_AGENT, 0, 2, 10, 10,
                    1024, 1, true, true);
            FetchResult r = new PageFetcher(small, new HostGuard(true)).fetch(site.base() + "/big");
            assertThat(r.body()).hasSize(1024);
        }
    }

    @Test
    void robotsStatusHandlingFollowsRfc9309() throws IOException {
        try (var site = new TestSite()) {
            PageFetcher f = new PageFetcher(LOCAL, new HostGuard(true));
            assertThat(f.fetchRobots(site.base()).isAllowed("/x")).isTrue(); // 404

            site.route("/robots.txt", 503, "text/plain", "down", null);
            assertThat(f.fetchRobots(site.base()).isAllowed("/x")).isFalse();

            site.route("/robots.txt", 301, "text/plain", "", "/real-robots.txt");
            site.route("/real-robots.txt", 200, "text/plain",
                    "User-agent: *\nDisallow: /x\n", null);
            RobotsRules rules = f.fetchRobots(site.base());
            assertThat(rules.isAllowed("/x")).isFalse();
            assertThat(rules.isAllowed("/y")).isTrue();

            site.route("/robots.txt", 301, "text/plain", "", "/robots.txt");
            assertThat(f.fetchRobots(site.base())).isEqualTo(RobotsRules.DISALLOW_ALL);
            assertThat(f.fetchRobots("http://127.0.0.1:1")).isEqualTo(RobotsRules.DISALLOW_ALL);
        }
    }

    @Test
    void parsesCharsetsDefensively() {
        assertThat(PageFetcher.charsetOf("text/html")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(PageFetcher.charsetOf("text/html; charset=iso-8859-1").name())
                .isEqualTo("ISO-8859-1");
        assertThat(PageFetcher.charsetOf("text/html; charset=bogus-xyz"))
                .isEqualTo(StandardCharsets.UTF_8);
    }
}
