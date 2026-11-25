package dev.searchforge.api.websearch;

// One image result: a thumbnail to show, the full image, and the page it comes from.
public record ImageHit(String title, String imageUrl, String thumbnailUrl, String pageUrl,
                       String site, int width, int height) {
}
