package dev.searchforge.core.graph;

import java.util.Arrays;

// PageRank by power iteration (Brin & Page 1998):
//   PR(v) = (1-d)/N + d * ( sum_{u->v} PR(u)/out(u) + danglingMass/N )
// Dangling nodes (no out-links) spread their rank uniformly, so ranks always sum to 1.
public final class PageRank {

    public record Result(double[] ranks, int iterations, double finalDelta) {
    }

    private PageRank() {
    }

    // outLinks[u] lists the node ids u links to. Self-links and duplicates are ignored.
    public static Result compute(int[][] outLinks, double damping, double tolerance,
                                 int maxIterations) {
        if (damping <= 0 || damping >= 1) {
            throw new IllegalArgumentException("damping must be in (0, 1)");
        }
        int n = outLinks.length;
        if (n == 0) {
            return new Result(new double[0], 0, 0);
        }
        int[][] links = dedupe(outLinks);
        double[] rank = new double[n];
        Arrays.fill(rank, 1.0 / n);
        double[] next = new double[n];
        double delta = Double.MAX_VALUE;
        int iter = 0;
        while (iter < maxIterations && delta > tolerance) {
            double dangling = 0;
            for (int u = 0; u < n; u++) {
                if (links[u].length == 0) {
                    dangling += rank[u];
                }
            }
            double base = (1 - damping) / n + damping * dangling / n;
            Arrays.fill(next, base);
            for (int u = 0; u < n; u++) {
                int[] out = links[u];
                if (out.length == 0) {
                    continue;
                }
                double share = damping * rank[u] / out.length;
                for (int v : out) {
                    next[v] += share;
                }
            }
            delta = 0;
            for (int i = 0; i < n; i++) {
                delta += Math.abs(next[i] - rank[i]);
            }
            double[] tmp = rank;
            rank = next;
            next = tmp;
            iter++;
        }
        return new Result(rank, iter, delta);
    }

    private static int[][] dedupe(int[][] outLinks) {
        int n = outLinks.length;
        int[][] out = new int[n][];
        for (int u = 0; u < n; u++) {
            int[] links = outLinks[u] == null ? new int[0] : outLinks[u];
            final int self = u;
            out[u] = Arrays.stream(links)
                    .filter(v -> v != self && v >= 0 && v < n)
                    .distinct()
                    .toArray();
        }
        return out;
    }
}
