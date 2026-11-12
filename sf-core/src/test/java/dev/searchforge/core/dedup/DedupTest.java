package dev.searchforge.core.dedup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DedupTest {

    @Test
    void bloomFilterHasNoFalseNegativesAndLowFalsePositives() {
        BloomFilter bf = new BloomFilter(10_000, 0.01);
        int reportedNew = 0;
        for (int i = 0; i < 10_000; i++) {
            if (bf.put("url-" + i)) {
                reportedNew++;
            }
        }
        // put() can say "not new" only for a false positive, so nearly all are new.
        assertThat(reportedNew).isGreaterThan(9_900);
        for (int i = 0; i < 10_000; i++) {
            assertThat(bf.mightContain("url-" + i)).isTrue();
        }
        int fp = 0;
        for (int i = 0; i < 10_000; i++) {
            if (bf.mightContain("other-" + i)) {
                fp++;
            }
        }
        assertThat(fp).isLessThan(250); // 1% target, 2.5% allowed slack
        assertThat(bf.hashCount()).isEqualTo(7);
        assertThat(bf.bitCount()).isGreaterThanOrEqualTo(95_851);
        assertThat(bf.put("url-1")).isFalse();
    }

    @Test
    void bloomFilterRejectsBadParameters() {
        assertThatThrownBy(() -> new BloomFilter(0, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BloomFilter(10, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simHashFindsNearDuplicatesButNotDifferentPages() {
        List<String> base = words(3000, 0);
        List<String> nearDup = new ArrayList<>(base);
        nearDup.set(200, "changed"); // e.g. a timestamp in the footer
        nearDup.add("extra");
        List<String> different = words(3000, 7);

        long a = SimHash.fingerprint(base);
        long b = SimHash.fingerprint(nearDup);
        long c = SimHash.fingerprint(different);
        assertThat(SimHash.hamming(a, a)).isZero();
        assertThat(SimHash.hamming(a, b)).isLessThanOrEqualTo(SimHash.MAX_DISTANCE);
        assertThat(SimHash.hamming(a, c)).isGreaterThan(SimHash.MAX_DISTANCE);

        SimHash index = new SimHash();
        assertThat(index.addIfNew(a)).isTrue();
        assertThat(index.addIfNew(b)).isFalse();
        assertThat(index.addIfNew(c)).isTrue();
    }

    @Test
    void simHashHandlesShortAndEmptyInput() {
        assertThat(SimHash.fingerprint(List.of())).isZero();
        assertThat(SimHash.fingerprint(List.of("one"))).isNotZero();
        assertThat(SimHash.fingerprint(Arrays.asList("a", "b")))
                .isEqualTo(SimHash.fingerprint(Arrays.asList("a", "b")));
    }

    private static List<String> words(int n, int salt) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("w" + salt + "_" + ((i * 31) % 997));
        }
        return out;
    }
}
