package com.pawwork.opencode.demo.api;

import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.demo.dto.ChatApiRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-request provider overrides, used by the UI's “add your own model” form.
 *
 * <p>A custom model is not in {@code opencode.providers.*}: the browser sends its base URL and the
 * key the user typed. Those apply to this one call, and a custom endpoint speaks plain OpenAI —
 * the OpenCode CLI dialect (identity headers, forced streaming) must not be used against it.</p>
 */
class ChatControllerOverrideTest {

    private final ProviderProfile zen = new ProviderProfile(
            "zen", "https://opencode.ai/zen/v1", null, "big-pickle",
            true, true, null, "opencode/1.0", true);

    @Test
    void withoutOverridesTheConfiguredProfileIsUsedUnchanged() {
        ProviderProfile profile = ChatController.withOverrides(zen, new ChatApiRequest());

        assertThat(profile).isSameAs(zen);
    }

    @Test
    void aCustomEndpointReplacesTheUrlAndTurnsOffTheCliDialect() {
        ChatApiRequest request = new ChatApiRequest();
        request.setBaseUrl("https://api.example.com/v1/");
        request.setApiKey("sk-test");

        ProviderProfile profile = ChatController.withOverrides(zen, request);

        assertThat(profile.endpoint()).isEqualTo("https://api.example.com/v1");
        assertThat(profile.apiKey()).isEqualTo("sk-test");
        assertThat(profile.opencodeGateway()).isFalse();
        assertThat(profile.forceStream()).isFalse();
        // the rest of the configured profile still applies for this call
        assertThat(profile.defaultModel()).isEqualTo("big-pickle");
        assertThat(profile.toolsEnabled()).isTrue();
        assertThat(profile.name()).isEqualTo("zen");
    }

    @Test
    void aKeyWithoutAnEndpointKeepsTheProviderDialect() {
        ChatApiRequest request = new ChatApiRequest();
        request.setApiKey("sk-test");

        ProviderProfile profile = ChatController.withOverrides(zen, request);

        assertThat(profile.endpoint()).isEqualTo("https://opencode.ai/zen/v1");
        assertThat(profile.apiKey()).isEqualTo("sk-test");
        assertThat(profile.opencodeGateway()).isTrue();
        assertThat(profile.forceStream()).isTrue();
    }

    @Test
    void blankOverridesAreIgnored() {
        ChatApiRequest request = new ChatApiRequest();
        request.setBaseUrl("   ");
        request.setApiKey("  ");

        assertThat(ChatController.withOverrides(zen, request)).isSameAs(zen);
    }
}
