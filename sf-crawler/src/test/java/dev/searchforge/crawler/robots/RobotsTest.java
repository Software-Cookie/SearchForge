package dev.searchforge.crawler.robots;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTest {

    private static final String AGENT = "searchforgebot";

    @Test
    void longestMatchWinsAndAllowWinsTies() {
        RobotsRules r = RobotsTxtParser.parse("""
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                Disallow: /tie
                Allow: /tie
                """, AGENT);
        assertThat(r.isAllowed("/docs/secret.html")).isFalse();
        assertThat(r.isAllowed("/docs/public/a.html")).isTrue();
        assertThat(r.isAllowed("/tie")).isTrue();
        assertThat(r.isAllowed("/other")).isTrue();
        assertThat(r.isAllowed("/robots.txt")).isTrue();
        assertThat(r.isAllowed("")).isTrue();
    }

    @Test
    void specificGroupOverridesStarAndGroupsMerge() {
        RobotsRules r = RobotsTxtParser.parse("""
                # comment line
                User-agent: *
                Disallow: /

                User-agent: Googlebot
                User-agent: SearchForgeBot/1.0
                Disallow: /private  # trailing comment
                Crawl-delay: 5

                User-agent: searchforgebot
                Disallow: /tmp
                """, AGENT);
        assertThat(r.isAllowed("/public")).isTrue();
        assertThat(r.isAllowed("/private/x")).isFalse();
        assertThat(r.isAllowed("/tmp/y")).isFalse();
        assertThat(r.crawlDelayMs()).isEqualTo(5_000);
    }

    @Test
    void wildcardsAndEndAnchor() {
        RobotsRules r = RobotsTxtParser.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /search*q=
                Disallow: /exact$
                """, AGENT);
        assertThat(r.isAllowed("/files/a.pdf")).isFalse();
        assertThat(r.isAllowed("/files/a.pdf?x=1")).isTrue();
        assertThat(r.isAllowed("/search?lang=en&q=java")).isFalse();
        assertThat(r.isAllowed("/search?lang=en")).isTrue();
        assertThat(r.isAllowed("/exact")).isFalse();
        assertThat(r.isAllowed("/exactly")).isTrue();
        assertThat(RobotsRules.matches("/a*", "/a")).isTrue();
        assertThat(RobotsRules.matches("*", "/anything")).isTrue();
    }

    @Test
    void emptyDisallowAndMissingGroupsAllowEverything() {
        assertThat(RobotsTxtParser.parse("User-agent: *\nDisallow:\n", AGENT).isAllowed("/x"))
                .isTrue();
        assertThat(RobotsTxtParser.parse("User-agent: other\nDisallow: /\n", AGENT)
                .isAllowed("/x")).isTrue();
        assertThat(RobotsTxtParser.parse(null, AGENT).isAllowed("/x")).isTrue();
        assertThat(RobotsTxtParser.parse("Disallow: /\nnonsense line\n", AGENT).isAllowed("/x"))
                .isTrue();
        assertThat(RobotsTxtParser.parse("User-agent:\nDisallow: /\n", AGENT).isAllowed("/x"))
                .isTrue();
    }

    @Test
    void malformedCrawlDelayIsIgnored() {
        RobotsRules r = RobotsTxtParser.parse("""
                User-agent: *
                Crawl-delay: soon
                Crawl-delay: -4
                Crawl-delay: 1.5
                Sitemap: https://example.com/sitemap.xml
                """, AGENT);
        assertThat(r.crawlDelayMs()).isEqualTo(1_500);
        assertThat(RobotsRules.DISALLOW_ALL.isAllowed("/a")).isFalse();
        assertThat(RobotsRules.ALLOW_ALL.crawlDelayMs()).isEqualTo(-1);
    }

    @Test
    void cacheReusesRulesUntilTtlAndEvictsLeastRecentlyUsed() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(0);
        RobotsCache cache = new RobotsCache(origin -> {
            loads.incrementAndGet();
            return new RobotsRules(List.of(), -1);
        }, 1_000, 2, now::get);
        cache.rulesFor("http://a");
        cache.rulesFor("http://a");
        assertThat(loads).hasValue(1);
        now.set(1_001);
        cache.rulesFor("http://a");
        assertThat(loads).hasValue(2);
        cache.rulesFor("http://b");
        cache.rulesFor("http://c");
        assertThat(cache.size()).isEqualTo(2);
    }
}
