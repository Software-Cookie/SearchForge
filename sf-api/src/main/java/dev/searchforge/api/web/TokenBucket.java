package dev.searchforge.api.web;

import java.util.function.LongSupplier;

// Classic token bucket: holds up to `capacity` tokens, refills `perSecond` tokens a second.
// A request takes one token; an empty bucket means "too many requests".
public final class TokenBucket {

    private final double capacity;
    private final double perNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long last;

    public TokenBucket(int capacity, int perSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.perNano = perSecond / 1e9;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.last = nanoClock.getAsLong();
    }

    public synchronized boolean tryConsume() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * perNano);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
