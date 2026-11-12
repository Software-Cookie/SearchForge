package dev.searchforge.crawler;

// Crawl policy from ADR-000. Validation keeps the polite defaults from being weakened
// against real sites: delays below 2 s are only allowed for private/local test hosts.
public record CrawlerConfig(
        String userAgent,
        long minDelayMs,
        int maxDepth,
        int perHostCap,
        int maxPages,
        int maxBytes,
        int workers,
        boolean allowPrivateHosts,
        boolean stayOnSeedHosts) {

    public static final String DEFAULT_USER_AGENT =
            "SearchForgeBot/1.0 (+https://github.com/Software-Cookie/SearchForge)";
    public static final long POLITE_DELAY_MS = 2_000;
    public static final String AGENT_TOKEN = "searchforgebot";

    public CrawlerConfig {
        if (userAgent == null || !userAgent.toLowerCase().contains(AGENT_TOKEN)) {
            throw new IllegalArgumentException("userAgent must identify SearchForgeBot");
        }
        if (minDelayMs < POLITE_DELAY_MS && !allowPrivateHosts) {
            throw new IllegalArgumentException("minDelayMs must be >= 2000 for public hosts");
        }
        if (minDelayMs < 0 || maxDepth < 0 || perHostCap < 1 || maxPages < 1
                || maxBytes < 1024 || workers < 1) {
            throw new IllegalArgumentException("Invalid crawler limits");
        }
    }

    public static CrawlerConfig defaults() {
        return new CrawlerConfig(DEFAULT_USER_AGENT, POLITE_DELAY_MS, 4, 1_000, 50_000,
                2 * 1024 * 1024, 64, false, true);
    }

    public CrawlerConfig withMaxPages(int pages) {
        return new CrawlerConfig(userAgent, minDelayMs, maxDepth, perHostCap, pages, maxBytes,
                workers, allowPrivateHosts, stayOnSeedHosts);
    }

    public CrawlerConfig withMaxDepth(int depth) {
        return new CrawlerConfig(userAgent, minDelayMs, depth, perHostCap, maxPages, maxBytes,
                workers, allowPrivateHosts, stayOnSeedHosts);
    }

    public CrawlerConfig withUserAgent(String agent) {
        return new CrawlerConfig(agent, minDelayMs, maxDepth, perHostCap, maxPages, maxBytes,
                workers, allowPrivateHosts, stayOnSeedHosts);
    }

    public CrawlerConfig withPerHostCap(int cap) {
        return new CrawlerConfig(userAgent, minDelayMs, maxDepth, cap, maxPages, maxBytes,
                workers, allowPrivateHosts, stayOnSeedHosts);
    }

    public CrawlerConfig withScope(boolean seedHostsOnly) {
        return new CrawlerConfig(userAgent, minDelayMs, maxDepth, perHostCap, maxPages, maxBytes,
                workers, allowPrivateHosts, seedHostsOnly);
    }

    // For crawling a local test server only (localhost / LAN). Allows short delays.
    public CrawlerConfig forPrivateTesting(long delayMs) {
        return new CrawlerConfig(userAgent, delayMs, maxDepth, perHostCap, maxPages, maxBytes,
                workers, true, stayOnSeedHosts);
    }
}
