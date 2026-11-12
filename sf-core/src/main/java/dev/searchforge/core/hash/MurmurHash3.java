package dev.searchforge.core.hash;

import java.nio.charset.StandardCharsets;

// MurmurHash3 x64 128-bit (Austin Appleby, public domain), ported to Java.
// Returns both 64-bit halves; most callers use h1 as a 64-bit hash.
public final class MurmurHash3 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private MurmurHash3() {
    }

    public static long hash64(String s) {
        return hash128(s.getBytes(StandardCharsets.UTF_8), 0)[0];
    }

    public static long[] hash128(byte[] data, long seed) {
        final int len = data.length;
        final int blocks = len / 16;
        long h1 = seed;
        long h2 = seed;

        for (int i = 0; i < blocks; i++) {
            long k1 = getLong(data, i * 16);
            long k2 = getLong(data, i * 16 + 8);
            h1 ^= mixK1(k1);
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;
            h2 ^= mixK2(k2);
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int tail = blocks * 16;
        int rem = len & 15;
        for (int i = rem - 1; i >= 8; i--) {
            k2 ^= (data[tail + i] & 0xffL) << ((i - 8) * 8);
        }
        for (int i = Math.min(rem, 8) - 1; i >= 0; i--) {
            k1 ^= (data[tail + i] & 0xffL) << (i * 8);
        }
        if (rem > 8) {
            h2 ^= mixK2(k2);
        }
        if (rem > 0) {
            h1 ^= mixK1(k1);
        }

        h1 ^= len;
        h2 ^= len;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new long[] {h1, h2};
    }

    private static long mixK1(long k1) {
        k1 *= C1;
        k1 = Long.rotateLeft(k1, 31);
        return k1 * C2;
    }

    private static long mixK2(long k2) {
        k2 *= C2;
        k2 = Long.rotateLeft(k2, 33);
        return k2 * C1;
    }

    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    private static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xffL);
        }
        return v;
    }
}
