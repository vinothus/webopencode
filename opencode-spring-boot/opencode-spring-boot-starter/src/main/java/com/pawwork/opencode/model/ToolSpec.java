package com.pawwork.opencode.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code function} half of a {@link ToolDefinition}: name, description and JSON Schema for the
 * arguments.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolSpec {

    private String name;

    private String description;

    /** JSON Schema ({@code {"type":"object","properties":{...}}}). */
    private JsonNode parameters;

    public ToolSpec() {
    }

    public ToolSpec(String name, String description, JsonNode parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getParameters() {
        return parameters;
    }

    public void setParameters(JsonNode parameters) {
        this.parameters = parameters;
    }
}
