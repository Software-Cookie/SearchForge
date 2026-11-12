package dev.searchforge.crawler.frontier;

import dev.searchforge.core.dedup.BloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlFrontierTest {

    private static UrlFrontier frontier(int cap) {
        return new UrlFrontier(new BloomFilter(1_000, 0.001), cap, System::currentTimeMillis);
    }

    @Test
    void dedupesAndCapsPerHost() {
        UrlFrontier f = frontier(2);
        assertThat(f.add(new CrawlUrl("http://a/1", "a", 0))).isTrue();
        assertThat(f.add(new CrawlUrl("http://a/1", "a", 0))).isFalse();
        assertThat(f.add(new CrawlUrl("http://a/2", "a", 0))).isTrue();
        assertThat(f.add(new CrawlUrl("http://a/3", "a", 0))).isFalse(); // cap
        f.markSeen("http://b/1");
        assertThat(f.add(new CrawlUrl("http://b/1", "b", 0))).isFalse();
        assertThat(f.queued()).isEqualTo(2);
    }

    @Test
    @Timeout(5)
    void servesShallowUrlsFirstAndEnforcesPerHostDelay() throws Exception {
        UrlFrontier f = frontier(100);
        f.add(new CrawlUrl("http://a/deep", "a", 3));
        f.add(new CrawlUrl("http://a/shallow", "a", 0));
        f.add(new CrawlUrl("http://b/1", "b", 0));

        CrawlUrl first = f.take();
        CrawlUrl second = f.take();
        assertThat(first.host()).isNotEqualTo(second.host()); // other host is not blocked
        CrawlUrl fromA = first.host().equals("a") ? first : second;
        assertThat(fromA.url()).isEqualTo("http://a/shallow");

        f.complete("b", 0);
        long t0 = System.nanoTime();
        f.complete("a", 300);
        CrawlUrl next = f.take();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(next.url()).isEqualTo("http://a/deep");
        assertThat(waitedMs).isGreaterThanOrEqualTo(250);
        f.complete("a", 0);
        assertThat(f.take()).isNull(); // nothing queued, nothing in flight
    }

    @Test
    @Timeout(5)
    void takeWaitsForInFlightWorkThenFinishes() throws Exception {
        UrlFrontier f = frontier(100);
        f.add(new CrawlUrl("http://a/1", "a", 0));
        CrawlUrl u = f.take();
        CompletableFuture<CrawlUrl> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return f.take();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        f.add(new CrawlUrl("http://a/2", "a", 1)); // discovered by the in-flight fetch
        f.complete(u.host(), 10);
        assertThat(waiter.get(2, TimeUnit.SECONDS).url()).isEqualTo("http://a/2");
    }

    @Test
    @Timeout(5)
    void closeReleasesWaitersAndRejectsNewUrls() throws Exception {
        UrlFrontier f = frontier(100);
        f.add(new CrawlUrl("http://a/1", "a", 0));
        f.take();
        CompletableFuture<CrawlUrl> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return f.take();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        f.close();
        assertThat(waiter.get(2, TimeUnit.SECONDS)).isNull();
        assertThat(f.add(new CrawlUrl("http://c/1", "c", 0))).isFalse();
        assertThatThrownBy(() -> f.complete("zzz", 0)).isInstanceOf(IllegalStateException.class);
    }
}
