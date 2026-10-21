package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tool <em>definition</em> — what we advertise to the model, not what it calls.
 *
 * <pre>{@code
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "websearch",
 *     "description": "...",
 *     "parameters": { "type": "object", "properties": { ... } }
 *   }
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionTool")
public class ToolDefinition {

    @JsonProperty("type")
    private String type = "function";

    private ToolSpec function;

    public ToolDefinition() {
    }

    public ToolDefinition(ToolSpec function) {
        this.function = function;
    }

    public static ToolDefinition of(String name, String description, JsonNode parameters) {
        return new ToolDefinition(new ToolSpec(name, description, parameters));
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public ToolSpec getFunction() {
        return function;
    }

    public void setFunction(ToolSpec function) {
        this.function = function;
    }

    /** Never {@code null} — safe to call while deserialising partial payloads. */
    public String functionName() {
        return function == null || function.getName() == null ? "" : function.getName();
    }
}
