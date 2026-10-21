package com.pawwork.opencode.client;

/**
 * Raised whenever the gateway could not produce a completion: transport failures, non-2xx answers,
 * or an SSE stream that died mid-flight.
 *
 * <p>Carries the HTTP status ({@code 0} when the request never got a response) and the gateway's
 * own words, so callers can surface the provider's message the way the Android client surfaces
 * {@code error_msg} / {@code error.message}.</p>
 */
public class OpenCodeException extends RuntimeException {

    private final int statusCode;

    private final String responseBody;

    public OpenCodeException(int statusCode, String message) {
        this(statusCode, message, null, null);
    }

    public OpenCodeException(int statusCode, String message, String responseBody) {
        this(statusCode, message, responseBody, null);
    }

    public OpenCodeException(int statusCode, String message, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** HTTP status of the failing call, or {@code 0} for connect/read/stream failures. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Raw (truncated) response body, when there was one. */
    public String getResponseBody() {
        return responseBody;
    }

    /** {@code true} when the gateway answered rather than the network failing. */
    public boolean hasHttpResponse() {
        return statusCode > 0;
    }
}
