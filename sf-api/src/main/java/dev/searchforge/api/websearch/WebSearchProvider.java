package dev.searchforge.api.websearch;

// A live internet search source. Pages start at 1.
public interface WebSearchProvider {

    String name();

    WebPage web(String query, int page);

    ImagePage images(String query, int page);
}
