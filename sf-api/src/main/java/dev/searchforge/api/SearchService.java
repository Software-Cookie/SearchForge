package dev.searchforge.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.searchforge.query.SearchEngine;
import dev.searchforge.query.SearchResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Wraps the engine with a result cache (Caffeine, W-TinyLFU eviction) and a per-query
// deadline. The index is immutable, so cached results never go stale until a redeploy.
@Service
public class SearchService {

    private record Key(String query, int page, int size) {
    }

    private final SearchEngine engine;
    private final SearchForgeProperties props;
    private final Cache<Key, SearchResult> cache;
    private final Timer searchTimer;

    public SearchService(SearchEngine engine, SearchForgeProperties props,
                         MeterRegistry registry) {
        this.engine = engine;
        this.props = props;
        this.cache = Caffeine.newBuilder()
                .maximumSize(props.cacheSize())
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "search");
        this.searchTimer = Timer.builder("searchforge.search")
                .description("Search latency, cache misses only")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public SearchResult search(String query, int page, int size) {
        String normalized = query.strip().replaceAll("\\s+", " ");
        Key key = new Key(normalized, page, size); // case kept: "OR" is an operator
        SearchResult cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(props.queryTimeoutMs());
        SearchResult result = engine.search(normalized, page, size, deadline);
        searchTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        cache.put(key, result);
        return result;
    }

    public List<String> suggest(String prefix) {
        return engine.suggest(prefix, 8);
    }

    public SearchEngine engine() {
        return engine;
    }
}
