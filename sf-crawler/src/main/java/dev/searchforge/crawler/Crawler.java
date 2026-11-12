package dev.searchforge.crawler;

import dev.searchforge.core.dedup.SimHash;
import dev.searchforge.core.page.PageRecord;
import dev.searchforge.core.page.PageStore;
import dev.searchforge.core.url.UrlNormalizer;
import dev.searchforge.crawler.fetch.FetchResult;
import dev.searchforge.crawler.fetch.PageFetcher;
import dev.searchforge.crawler.frontier.CrawlUrl;
import dev.searchforge.crawler.frontier.UrlFrontier;
import dev.searchforge.crawler.robots.RobotsCache;
import dev.searchforge.crawler.robots.RobotsRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

// The crawl loop: take URL -> robots check -> fetch -> dedupe -> store -> enqueue links.
// Workers are virtual threads; politeness is enforced by the frontier, not by sleeping.
public final class Crawler {

    private static final Logger LOG = LoggerFactory.getLogger(Crawler.class);

    public record Stats(long stored, long fetched, long robotsBlocked, long duplicates,
                        long errors, long durationMs) {
    }

    private final CrawlerConfig config;
    private final PageFetcher fetcher;
    private final RobotsCache robots;
    private final PageStore store;
    private final UrlFrontier frontier;
    private final SimHash simHash = new SimHash();
    private final Set<String> scopeHosts = new HashSet<>();
    private final AtomicLong fetched = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong dups = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public Crawler(CrawlerConfig config, PageFetcher fetcher, RobotsCache robots,
                   PageStore store, UrlFrontier frontier) {
        this.config = config;
        this.fetcher = fetcher;
        this.robots = robots;
        this.store = store;
        this.frontier = frontier;
    }

    public Stats run(List<String> seeds, List<PageRecord> alreadyStored)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        for (String s : seeds) {
            UrlNormalizer.normalize(s).ifPresent(u -> scopeHosts.add(UrlNormalizer.host(u)));
        }
        resume(seeds, alreadyStored);
        seeds.forEach(s -> UrlNormalizer.normalize(s).ifPresent(u -> enqueue(u, 0)));
        if (store.count() >= config.maxPages()) {
            frontier.close();
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < config.workers(); i++) {
                pool.submit(this::workerLoop);
            }
        }
        try {
            store.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Stats(store.count(), fetched.get(), blocked.get(), dups.get(), errors.get(),
                System.currentTimeMillis() - start);
    }

    // Resume: skip stored URLs, remember their fingerprints, and continue from their links.
    // Depths are recomputed by BFS from the seeds over the stored link graph; stored pages
    // reached only through a redirect get depth 1.
    private void resume(List<String> seeds, List<PageRecord> stored) {
        Map<String, PageRecord> byUrl = new HashMap<>();
        for (PageRecord p : stored) {
            frontier.markSeen(p.url());
            simHash.addIfNew(SimHash.fingerprint(tokens(p.body())));
            byUrl.put(p.url(), p);
        }
        Map<String, Integer> depth = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String s : seeds) {
            UrlNormalizer.normalize(s).filter(byUrl::containsKey).ifPresent(u -> {
                depth.put(u, 0);
                queue.add(u);
            });
        }
        while (!queue.isEmpty()) {
            String u = queue.poll();
            for (String link : byUrl.get(u).outlinks()) {
                if (byUrl.containsKey(link) && !depth.containsKey(link)) {
                    depth.put(link, depth.get(u) + 1);
                    queue.add(link);
                }
            }
        }
        for (PageRecord p : stored) {
            int d = depth.getOrDefault(p.url(), 1);
            if (d < config.maxDepth()) {
                p.outlinks().forEach(link -> enqueue(link, d + 1));
            }
        }
    }

    private static List<String> tokens(String body) {
        return Arrays.asList(body.toLowerCase(Locale.ROOT).split("\\W+"));
    }

    private Void workerLoop() throws InterruptedException {
        CrawlUrl url;
        while ((url = frontier.take()) != null) {
            long delay = config.minDelayMs();
            try {
                delay = process(url);
            } catch (RuntimeException | IOException e) {
                errors.incrementAndGet();
                LOG.warn("Failed {}: {}", url.url(), e.toString());
            } finally {
                frontier.complete(url.host(), delay);
            }
        }
        return null;
    }

    // Returns the politeness delay to apply to this host before its next request.
    private long process(CrawlUrl url) throws IOException {
        URI uri = URI.create(url.url());
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        RobotsRules rules = robots.rulesFor(origin);
        long delay = Math.max(config.minDelayMs(), rules.crawlDelayMs());
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? ""
                : "?" + uri.getRawQuery());
        if (!rules.isAllowed(pathAndQuery)) {
            blocked.incrementAndGet();
            return 0; // nothing was requested from the host
        }
        FetchResult res = fetcher.fetch(url.url());
        fetched.incrementAndGet();
        switch (res.kind()) {
            case REDIRECT -> UrlNormalizer.resolve(url.url(), res.location())
                    .ifPresent(target -> enqueue(target, url.depth()));
            case OK -> handlePage(url, res.body());
            case BLOCKED -> blocked.incrementAndGet();
            default -> errors.incrementAndGet();
        }
        return delay;
    }

    private void handlePage(CrawlUrl url, String html) throws IOException {
        HtmlExtractor.Extracted page = HtmlExtractor.extract(html, url.url());
        if (url.depth() < config.maxDepth()) {
            page.links().forEach(link -> enqueue(link, url.depth() + 1));
        }
        if (page.noindex()) {
            return;
        }
        if (!simHash.addIfNew(SimHash.fingerprint(tokens(page.body())))) {
            dups.incrementAndGet();
            return;
        }
        synchronized (store) {
            if (store.count() >= config.maxPages()) {
                frontier.close();
                return;
            }
            store.append(new PageRecord(url.url(), page.title(), page.headings(), page.body(),
                    new ArrayList<>(page.links()), System.currentTimeMillis()));
            long n = store.count();
            if (n % 100 == 0) {
                store.flush(); // checkpoint: a crash loses at most ~100 pages
                LOG.info("Stored {} pages, {} queued", n, frontier.queued());
            }
            if (n >= config.maxPages()) {
                frontier.close();
            }
        }
    }

    private void enqueue(String url, int depth) {
        String host = UrlNormalizer.host(url);
        if (host == null || (config.stayOnSeedHosts() && !scopeHosts.contains(host))) {
            return;
        }
        frontier.add(new CrawlUrl(url, host, depth));
    }
}
