package com.pawwork.opencode.stt;

/**
 * A failure inside the speech-to-text engine, carrying the HTTP status the API should answer
 * with — 400 for a bad request, 404 for an unknown model, 409 for a download in flight, 503 when
 * nothing is installed and 500 when the decoder blew up.
 */
public class SttException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    public SttException(int status, String message) {
        super(message);
        this.status = status;
    }

    public SttException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** The HTTP status this failure should be reported with. */
    public int status() {
        return status;
    }
}
