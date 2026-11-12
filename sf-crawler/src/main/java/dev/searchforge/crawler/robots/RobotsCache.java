package dev.searchforge.crawler.robots;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

// Caches robots rules per origin (scheme://host[:port]) for a TTL (24 h by default).
// Bounded LRU so a crawl over many hosts cannot grow it without limit.
public final class RobotsCache {

    private record Entry(RobotsRules rules, long expiresAtMs) {
    }

    private final Function<String, RobotsRules> loader;
    private final long ttlMs;
    private final LongSupplier clock;
    private final Map<String, Entry> entries;

    public RobotsCache(Function<String, RobotsRules> loader, long ttlMs, int maxEntries,
                       LongSupplier clock) {
        this.loader = loader;
        this.ttlMs = ttlMs;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public RobotsRules rulesFor(String origin) {
        long now = clock.getAsLong();
        synchronized (entries) {
            Entry e = entries.get(origin);
            if (e != null && e.expiresAtMs() > now) {
                return e.rules();
            }
        }
        // Load outside the lock; the frontier allows one in-flight request per host,
        // so duplicate loads for the same origin are rare and harmless.
        RobotsRules rules = loader.apply(origin);
        synchronized (entries) {
            entries.put(origin, new Entry(rules, now + ttlMs));
        }
        return rules;
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
