package dev.searchforge.crawler.frontier;

// A normalized URL waiting to be crawled. host is the politeness key.
public record CrawlUrl(String url, String host, int depth) {
}
