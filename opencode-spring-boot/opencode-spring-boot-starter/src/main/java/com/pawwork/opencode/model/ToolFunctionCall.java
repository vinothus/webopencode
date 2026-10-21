package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code function} half of a {@link ToolCall}: the tool name plus its JSON arguments.
 *
 * <p>{@code arguments} is a <em>encoded JSON string</em> on the OpenAI wire (not a nested object),
 * which is why it is modelled as {@link String}. During SSE streaming it arrives in fragments that
 * must be concatenated in order.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "FunctionDefinition")
public class ToolFunctionCall {

    private String name;

    /** JSON-encoded arguments, possibly a fragment while streaming. */
    private String arguments;

    public ToolFunctionCall() {
    }

    public ToolFunctionCall(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }
}
