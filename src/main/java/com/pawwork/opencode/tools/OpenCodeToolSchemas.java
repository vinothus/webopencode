package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.model.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Holds the official OpenCode CLI tool schemas so both the client (gateway injection) and the chat
 * service (tool list) share one parsed copy.
 *
 * <p>The schemas are also where the CLI's <em>instructions</em> live. The {@code bash} description
 * alone is over four thousand characters of hard-won guidance — when to shell out, when not to,
 * how to quote paths, how to verify a directory first. That guidance is why the CLI's agent
 * behaves, and it is worthless if it is thrown away.</p>
 *
 * <p>So the official schema wins: for every tool the CLI knows, the model is shown the CLI's own
 * description and argument schema. A locally implemented tool keeps its behaviour but is described
 * by the CLI, with a short note appended describing what differs here (the workspace root, the
 * shell, the shape of the JSON this server returns).</p>
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

    /**
     * The tool list to advertise: the CLI's schemas for every tool it knows, plus the local tools
     * it does not.
     *
     * @param localDefinitions the locally registered tools
     * @param description      turns the CLI's description into the one to advertise
     * @return the merged list, official tools first
     */
    public List<ToolDefinition> merge(List<ToolDefinition> localDefinitions,
                                      BiFunction<String, String, String> description) {
        return merge(tools, localDefinitions, description);
    }

    /**
     * Merges the official CLI schemas with the locally registered tools.
     *
     * <p>The official description wins. It is the part that carries the CLI's hard-won guidance —
     * when to shell out, that a file must be read before it is edited, that a plan is worth writing
     * down — and guidance that is replaced by a one-liner is guidance that is lost. The description
     * function adds what differs about this deployment: the workspace root, the shell, the shape of
     * the JSON the server returns.</p>
     *
     * <p>The official argument schema wins too, so the model uses the names the CLI's descriptions
     * talk about ({@code filePath}, {@code oldString}, {@code newString}). A tool the CLI knows but
     * this server does not implement is still advertised — the gateway's free tier requires the
     * names — but is marked as unavailable so the model does not waste a turn on it.</p>
     *
     * @param official         the CLI's tool schemas
     * @param localDefinitions the locally registered tools
     * @param description      turns the CLI's description into the one to advertise
     * @return the merged list, official tools first
     */
    public static List<ToolDefinition> merge(List<ToolDefinition> official,
                                              List<ToolDefinition> localDefinitions,
                                              BiFunction<String, String, String> description) {
        Map<String, ToolDefinition> local = new LinkedHashMap<>();
        if (localDefinitions != null) {
            for (ToolDefinition definition : localDefinitions) {
                String name = definition.functionName();
                if (name != null && !name.isBlank()) {
                    local.put(name, definition);
                }
            }
        }

        List<ToolDefinition> officialList = official == null ? List.of() : official;
        List<ToolDefinition> merged = new ArrayList<>(officialList.size() + local.size());
        for (ToolDefinition definition : officialList) {
            String name = definition.functionName();
            if (name == null || name.isBlank()) {
                continue;
            }
            boolean implemented = local.remove(name) != null;
            String text = description == null
                    ? descriptionOf(definition)
                    : description.apply(name, descriptionOf(definition));
            if (!implemented) {
                text = withNote(text, "Not implemented by this server: calling it returns an error. "
                        + "Do not call it.");
            }
            merged.add(ToolDefinition.of(name, text, parameters(definition)));
        }
        // Tools this server has and the CLI does not — they keep their own description.
        merged.addAll(local.values());
        return merged;
    }

    private static String descriptionOf(ToolDefinition definition) {
        return definition.getFunction() == null ? "" : nullToEmpty(definition.getFunction().getDescription());
    }

    private static com.fasterxml.jackson.databind.JsonNode parameters(ToolDefinition definition) {
        return definition.getFunction() == null ? null : definition.getFunction().getParameters();
    }

    private static String withNote(String description, String note) {
        if (note == null || note.isBlank()) {
            return description;
        }
        if (description == null || description.isBlank()) {
            return note;
        }
        return description + "\n\n" + note;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
