package dev.searchforge.api.websearch;

import java.util.List;

// The /api/web response. type is "web" or "images"; only the matching list is filled.
public record WebSearchResponse(String query, String type, int page, long tookMs, String source,
                                boolean hasMore, String didYouMean, List<WebHit> results,
                                List<ImageHit> images) {
}
