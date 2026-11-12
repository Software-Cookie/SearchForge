package dev.searchforge.bench;

import dev.searchforge.indexer.Index;
import dev.searchforge.query.RelevanceMetrics;
import dev.searchforge.query.SearchEngine;
import dev.searchforge.query.SearchResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Scores the engine against human judgments.
//   --index <dir> --qrels <file.tsv>
// qrels.tsv lines: query <TAB> url <TAB> grade (0 = not relevant ... 3 = perfect).
// Prints P@10 and nDCG@10 per query and the means (the numbers for docs/RESULTS.md).
public final class RelevanceEval {

    private RelevanceEval() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].equals("--index") || !args[2].equals("--qrels")) {
            System.out.println("Usage: --index <dir> --qrels <file.tsv>");
            System.exit(2);
        }
        SearchEngine engine = new SearchEngine(Index.open(Path.of(args[1])));
        Map<String, Map<String, Integer>> qrels = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(args[3]), StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (line.isBlank() || line.startsWith("#") || parts.length != 3) {
                continue;
            }
            qrels.computeIfAbsent(parts[0], k -> new HashMap<>())
                    .put(parts[1], Integer.parseInt(parts[2].strip()));
        }
        double sumP = 0;
        double sumN = 0;
        for (Map.Entry<String, Map<String, Integer>> q : qrels.entrySet()) {
            SearchResult r = engine.search(q.getKey(), 1, 10, System.nanoTime() + 5_000_000_000L);
            List<String> ranked = r.results().stream().map(SearchResult.Hit::url).toList();
            var relevant = q.getValue().entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            double p = RelevanceMetrics.precisionAtK(ranked, relevant, 10);
            double n = RelevanceMetrics.ndcgAtK(ranked, q.getValue(), 10);
            sumP += p;
            sumN += n;
            System.out.printf("%-40s P@10=%.2f nDCG@10=%.2f%n", q.getKey(), p, n);
        }
        int count = Math.max(1, qrels.size());
        System.out.printf("queries=%d meanP@10=%.3f meanNDCG@10=%.3f%n", qrels.size(),
                sumP / count, sumN / count);
    }
}
