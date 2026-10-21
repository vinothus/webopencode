package com.pawwork.opencode.config;

/**
 * Immutable description of one AI provider the library can talk to.
 *
 * <p>The {@code opencodeGateway} flag is what makes the client behave like the OpenCode CLI: it
 * forces streaming (the Zen free tier rejects non-streaming calls), attaches the CLI identity
 * headers, and injects the official CLI tool schemas that the free-tier gate requires.</p>
 *
 * @param name           logical provider name ({@code zen}, {@code deepseek}, …)
 * @param baseUrl        OpenAI-compatible base URL, without a trailing {@code /chat/completions}
 * @param apiKey         optional bearer key; blank means anonymous
 * @param defaultModel   model used when a request does not name one
 * @param opencodeGateway whether to speak the OpenCode CLI dialect to this endpoint
 * @param toolsEnabled   whether tools may be advertised at all
 * @param maxTokens      default output cap, or {@code null} to leave it to the provider
 * @param userAgent      {@code User-Agent} sent to the gateway
 * @param forceStream    whether every call must be made with {@code stream:true}
 */
public record ProviderProfile(
        String name,
        String baseUrl,
        String apiKey,
        String defaultModel,
        boolean opencodeGateway,
        boolean toolsEnabled,
        Integer maxTokens,
        String userAgent,
        boolean forceStream) {

    /** {@code true} when requests must carry the {@code x-opencode-*} CLI identity headers. */
    public boolean usesGatewayHeaders() {
        return opencodeGateway;
    }

    /** Normalised base URL — never ends with {@code /}. */
    public String endpoint() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    public String chatCompletionsUrl() {
        return endpoint() + "/chat/completions";
    }
}
