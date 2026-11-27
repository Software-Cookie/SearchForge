package dev.searchforge.api.websearch;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Live internet search: picks a provider at startup, caches answers for 15 minutes (results
// change slowly and every upstream call costs quota) and supplies autocomplete.
@Service
public class WebSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(WebSearchService.class);
    private static final ExecutorService LOADERS = Executors.newVirtualThreadPerTaskExecutor();

    private record Key(String type, String query, int page) {
    }

    private final WebSearchProvider provider;
    private final Http http;
    private final WebSearchProperties props;
    // Async caches hold one in-flight future per key, so concurrent identical searches wait
    // on that future (no lock held while the upstream call runs) and share its answer.
    private final AsyncCache<Key, WebSearchResponse> cache;
    private final AsyncCache<String, List<String>> suggestCache;
    private final Timer timer;

    public WebSearchService(WebSearchProperties props, MeterRegistry registry) {
        this.props = props;
        this.http = new Http(props);
        this.provider = switch (props.effectiveProvider()) {
            case "brave" -> new BraveProvider(http, props);
            case "searxng" -> new SearxngProvider(http, props);
            default -> new OpenProvider(http, props);
        };
        this.cache = Caffeine.newBuilder().maximumSize(props.cacheSize()).executor(LOADERS)
                .expireAfterWrite(Duration.ofMinutes(15)).recordStats().buildAsync();
        this.suggestCache = Caffeine.newBuilder().maximumSize(props.cacheSize()).executor(LOADERS)
                .expireAfterWrite(Duration.ofHours(1)).buildAsync();
        CaffeineCacheMetrics.monitor(registry, cache, "web");
        this.timer = Timer.builder("searchforge.web")
                .description("Live web search latency, cache misses only")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        LOG.info("Live web search provider: {}", provider.name());
    }

    public String providerName() {
        return provider.name();
    }

    public WebSearchResponse search(String query, String type, int page) {
        String q = query.strip().replaceAll("\\s+", " ");
        Key key = new Key(type, q.toLowerCase(Locale.ROOT), page);
        // A thousand people searching the same trending word at the same moment cause one
        // upstream call, not a thousand.
        WebSearchResponse res = await(cache.get(key, k -> fetch(q, type, page)));
        if (res.results().isEmpty() && res.images().isEmpty()) {
            cache.synchronous().invalidate(key); // an empty answer may be a transient glitch
        }
        return res;
    }

    private WebSearchResponse fetch(String q, String type, int page) {
        long start = System.nanoTime();
        WebSearchResponse res;
        if ("images".equals(type)) {
            ImagePage p = provider.images(q, page);
            res = new WebSearchResponse(q, type, page, ms(start), provider.name(), p.hasMore(),
                    null, List.of(), p.images());
        } else {
            WebPage p = provider.web(q, page);
            res = new WebSearchResponse(q, type, page, ms(start), provider.name(), p.hasMore(),
                    p.didYouMean(), p.hits(), List.of());
        }
        timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return res;
    }

    // Wikipedia's title search: free, fast and has no usage key.
    public List<String> suggest(String prefix) {
        String p = prefix.strip().toLowerCase(Locale.ROOT);
        if (p.length() < 2) {
            return List.of();
        }
        return await(suggestCache.get(p, k -> {
            try {
                JsonNode root = http.getJson(props.wikipediaUrl()
                        + "/w/api.php?action=opensearch&format=json&namespace=0&limit=8&search="
                        + Http.enc(k));
                List<String> out = new ArrayList<>();
                for (JsonNode s : root.path(1)) {
                    String v = s.asString("").toLowerCase(Locale.ROOT);
                    if (!v.isBlank() && !v.equals(k) && !out.contains(v)) {
                        out.add(v);
                    }
                }
                return List.copyOf(out);
            } catch (UpstreamException e) {
                return List.of(); // suggestions are optional
            }
        }));
    }

    // The future's value, rethrowing the loader's own exception (an UpstreamException).
    private static <T> T await(java.util.concurrent.CompletableFuture<T> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw e;
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
