package com.pawwork.opencode;

import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider resolution: the gateway provider speaks the OpenCode dialect, everything else looks
 * like a plain OpenAI-compatible endpoint.
 */
class OpenCodePropertiesTest {

    private OpenCodeProperties properties() {
        OpenCodeProperties properties = new OpenCodeProperties();
        properties.setBaseUrl("https://opencode.ai/zen/v1/");
        properties.setDefaultModel("big-pickle");
        OpenCodeProperties.Provider deepseek = new OpenCodeProperties.Provider();
        deepseek.setBaseUrl("https://api.deepseek.com/v1/");
        deepseek.setDefaultModel("deepseek-chat");
        properties.setProviders(new java.util.LinkedHashMap<>(Map.of("deepseek", deepseek)));
        return properties;
    }

    @Test
    void gatewayProviderSpeaksTheCliDialect() {
        ProviderProfile zen = properties().resolve("zen");

        assertThat(zen.usesGatewayHeaders()).isTrue();
        assertThat(zen.forceStream()).isTrue();
        assertThat(zen.maxTokens()).isEqualTo(32000);
        assertThat(zen.endpoint()).doesNotEndWith("/");
        assertThat(zen.chatCompletionsUrl()).isEqualTo("https://opencode.ai/zen/v1/chat/completions");
    }

    @Test
    void anyProviderHostedOnOpencodeAiSpeaksTheDialectEvenWhenNotDeclaredSo() {
        OpenCodeProperties properties = new OpenCodeProperties();
        OpenCodeProperties.Provider custom = new OpenCodeProperties.Provider();
        custom.setBaseUrl("https://opencode.ai/something-else/v1");
        properties.setProviders(Map.of("custom", custom));

        assertThat(properties.resolve("custom").usesGatewayHeaders()).isTrue();
    }

    @Test
    void aPlainProviderIsNotForcedIntoStreamingAndGetsAStandardTokenCap() {
        ProviderProfile deepseek = properties().resolve("deepseek");

        assertThat(deepseek.usesGatewayHeaders()).isFalse();
        assertThat(deepseek.forceStream()).isFalse();
        assertThat(deepseek.maxTokens()).isEqualTo(4096);
        assertThat(deepseek.chatCompletionsUrl())
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }

    @Test
    void bigPickleIsLeftUncappedOnNonGatewayProvidersSoItsReasoningKeepsItsBudget() {
        OpenCodeProperties properties = properties();
        OpenCodeProperties.Provider plain = new OpenCodeProperties.Provider();
        plain.setBaseUrl("https://example.invalid/v1");
        plain.setDefaultModel("big-pickle");
        properties.setProviders(Map.of("plain", plain));

        assertThat(properties.resolve("plain").maxTokens()).isNull();
    }

    @Test
    void unknownProviderFallsBackToTheDefaultInsteadOfFailing() {
        OpenCodeProperties properties = properties();
        properties.setDefaultProvider("deepseek");

        assertThat(properties.resolve("does-not-exist").name()).isEqualTo("deepseek");
        assertThat(properties.resolve(null).name()).isEqualTo("deepseek");
        assertThat(properties.resolve("  ").name()).isEqualTo("deepseek");
    }

    @Test
    void anApiKeyIsCarriedThroughAndNewProvidersAreListed() {
        OpenCodeProperties properties = properties();
        OpenCodeProperties.Provider keyed = new OpenCodeProperties.Provider();
        keyed.setBaseUrl("https://example.invalid/v1");
        keyed.setApiKey("sk-secret");
        keyed.setDefaultModel("m");
        properties.getProviders().put("keyed", keyed);

        assertThat(properties.resolve("keyed").apiKey()).isEqualTo("sk-secret");
        assertThat(properties.profiles()).containsKey("keyed").containsKey("zen");
    }
}
