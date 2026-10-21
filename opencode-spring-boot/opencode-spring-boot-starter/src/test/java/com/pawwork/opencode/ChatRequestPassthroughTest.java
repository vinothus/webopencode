package com.pawwork.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.model.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An OpenAI SDK must be able to send any field of the spec and find it on the outgoing wire —
 * nothing may be dropped just because the library models the request with a fixed class.
 */
class ChatRequestPassthroughTest {

    private final ObjectMapper mapper = OpenAiClient.defaultMapper();

    @Test
    void everySpecFieldSurvivesSerialisation() throws Exception {
        String body = """
                {"model":"big-pickle",
                 "messages":[{"role":"user","content":"hi"}],
                 "temperature":0.2,
                 "top_p":0.9,
                 "n":1,
                 "stop":["\\n\\n","END"],
                 "seed":7,
                 "max_tokens":128,
                 "max_completion_tokens":64,
                 "presence_penalty":0.1,
                 "frequency_penalty":0.2,
                 "logit_bias":{"50256":-100},
                 "logprobs":true,
                 "top_logprobs":5,
                 "response_format":{"type":"json_object"},
                 "parallel_tool_calls":false,
                 "metadata":{"trace":"abc"},
                 "store":true,
                 "user":"cursor-user",
                 "tool_choice":"auto"}
                """;

        ChatRequest request = mapper.readValue(body, ChatRequest.class);
        JsonNode wire = mapper.valueToTree(request);

        assertThat(wire.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(wire.path("top_p").asDouble()).isEqualTo(0.9);
        assertThat(wire.path("n").asInt()).isEqualTo(1);
        assertThat(wire.path("stop").isArray()).isTrue();
        assertThat(wire.path("stop").get(1).asText()).isEqualTo("END");
        assertThat(wire.path("seed").asLong()).isEqualTo(7L);
        assertThat(wire.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(wire.path("max_completion_tokens").asInt()).isEqualTo(64);
        assertThat(wire.path("presence_penalty").asDouble()).isEqualTo(0.1);
        assertThat(wire.path("frequency_penalty").asDouble()).isEqualTo(0.2);
        assertThat(wire.path("logit_bias").path("50256").asInt()).isEqualTo(-100);
        assertThat(wire.path("logprobs").asBoolean()).isTrue();
        assertThat(wire.path("top_logprobs").asInt()).isEqualTo(5);
        assertThat(wire.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(wire.path("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(wire.path("metadata").path("trace").asText()).isEqualTo("abc");
        assertThat(wire.path("store").asBoolean()).isTrue();
        assertThat(wire.path("user").asText()).isEqualTo("cursor-user");
        assertThat(wire.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void fieldsTheClassDoesNotKnowAboutStillReachTheProvider() throws Exception {
        // Forward compatibility: when OpenAI adds a field, we forward it instead of dropping it.
        ChatRequest request = mapper.readValue(
                "{\"model\":\"m\",\"messages\":[],\"service_tier\":\"auto\",\"some_future_field\":42}",
                ChatRequest.class);

        JsonNode wire = mapper.valueToTree(request);

        assertThat(wire.path("service_tier").asText()).isEqualTo("auto");
        assertThat(wire.path("some_future_field").asInt()).isEqualTo(42);
    }

    @Test
    void multimodalMessageContentIsPreservedAsParts() throws Exception {
        ChatRequest request = mapper.readValue("""
                {"model":"m","messages":[{"role":"user","content":[
                   {"type":"text","text":"what is this?"},
                   {"type":"image_url","image_url":{"url":"https://example.invalid/x.png"}}]}]}
                """, ChatRequest.class);

        JsonNode content = mapper.valueToTree(request.getMessages().get(0)).path("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(2);
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
    }
}
