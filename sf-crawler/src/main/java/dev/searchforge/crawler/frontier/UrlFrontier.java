package dev.searchforge.crawler.frontier;

import dev.searchforge.core.dedup.BloomFilter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

// Mercator-style frontier, simplified:
// - one back queue per host, ordered by depth (shallow pages first = priority);
// - a min-heap of "host H may be fetched again at time T" (politeness);
// - at most one request in flight per host;
// - a Bloom filter so each URL is enqueued at most once, and a per-host cap against traps.
public final class UrlFrontier {

    private static final class HostQueue {
        final PriorityQueue<Item> urls = new PriorityQueue<>(
                Comparator.comparingInt((Item i) -> i.url.depth()).thenComparingLong(i -> i.seq));
        long readyAtMs;
        boolean inFlight;
        boolean scheduled;
        int accepted;
    }

    private record Item(CrawlUrl url, long seq) {
    }

    private record Ready(String host, long atMs) {
    }

    private final BloomFilter seen;
    private final int perHostCap;
    private final LongSupplier clockMs;
    private final Map<String, HostQueue> hosts = new HashMap<>();
    private final PriorityQueue<Ready> heap =
            new PriorityQueue<>(Comparator.comparingLong(Ready::atMs));
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long seq;
    private int queued;
    private int inFlight;
    private boolean closed;

    public UrlFrontier(BloomFilter seen, int perHostCap, LongSupplier clockMs) {
        this.seen = seen;
        this.perHostCap = perHostCap;
        this.clockMs = clockMs;
    }

    // Marks a URL as already crawled (used when resuming) without enqueuing it.
    public void markSeen(String url) {
        seen.put(url);
    }

    // Returns false if the URL was seen before, the host is at its cap, or we are closed.
    public boolean add(CrawlUrl url) {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            HostQueue hq = hosts.computeIfAbsent(url.host(), h -> new HostQueue());
            if (hq.accepted >= perHostCap || !seen.put(url.url())) {
                return false;
            }
            hq.accepted++;
            hq.urls.add(new Item(url, seq++));
            queued++;
            if (!hq.inFlight && !hq.scheduled) {
                hq.scheduled = true;
                heap.add(new Ready(url.host(), hq.readyAtMs));
                changed.signalAll();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    // Blocks until a URL is ready. Returns null once the crawl is finished (nothing queued
    // and nothing in flight) or the frontier was closed.
    public CrawlUrl take() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    return null;
                }
                long now = clockMs.getAsLong();
                Ready top = heap.peek();
                if (top != null && top.atMs() <= now) {
                    heap.poll();
                    HostQueue hq = hosts.get(top.host());
                    hq.scheduled = false;
                    Item item = hq.urls.poll();
                    hq.inFlight = true;
                    queued--;
                    inFlight++;
                    return item.url();
                }
                if (top == null && inFlight == 0) {
                    return null;
                }
                long waitMs = top == null ? 1_000 : Math.max(1, top.atMs() - now);
                changed.await(waitMs, TimeUnit.MILLISECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    // Called when the fetch of a URL from host finishes; the host may be used again
    // after delayMs.
    public void complete(String host, long delayMs) {
        lock.lock();
        try {
            HostQueue hq = hosts.get(host);
            if (hq == null || !hq.inFlight) {
                throw new IllegalStateException("No request in flight for host " + host);
            }
            hq.inFlight = false;
            inFlight--;
            hq.readyAtMs = clockMs.getAsLong() + delayMs;
            if (!hq.urls.isEmpty()) {
                hq.scheduled = true;
                heap.add(new Ready(host, hq.readyAtMs));
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int queued() {
        lock.lock();
        try {
            return queued;
        } finally {
            lock.unlock();
        }
    }
}
