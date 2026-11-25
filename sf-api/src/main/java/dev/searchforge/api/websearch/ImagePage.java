package dev.searchforge.api.websearch;

import java.util.List;

// What a provider returns for one page of image results.
public record ImagePage(List<ImageHit> images, boolean hasMore) {
}
