package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.pawwork.opencode.model.ToolDefinition;
import com.pawwork.opencode.model.ToolSpec;

import java.util.Map;

/**
 * A tool the model may call and this application answers.
 *
 * <p>Register one as a Spring bean and it is advertised automatically (its name, description and
 * JSON Schema ride along in the {@code tools} array; the executed result is handed back to the
 * model as a {@code tool} message).</p>
 *
 * <pre>{@code
 * @Bean
 * ToolHandler currentTime() {
 *     return new ToolHandler() {
 *         public String name() { return "current_time"; }
 *         public String description() { return "Current server time"; }
 *         public String execute(JsonNode args) { return Instant.now().toString(); }
 *     };
 * }
 * }</pre>
 */
public interface ToolHandler {

    /** Unique tool name as the model should call it. */
    String name();

    /** One-line purpose; the model picks tools by reading this. */
    default String description() {
        return "Tool " + name();
    }

    /**
     * JSON Schema for the arguments. Defaults to an object with no required properties, which
     * tells the model it may call the tool with no arguments.
     */
    default JsonNode parameters() {
        return ToolSchemas.emptyObject();
    }

    /**
     * Runs the tool.
     *
     * @param arguments already-parsed JSON arguments; never {@code null} (empty object when the
     *                  model supplied none)
     * @return the result handed back to the model — usually a JSON string
     * @throws Exception translated to an {@code {"error": ...}} result by the registry
     */
    String execute(JsonNode arguments) throws Exception;

    /** The definition sent to the gateway. */
    default ToolDefinition definition() {
        return ToolDefinition.of(name(), description(), parameters());
    }

    /**
     * Convenience for declaring a tool whose schema has required string properties.
     *
     * @param requiredProperties property name → human description, all required strings
     */
    static ToolSpec spec(String name, String description, Map<String, String> requiredProperties) {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var properties = factory.objectNode();
        var required = factory.arrayNode();
        requiredProperties.forEach((prop, doc) -> {
            var node = factory.objectNode();
            node.put("type", "string");
            node.put("description", doc);
            properties.set(prop, node);
            required.add(prop);
        });
        var schema = factory.objectNode();
        schema.set("type", factory.textNode("object"));
        schema.set("properties", properties);
        schema.set("required", required);
        return new ToolSpec(name, description, schema);
    }
}
