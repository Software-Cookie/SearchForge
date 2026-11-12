package dev.searchforge.core.dedup;

import dev.searchforge.core.hash.MurmurHash3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 64-bit SimHash (Charikar) over TF-weighted words, plus a 4-band index that finds any
// stored fingerprint within Hamming distance <= 3 (pigeonhole: one 16-bit band must match).
public final class SimHash {

    public static final int MAX_DISTANCE = 3;
    private static final int BANDS = 4;

    private final List<Map<Integer, List<Long>>> bands = new ArrayList<>();

    public SimHash() {
        for (int i = 0; i < BANDS; i++) {
            bands.add(new HashMap<>());
        }
    }

    // Features are words weighted by term frequency (Manku, Jain, Das Sarma, WWW 2007).
    public static long fingerprint(List<String> tokens) {
        if (tokens.isEmpty()) {
            return 0L;
        }
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        long[] v = new long[64];
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            long h = MurmurHash3.hash64(e.getKey());
            int w = e.getValue();
            for (int b = 0; b < 64; b++) {
                v[b] += ((h >>> b) & 1L) == 1L ? w : -w;
            }
        }
        long fp = 0;
        for (int b = 0; b < 64; b++) {
            if (v[b] > 0) {
                fp |= 1L << b;
            }
        }
        return fp;
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    // Adds the fingerprint and returns true, unless a near-duplicate is already stored.
    public synchronized boolean addIfNew(long fp) {
        for (int i = 0; i < BANDS; i++) {
            List<Long> bucket = bands.get(i).get(band(fp, i));
            if (bucket == null) {
                continue;
            }
            for (long other : bucket) {
                if (hamming(fp, other) <= MAX_DISTANCE) {
                    return false;
                }
            }
        }
        for (int i = 0; i < BANDS; i++) {
            bands.get(i).computeIfAbsent(band(fp, i), k -> new ArrayList<>(1)).add(fp);
        }
        return true;
    }

    private static int band(long fp, int i) {
        return (int) ((fp >>> (i * 16)) & 0xffffL);
    }
}
