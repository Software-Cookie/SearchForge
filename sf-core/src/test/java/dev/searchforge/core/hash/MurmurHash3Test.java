package dev.searchforge.core.hash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MurmurHash3Test {

    @Test
    void emptyInputWithSeedZeroIsZero() {
        assertThat(MurmurHash3.hash128(new byte[0], 0)).containsExactly(0L, 0L);
    }

    @Test
    void matchesReferenceVectors() {
        // Reference values from the C++ MurmurHash3_x64_128 (same as Guava murmur3_128).
        long[] hello = MurmurHash3.hash128("hello".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(hello).containsExactly(0xcbd8a7b341bd9b02L, 0x5b1e906a48ae1d19L);
        long[] fox = MurmurHash3.hash128(
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(fox).containsExactly(0xe34bbc7bbc071b6cL, 0x7a433ca9c49a9347L);
    }

    @Test
    void coversEveryTailLength() {
        String s = "abcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int len = 0; len <= s.length(); len++) {
            seen.add(MurmurHash3.hash64(s.substring(0, len)));
        }
        assertThat(seen).hasSize(s.length() + 1);
    }

    @Test
    void seedChangesHash() {
        byte[] b = "searchforge".getBytes(StandardCharsets.UTF_8);
        assertThat(MurmurHash3.hash128(b, 1)).isNotEqualTo(MurmurHash3.hash128(b, 2));
    }
}
