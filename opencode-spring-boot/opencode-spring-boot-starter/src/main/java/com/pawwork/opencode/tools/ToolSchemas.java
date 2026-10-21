package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Small shared JSON-Schema helpers for {@link ToolHandler} defaults.
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** {@code {"type":"object","properties":{}}} — "takes any arguments, requires none". */
    public static JsonNode emptyObject() {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        var schema = factory.objectNode();
        schema.set("type", factory.textNode("object"));
        schema.set("properties", factory.objectNode());
        schema.set("required", factory.arrayNode());
        return schema;
    }

    /** Builds a schema from a ready-made properties object plus a required list. */
    public static JsonNode objectSchema(JsonNode properties, JsonNode required) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        var schema = factory.objectNode();
        schema.set("type", factory.textNode("object"));
        schema.set("properties", properties == null ? factory.objectNode() : properties);
        schema.set("required", required == null ? factory.arrayNode() : required);
        return schema;
    }
}
