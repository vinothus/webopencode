package com.pawwork.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.SseAccumulator;
import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folding an SSE stream back into one response — including the two traps the Android client
 * documents: null fragment fields must not clobber earlier ones, and JSON-null content is not text.
 */
class SseAccumulatorTest {

    private final ObjectMapper mapper = OpenAiClient.defaultMapper();

    @Test
    void assemblesContentAcrossChunks() throws IOException {
        SseAccumulator accumulator = new SseAccumulator();
        accept(accumulator, """
                {"id":"chatcmpl-1","model":"big-pickle","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""");
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"content":"Hello "}}]}""");
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"content":"world"}}]}""");
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""");

        ChatResponse response = accumulator.toResponse();

        assertThat(response.getId()).isEqualTo("chatcmpl-1");
        assertThat(response.getModel()).isEqualTo("big-pickle");
        assertThat(response.firstMessage().contentAsText()).isEqualTo("Hello world");
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.firstMessage().getToolCalls()).isNull();
    }

    @Test
    void jsonNullContentIsNotTreatedAsText() {
        SseAccumulator accumulator = new SseAccumulator();
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"content":null,"reasoning_content":null}}]}""");

        assertThat(accumulator.text()).isEmpty();
    }

    @Test
    void fallsBackToReasoningWhenTheModelWroteNoVisibleText() {
        SseAccumulator accumulator = new SseAccumulator();
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"reasoning_content":"thinking…"}}]}""");

        ChatResponse response = accumulator.toResponse();
        assertThat(response.firstMessage().contentAsText()).isEqualTo("thinking…");
        assertThat(response.firstMessage().getReasoningContent()).isEqualTo("thinking…");
    }

    @Test
    void stitchesToolCallFragmentsWithoutLosingTheFirstIdAndName() {
        SseAccumulator accumulator = new SseAccumulator();
        // First fragment carries the identity…
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc",
                  "type":"function","function":{"name":"calculate","arguments":"{"}}]}}]}""");
        // …later ones are pure argument fragments with null id/name, which must not clobber it.
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":null,
                  "function":{"name":null,"arguments":"\\"expression\\":\\"2+2\\"}"}}]}}]}""");

        ChatResponse response = accumulator.toResponse();
        ToolCall call = response.firstMessage().getToolCalls().get(0);

        assertThat(call.getId()).isEqualTo("call_abc");
        assertThat(call.functionName()).isEqualTo("calculate");
        assertThat(call.rawArguments()).isEqualTo("{\"expression\":\"2+2\"}");
        assertThat(call.getType()).isEqualTo("function");
        assertThat(accumulator.toolCallCount()).isEqualTo(1);
    }

    @Test
    void separateToolCallsKeepTheirIndexes() throws IOException {
        SseAccumulator accumulator = new SseAccumulator();
        accept(accumulator, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"index":0,"id":"call_1","function":{"name":"a","arguments":"{}"}},
                  {"index":1,"id":"call_2","function":{"name":"b","arguments":"{}"}}]}}]}""");

        ChatResponse response = accumulator.toResponse();
        assertThat(response.firstMessage().getToolCalls())
                .extracting(ToolCall::functionName)
                .containsExactly("a", "b");
    }

    @Test
    void mergesUsageBlocks() {
        SseAccumulator accumulator = new SseAccumulator();
        accept(accumulator, """
                {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""");
        accept(accumulator, """
                {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""");

        assertThat(accumulator.toResponse().getUsage().getTotalTokens()).isEqualTo(20);
        assertThat(accumulator.toResponse().getUsage().getPromptTokens()).isEqualTo(12);
    }

    private void accept(SseAccumulator accumulator, String chunkJson) {
        try {
            accumulator.accept(mapper.readValue(chunkJson, ChatChunk.class));
        } catch (IOException e) {
            throw new AssertionError("bad test fixture: " + chunkJson, e);
        }
    }
}
