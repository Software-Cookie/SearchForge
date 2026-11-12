package dev.searchforge.query;

import java.util.List;

// The response shape of a search. Kept stable: the UI and the load test depend on it.
public record SearchResult(
        String query,
        long totalHits,
        long tookMs,
        int page,
        int size,
        List<Hit> results,
        String didYouMean) {

    public record Hit(String url, String title, double score, List<Snippets.Segment> snippet) {
    }
}
