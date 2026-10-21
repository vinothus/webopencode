package com.pawwork.opencode.demo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI SDKs branch on {@code error.type}, so the mapping from HTTP status has to be right.
 */
class GatewayExceptionHandlerTest {

    @Test
    void statusesMapToTheOpenAiErrorTypes() {
        assertThat(GatewayExceptionHandler.typeFor(400)).isEqualTo("invalid_request_error");
        assertThat(GatewayExceptionHandler.typeFor(404)).isEqualTo("invalid_request_error");
        assertThat(GatewayExceptionHandler.typeFor(401)).isEqualTo("authentication_error");
        assertThat(GatewayExceptionHandler.typeFor(403)).isEqualTo("permission_error");
        assertThat(GatewayExceptionHandler.typeFor(429)).isEqualTo("rate_limit_error");
        assertThat(GatewayExceptionHandler.typeFor(500)).isEqualTo("server_error");
        assertThat(GatewayExceptionHandler.typeFor(502)).isEqualTo("api_error");
        assertThat(GatewayExceptionHandler.typeFor(503)).isEqualTo("server_error");
        assertThat(GatewayExceptionHandler.typeFor(418)).isEqualTo("invalid_request_error");
    }

    @Test
    void errorBodyIsRenderedInTheOpenAiShape() {
        GatewayExceptionHandler.ApiError body = new GatewayExceptionHandler.ApiError(
                new GatewayExceptionHandler.ApiError.Detail(
                        "The model 'nope' does not exist", "invalid_request_error", null, null, null));

        assertThat(body.error().message()).isEqualTo("The model 'nope' does not exist");
        assertThat(body.error().type()).isEqualTo("invalid_request_error");
        assertThat(body.error().param()).isNull();
        assertThat(body.error().code()).isNull();
    }
}
