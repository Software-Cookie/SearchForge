package dev.searchforge.api.websearch;

import java.util.List;

// What a provider returns for one page of web results.
public record WebPage(List<WebHit> hits, boolean hasMore, String didYouMean) {
}
