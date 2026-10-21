package com.pawwork.opencode;

import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.Choice;
import com.pawwork.opencode.model.ToolCall;
import com.pawwork.opencode.model.ToolDefinition;
import com.pawwork.opencode.tools.DeclaredTools;
import com.pawwork.opencode.tools.OpenCodeToolSchemas;
import com.pawwork.opencode.client.OpenAiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pass-through must never hand a client a tool call it did not declare — the gateway forces
 * the official CLI tools into every upstream body, but they must stay invisible downstream.
 */
class DeclaredToolsTest {

    private static ChatResponse responseWithCalls(String... names) {
        ChatResponse response = new ChatResponse();
        Choice choice = new Choice();
        ChatMessage message = new ChatMessage();
        message.setRole("assistant");
        message.setToolCalls(java.util.Arrays.stream(names)
                .map(name -> new ToolCall("call_" + name, "function",
                        new com.pawwork.opencode.model.ToolFunctionCall(name, "{}")))
                .toList());
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    @Test
    void declaredNamesComeFromTheCallersOwnTools() {
        ChatRequest request = new ChatRequest();
        request.setTools(List.of(ToolDefinition.of("calculate", "Math calculator",
                OpenAiClient.defaultMapper().createObjectNode())));

        assertThat(DeclaredTools.declared(request)).containsExactly("calculate");
        assertThat(DeclaredTools.declared(new ChatRequest())).isEmpty();
        assertThat(DeclaredTools.declared(null)).isEmpty();
    }

    @Test
    void injectedOfficialToolsAreReportedAsUndeclared() {
        ChatResponse response = responseWithCalls("bash");

        assertThat(DeclaredTools.hasUndeclared(response, Set.of("calculate"))).isTrue();
        assertThat(DeclaredTools.undeclared(response, Set.of("calculate"))).containsExactly("bash");
        assertThat(DeclaredTools.hasUndeclared(response, Set.of("bash"))).isFalse();
        assertThat(DeclaredTools.hasUndeclared(response, Set.of())).isTrue();
    }

    @Test
    void aCallWithNoNameIsUndeclaredToo() {
        ChatResponse response = responseWithCalls("(unnamed)");

        assertThat(DeclaredTools.undeclared(response, Set.of("calculate")))
                .containsExactly("(unnamed)");
    }

    @Test
    void plainContentResponsesCarryNoForeignCalls() {
        ChatResponse response = new ChatResponse();
        Choice choice = new Choice();
        choice.setMessage(ChatMessage.assistant("just text"));
        response.setChoices(List.of(choice));

        assertThat(DeclaredTools.hasUndeclared(response, Set.of())).isFalse();
        assertThat(DeclaredTools.toolCalls(response)).isEmpty();
    }

    @Test
    void aChunkCountsAsContentOnlyOnceTextArrives() {
        assertThat(DeclaredTools.carriesContent(chunkWith("tool", null, null))).isFalse();
        assertThat(DeclaredTools.carriesContent(chunkWith("assistant", "", null))).isFalse();
        assertThat(DeclaredTools.carriesContent(chunkWith("assistant", "PONG", null))).isTrue();
        // Reasoning fragments are not answer text either — they stay bufferable, so a response
        // that reasons and then only calls tools is corrected before anything is relayed.
        assertThat(DeclaredTools.carriesContent(chunkWith(null, null, "thinking…"))).isFalse();
    }

    private ChatChunk chunkWith(String role, String content, String reasoning) {
        com.pawwork.opencode.model.Delta delta = new com.pawwork.opencode.model.Delta();
        delta.setRole(role);
        delta.setContent(content);
        delta.setReasoningContent(reasoning);
        com.pawwork.opencode.model.ChunkChoice choice = new com.pawwork.opencode.model.ChunkChoice();
        choice.setIndex(0);
        choice.setDelta(delta);
        ChatChunk chunk = new ChatChunk();
        chunk.setChoices(List.of(choice));
        return chunk;
    }

    @Test
    void allElevenOfficialToolsAreRecognisedByTheNamesTheGateChecks() {
        OpenCodeToolSchemas schemas = OpenCodeToolSchemas.load(OpenAiClient.defaultMapper());
        ChatRequest wire = new ChatRequest();
        wire.setTools(schemas.tools());

        assertThat(DeclaredTools.declared(wire))
                .contains("bash", "edit", "glob", "grep", "read", "skill", "task",
                        "todowrite", "webfetch", "websearch", "write");
    }
}
