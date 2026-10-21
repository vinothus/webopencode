package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.model.ToolDefinition;
import com.pawwork.opencode.model.ToolExecution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of the tools this application lets the model call.
 *
 * <p>Handlers arrive as Spring beans ({@link ToolHandler}); results are returned to the model as
 * the JSON string of a {@code tool} message, with every failure funnelled into an
 * {@code {"error": …}} payload so a broken tool never breaks the loop — exactly how the Android
 * reference client's {@code ToolRegistry.execute} behaves.</p>
 */
public class ToolRegistry {

    private final ObjectMapper mapper;
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final int resultLimit;

    public ToolRegistry(ObjectMapper mapper, Collection<ToolHandler> handlers, int resultLimit) {
        this.mapper = mapper;
        this.resultLimit = resultLimit > 0 ? resultLimit : 4000;
        if (handlers != null) {
            handlers.forEach(this::register);
        }
    }

    /** Registers (or replaces) a handler. Later registrations win on name clashes. */
    public void register(ToolHandler handler) {
        if (handler != null && handler.name() != null && !handler.name().isBlank()) {
            handlers.put(handler.name().trim(), handler);
        }
    }

    public Optional<ToolHandler> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(handlers.get(name));
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    public List<String> names() {
        return new ArrayList<>(handlers.keySet());
    }

    /** Definitions of every registered tool, in registration order. */
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> out = new ArrayList<>(handlers.size());
        for (ToolHandler handler : handlers.values()) {
            out.add(handler.definition());
        }
        return out;
    }

    /**
     * Runs a tool and returns the result string to hand back to the model, truncating anything
     * longer than the configured limit. Never throws: unknown tools and failing tools come back as
     * {@code {"error": …}}.
     */
    public String execute(String name, String argumentsJson) {
        ToolExecution execution = executeDetailed(name, argumentsJson);
        return execution.getResult();
    }

    /** Same as {@link #execute} but also reports success, duration and the raw arguments. */
    public ToolExecution executeDetailed(String name, String argumentsJson) {
        long started = System.currentTimeMillis();
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;

        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            return new ToolExecution(name, raw,
                    errorJson("unknown tool: " + name + " (registered: " + handlers.keySet() + ")"),
                    false, 0L);
        }

        JsonNode arguments = parseArguments(raw);
        try {
            String result = handler.execute(arguments);
            String payload = truncate(result == null ? "" : result);
            return new ToolExecution(name, raw, payload, !looksLikeFailure(payload),
                    System.currentTimeMillis() - started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolExecution(name, raw, errorJson("tool interrupted"), false,
                    System.currentTimeMillis() - started);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ToolExecution(name, raw, errorJson(message), false,
                    System.currentTimeMillis() - started);
        }
    }

    private JsonNode parseArguments(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // models occasionally emit trailing commas or unescaped newlines — fall back below
        }
        return mapper.createObjectNode();
    }

    private String truncate(String result) {
        if (result.length() <= resultLimit) {
            return result;
        }
        return result.substring(0, resultLimit) + "\n…[truncated at " + resultLimit + " chars]";
    }

    /**
     * Heuristic mirrored from the Android client: a result counts as a failure when it carries an
     * {@code error}, an {@code error_msg}, or an explicit {@code ok:false}.
     */
    private boolean looksLikeFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        String trimmed = result.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = mapper.readTree(trimmed);
                if (node.isObject()) {
                    if (node.hasNonNull("error") || node.hasNonNull("error_msg")) {
                        return true;
                    }
                    if (node.has("ok") && !node.get("ok").asBoolean(true)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // not JSON after all — fall through to the textual checks
            }
        }
        String head = trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
        return head.startsWith("ERROR") || head.startsWith("EXCEPTION") || head.startsWith("⚠️");
    }

    private static String errorJson(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", message == null ? "tool failed" : message);
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"" + (message == null ? "tool failed" : message.replace('"', '\'')) + "\"}";
        }
    }
}
