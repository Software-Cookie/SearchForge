package dev.searchforge.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Offline relevance metrics for the judged query set (docs/eval/qrels.tsv).
public final class RelevanceMetrics {

    private RelevanceMetrics() {
    }

    // Fraction of the top k results that are relevant (missing results count as misses).
    public static double precisionAtK(List<String> ranked, Set<String> relevant, int k) {
        int hits = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (relevant.contains(ranked.get(i))) {
                hits++;
            }
        }
        return (double) hits / k;
    }

    // nDCG@k with graded judgments (0 = not relevant ... 3 = perfect), gain 2^g - 1.
    public static double ndcgAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            dcg += gain(grades.getOrDefault(ranked.get(i), 0)) / log2(i + 2);
        }
        List<Integer> ideal = new ArrayList<>(grades.values());
        ideal.sort((a, b) -> b - a);
        double idcg = 0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) {
            idcg += gain(ideal.get(i)) / log2(i + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private static double gain(int grade) {
        return Math.pow(2, grade) - 1;
    }

    private static double log2(int x) {
        return Math.log(x) / Math.log(2);
    }
}
