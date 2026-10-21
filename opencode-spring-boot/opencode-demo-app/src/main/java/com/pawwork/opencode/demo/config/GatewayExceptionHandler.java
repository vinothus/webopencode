package com.pawwork.opencode.demo.config;

import com.pawwork.opencode.client.OpenCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every failure in the shape OpenAI clients parse:
 * {@code {"error":{"message":"…","type":"…","param":null,"code":null}}}.
 *
 * <p>{@code type} follows OpenAI's own vocabulary so SDKs can branch on it
 * ({@code invalid_request_error}, {@code authentication_error}, {@code rate_limit_error},
 * {@code api_error}); the gateway's raw body rides along in the non-standard {@code upstream}
 * field for debugging and is ignored by clients.</p>
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /** OpenAI's error object, plus an optional passthrough of what the provider actually said. */
    public record ApiError(Detail error) {

        public record Detail(String message, String type, String param, Object code, String upstream) {
        }
    }

    @ExceptionHandler(OpenCodeException.class)
    public ResponseEntity<ApiError> handleGateway(OpenCodeException e) {
        log.warn("Gateway call failed (status {}): {}", e.getStatusCode(), e.getMessage());
        HttpStatus status = e.getStatusCode() >= 400 && e.getStatusCode() < 600
                ? HttpStatus.resolve(e.getStatusCode())
                : HttpStatus.BAD_GATEWAY;
        int code = status == null ? HttpStatus.BAD_GATEWAY.value() : status.value();
        return ResponseEntity.status(HttpStatus.valueOf(code))
                .body(new ApiError(new ApiError.Detail(
                        e.getMessage(), typeFor(code), null, null, e.getResponseBody())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(new ApiError.Detail(String.valueOf(e.getMessage()),
                        "invalid_request_error", null, null, null)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest()
                .body(new ApiError(new ApiError.Detail(
                        "The request body was not valid JSON." + (detail.isBlank() ? "" : " " + detail),
                        "invalid_request_error", null, null, null)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleUnknownPath(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(new ApiError.Detail(
                        "Unknown endpoint: " + e.getResourcePath(),
                        "invalid_request_error", null, null, null)));
    }

    /** OpenAI's error-type vocabulary, mapped from the HTTP status. */
    static String typeFor(int status) {
        if (status == 401) {
            return "authentication_error";
        }
        if (status == 403) {
            return "permission_error";
        }
        if (status == 429) {
            return "rate_limit_error";
        }
        if (status == 400 || status == 404 || status == 409 || status == 413
                || status == 415 || status == 422) {
            return "invalid_request_error";
        }
        if (status == 500 || status == 503) {
            return "server_error";
        }
        return status >= 500 ? "api_error" : "invalid_request_error";
    }
}
