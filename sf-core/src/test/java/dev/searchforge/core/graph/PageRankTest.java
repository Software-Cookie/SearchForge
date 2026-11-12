package dev.searchforge.core.graph;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PageRankTest {

    @Test
    void cycleGivesEqualRanks() {
        int[][] g = {{1}, {2}, {0}};
        PageRank.Result r = PageRank.compute(g, 0.85, 1e-12, 200);
        for (double v : r.ranks()) {
            assertThat(v).isCloseTo(1.0 / 3, within(1e-9));
        }
    }

    @Test
    void popularPageRanksHighestAndRanksSumToOneWithDanglingNodes() {
        // 0,1,2 all link to 3; 3 is dangling. Duplicate and self links are ignored.
        int[][] g = {{3, 3}, {3, 1}, {3}, {}};
        PageRank.Result r = PageRank.compute(g, 0.85, 1e-12, 200);
        double sum = Arrays.stream(r.ranks()).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-9));
        assertThat(r.ranks()[3]).isGreaterThan(r.ranks()[0]);
        assertThat(r.iterations()).isPositive();
        assertThat(r.finalDelta()).isLessThanOrEqualTo(1e-12);
    }

    @Test
    void handlesEmptyGraphNullRowsAndBadIds() {
        assertThat(PageRank.compute(new int[0][], 0.85, 1e-9, 10).ranks()).isEmpty();
        int[][] g = {null, {5, -1, 0}};
        PageRank.Result r = PageRank.compute(g, 0.85, 1e-12, 200);
        assertThat(Arrays.stream(r.ranks()).sum()).isCloseTo(1.0, within(1e-9));
        assertThatThrownBy(() -> PageRank.compute(g, 1.0, 1e-9, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
