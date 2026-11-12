package dev.searchforge.query;

// Thrown when a query runs past its deadline (checked inside the scoring loop).
public final class QueryTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueryTimeoutException() {
        super("Query exceeded its time budget");
    }
}
