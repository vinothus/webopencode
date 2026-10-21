package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single tool invocation requested by the model, either complete (as it comes back from a
 * non-streaming response) or as a partial fragment while an SSE stream is still being assembled.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionMessageToolCall")
public class ToolCall {

    /** Call id, echoed back verbatim in the matching {@code tool} message as {@code tool_call_id}. */
    private String id;

    /** Always {@code function} for OpenAI-compatible gateways. */
    private String type;

    private ToolFunctionCall function;

    /**
     * SSE delta position of this call. Streaming models split one call across many chunks
     * (id/name arrive once, {@code arguments} arrive as string fragments), so the index is what
     * stitches them back together.
     */
    private Integer index;

    public ToolCall() {
    }

    public ToolCall(String id, String type, ToolFunctionCall function) {
        this.id = id;
        this.type = type;
        this.function = function;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ToolFunctionCall getFunction() {
        return function;
    }

    public void setFunction(ToolFunctionCall function) {
        this.function = function;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    /** Convenience: the tool name, never {@code null} — empty when the fragment has not arrived yet. */
    public String functionName() {
        return function == null || function.getName() == null ? "" : function.getName();
    }

    /** Convenience: the raw (possibly partial) JSON arguments string. */
    public String rawArguments() {
        return function == null || function.getArguments() == null ? "{}" : function.getArguments();
    }

    @Override
    public String toString() {
        return "ToolCall{id=" + id + ", name=" + functionName() + "}";
    }
}
