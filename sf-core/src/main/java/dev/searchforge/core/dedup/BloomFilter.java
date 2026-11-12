package dev.searchforge.core.dedup;

import dev.searchforge.core.hash.MurmurHash3;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLongArray;

// Thread-safe Bloom filter sized from expected items n and false-positive rate p:
//   m = -n ln p / (ln 2)^2 bits,  k = (m / n) ln 2 hash functions.
// Uses Kirsch-Mitzenmacher double hashing on the two MurmurHash3 halves.
public final class BloomFilter {

    private final AtomicLongArray words;
    private final long bitCount;
    private final int hashCount;

    public BloomFilter(long expectedItems, double falsePositiveRate) {
        if (expectedItems <= 0) {
            throw new IllegalArgumentException("expectedItems must be > 0");
        }
        if (!(falsePositiveRate > 0 && falsePositiveRate < 1)) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
        }
        double ln2 = Math.log(2);
        long m = (long) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (ln2 * ln2));
        m = Math.max(64, m);
        int words = Math.toIntExact((m + 63) / 64);
        this.words = new AtomicLongArray(words);
        this.bitCount = (long) words * 64;
        this.hashCount = Math.max(1, (int) Math.round((double) m / expectedItems * ln2));
    }

    public long bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    public boolean mightContain(String item) {
        long[] h = hashes(item);
        for (int i = 0; i < hashCount; i++) {
            long bit = index(h, i);
            if ((words.get((int) (bit >>> 6)) & (1L << bit)) == 0) {
                return false;
            }
        }
        return true;
    }

    // Returns true if the item was (probably) new, i.e. at least one bit changed.
    public boolean put(String item) {
        long[] h = hashes(item);
        boolean changed = false;
        for (int i = 0; i < hashCount; i++) {
            long bit = index(h, i);
            int w = (int) (bit >>> 6);
            long mask = 1L << bit;
            while (true) {
                long old = words.get(w);
                if ((old & mask) != 0) {
                    break;
                }
                if (words.compareAndSet(w, old, old | mask)) {
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private static long[] hashes(String item) {
        return MurmurHash3.hash128(item.getBytes(StandardCharsets.UTF_8), 0x5f0e1L);
    }

    private long index(long[] h, int i) {
        long combined = h[0] + i * h[1];
        return Math.floorMod(combined, bitCount);
    }
}
