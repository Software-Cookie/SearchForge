package dev.searchforge.core.page;

import java.util.List;
import java.util.Objects;

// One crawled (or imported) page, as written by the crawler and read by the indexer.
public record PageRecord(
        String url,
        String title,
        String headings,
        String body,
        List<String> outlinks,
        long fetchedAtEpochMs) {

    public PageRecord {
        Objects.requireNonNull(url, "url");
        title = title == null ? "" : title;
        headings = headings == null ? "" : headings;
        body = body == null ? "" : body;
        outlinks = outlinks == null ? List.of() : List.copyOf(outlinks);
    }
}
