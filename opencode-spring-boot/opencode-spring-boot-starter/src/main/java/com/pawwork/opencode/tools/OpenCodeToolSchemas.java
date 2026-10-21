package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.model.ToolDefinition;

import java.util.List;

/**
 * Holds the official OpenCode CLI tool schemas so both the client (gateway injection) and the chat
 * service (tool list) share one parsed copy.
 */
public class OpenCodeToolSchemas {

    private final List<ToolDefinition> tools;

    public OpenCodeToolSchemas(List<ToolDefinition> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Parses {@code opencode_tools.json} from the classpath. */
    public static OpenCodeToolSchemas load(ObjectMapper mapper) {
        return new OpenCodeToolSchemas(OfficialOpenCodeTools.load(mapper));
    }

    public List<ToolDefinition> tools() {
        return tools;
    }

    public List<String> names() {
        return tools.stream().map(ToolDefinition::functionName).toList();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}
