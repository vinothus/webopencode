package com.pawwork.opencode;

import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.OpenCodeIdentity;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.tools.OpenCodeToolSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real call through the OpenCode gateway — the whole point of the library.
 *
 * <p>Skipped unless explicitly enabled, because it needs network access:</p>
 * <pre>mvn test -Dopencode.test.live=true</pre>
 */
@EnabledIfSystemProperty(named = "opencode.test.live", matches = "true")
class LiveGatewayTest {

    private OpenAiClient client() {
        OpenCodeProperties properties = new OpenCodeProperties();
        OpenCodeToolSchemas schemas = OpenCodeToolSchemas.load(OpenAiClient.defaultMapper());
        return new OpenAiClient(properties, new OpenCodeIdentity("live-test"),
                OpenAiClient.defaultMapper(), schemas.tools());
    }

    @Test
    void nonStreamingCallIsStreamedToTheGatewayAndFoldedBackIntoOneResponse() {
        try (OpenAiClient client = client()) {
            ProviderProfile zen = new OpenCodeProperties().resolve("zen");
            ChatRequest request = new ChatRequest("big-pickle",
                    new ArrayList<>(List.of(ChatMessage.user("Reply with exactly: PONG"))));

            ChatResponse response = client.complete(zen, request);

            assertThat(response.firstMessage().contentAsText()).contains("PONG");
            assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        }
    }

    @Test
    void officialToolSchemasArePresentInTheOutgoingRequest() {
        OpenCodeProperties properties = new OpenCodeProperties();
        OpenCodeToolSchemas schemas = OpenCodeToolSchemas.load(OpenAiClient.defaultMapper());

        assertThat(schemas.names())
                .contains("bash", "read", "write", "webfetch", "todowrite")
                .hasSize(11);
        assertThat(properties.resolve("zen").usesGatewayHeaders()).isTrue();
    }
}
