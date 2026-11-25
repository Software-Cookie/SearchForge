package dev.searchforge.api.websearch;

// A live search source failed (timeout, quota, bad response). Shown to users as a 502.
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
