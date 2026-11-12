package dev.searchforge.crawler;

import dev.searchforge.core.dedup.BloomFilter;
import dev.searchforge.core.page.PageRecord;
import dev.searchforge.core.page.PageStore;
import dev.searchforge.crawler.fetch.HostGuard;
import dev.searchforge.crawler.fetch.PageFetcher;
import dev.searchforge.crawler.frontier.UrlFrontier;
import dev.searchforge.crawler.robots.RobotsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Command line:
//   --seeds <file> --out <dir> [--max-pages N] [--max-depth N] [--per-host-cap N]
//   [--user-agent "SearchForgeBot/1.0 (+repo; email)"] [--any-host]
//   [--allow-private --delay-ms N]   (local test servers only)
// Running again with the same --out resumes the crawl.
public final class CrawlerMain {

    private static final Logger LOG = LoggerFactory.getLogger(CrawlerMain.class);
    private static final long ROBOTS_TTL_MS = 24L * 60 * 60 * 1000;

    private CrawlerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) throws IOException, InterruptedException {
        Map<String, String> opts;
        try {
            opts = parse(args);
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            out.println(usage());
            return 2;
        }
        if (!opts.containsKey("seeds") || !opts.containsKey("out")) {
            out.println(usage());
            return 2;
        }
        CrawlerConfig config;
        try {
            config = configFrom(opts);
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            return 2;
        }
        List<String> seeds = Files.readAllLines(Path.of(opts.get("seeds"))).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        Path outDir = Path.of(opts.get("out"));
        List<PageRecord> existing = PageStore.readAll(outDir);

        BloomFilter seen = new BloomFilter(Math.max(10_000L, config.maxPages() * 50L), 0.001);
        UrlFrontier frontier = new UrlFrontier(seen, config.perHostCap(),
                System::currentTimeMillis);
        PageFetcher fetcher = new PageFetcher(config, new HostGuard(config.allowPrivateHosts()));
        RobotsCache robots = new RobotsCache(fetcher::fetchRobots, ROBOTS_TTL_MS, 100_000,
                System::currentTimeMillis);
        try (PageStore store = PageStore.openForAppend(outDir)) {
            Thread hook = new Thread(frontier::close);
            Runtime.getRuntime().addShutdownHook(hook);
            LOG.info("Crawling {} seeds into {} (resuming with {} pages)", seeds.size(), outDir,
                    existing.size());
            Crawler.Stats stats = new Crawler(config, fetcher, robots, store, frontier)
                    .run(seeds, existing);
            Runtime.getRuntime().removeShutdownHook(hook);
            out.printf("stored=%d fetched=%d robotsBlocked=%d duplicates=%d errors=%d "
                            + "seconds=%.1f%n", stats.stored(), stats.fetched(),
                    stats.robotsBlocked(), stats.duplicates(), stats.errors(),
                    stats.durationMs() / 1000.0);
        }
        return 0;
    }

    static CrawlerConfig configFrom(Map<String, String> opts) {
        CrawlerConfig c = CrawlerConfig.defaults();
        if (opts.containsKey("user-agent")) {
            c = c.withUserAgent(opts.get("user-agent"));
        }
        if (opts.containsKey("max-pages")) {
            c = c.withMaxPages(Integer.parseInt(opts.get("max-pages")));
        }
        if (opts.containsKey("max-depth")) {
            c = c.withMaxDepth(Integer.parseInt(opts.get("max-depth")));
        }
        if (opts.containsKey("per-host-cap")) {
            c = c.withPerHostCap(Integer.parseInt(opts.get("per-host-cap")));
        }
        if (opts.containsKey("any-host")) {
            c = c.withScope(false);
        }
        if (opts.containsKey("allow-private")) {
            c = c.forPrivateTesting(Long.parseLong(opts.getOrDefault("delay-ms", "2000")));
        } else if (opts.containsKey("delay-ms")) {
            throw new IllegalArgumentException("--delay-ms is only allowed with --allow-private");
        }
        return c;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + a);
            }
            String key = a.substring(2);
            boolean flag = key.equals("any-host") || key.equals("allow-private");
            if (flag) {
                opts.put(key, "true");
            } else if (i + 1 < args.length) {
                opts.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("Missing value for " + a);
            }
        }
        return opts;
    }

    static String usage() {
        return "Usage: --seeds <file> --out <dir> [--max-pages N] [--max-depth N] "
                + "[--per-host-cap N] [--user-agent UA] [--any-host] "
                + "[--allow-private --delay-ms N]";
    }
}
