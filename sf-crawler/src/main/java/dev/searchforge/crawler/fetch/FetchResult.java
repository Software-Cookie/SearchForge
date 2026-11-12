package dev.searchforge.crawler.fetch;

// Outcome of one HTTP fetch. body is set only for OK; location only for REDIRECT.
public record FetchResult(Kind kind, int status, String body, String location) {

    public enum Kind {
        OK, REDIRECT, NOT_HTML, HTTP_ERROR, BLOCKED, ERROR
    }

    static FetchResult of(Kind kind, int status) {
        return new FetchResult(kind, status, null, null);
    }
}
