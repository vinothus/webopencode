package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.model.ToolDefinition;
import com.pawwork.opencode.model.ToolExecution;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /**
     * Shortens a result to the configured limit <em>without</em> cutting through a JSON string.
     *
     * <p>Slicing the payload at an arbitrary character left a document that no longer parsed and
     * chopped quoted values in half — which is what "the output of a quoted command is truncated"
     * looked like from the model's side. When the payload is a JSON object, its longest textual
     * field is shortened instead and {@code "truncated": true} is recorded, so the result stays
     * valid JSON and still begins with the real content.</p>
     */
    private String truncate(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() <= resultLimit) {
            return result;
        }

        JsonNode parsed = parse(result);
        if (parsed != null && parsed.isContainerNode()) {
            String shortened = shrinkToFit(parsed).toString();
            if (shortened.length() <= resultLimit) {
                return shortened;
            }
            return envelope(result);
        }

        // Plain text (not JSON): a character-boundary cut with a marker is readable and lossless
        // up to the limit. Only JSON needs the structural treatment below.
        return result.substring(0, safeCut(result, resultLimit))
                + "\n…[truncated at " + resultLimit + " chars]";
    }

    /**
     * Shortens a JSON document in place until it fits, keeping it valid.
     *
     * <p>Two passes: shrink the longest textual field (a {@code read} or {@code bash} result is one
     * huge string), then drop the tail of the longest array (a {@code grep} result is many small
     * entries). {@code "truncated": true} marks the result either way.</p>
     */
    private JsonNode shrinkToFit(JsonNode parsed) {
        ObjectNode object;
        if (parsed.isObject()) {
            object = (ObjectNode) parsed;
        } else {
            // a top-level array: wrap it so the truncation flag has somewhere to live
            object = mapper.createObjectNode();
            object.set("items", parsed);
        }
        object.put("truncated", true);
        long overflow = (long) object.toString().length() - resultLimit;
        if (overflow > 0) {
            shortenLongestText(object, overflow + 96);
        }
        while (object.toString().length() > resultLimit && trimLongestArray(object)) {
            // keep trimming
        }
        return object;
    }

    /**
     * Last resort for a document that cannot be shrunk below the limit: hand back a small, valid
     * JSON object with the head of the payload instead of a document cut inside a quoted string.
     */
    private String envelope(String text) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("truncated", true);
        envelope.put("note", "the tool result was longer than " + resultLimit + " characters");
        int budget = Math.max(0, resultLimit - envelope.toString().length() - 40);
        envelope.put("excerpt", text.substring(0, safeCut(text, budget)));
        return envelope.toString();
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** Cuts {@code overflow} characters plus a marker from the longest textual leaf. */
    private boolean shortenLongestText(JsonNode node, long overflow) {
        TextHolder holder = new TextHolder();
        findLongestText(node, null, null, holder);
        if (holder.parent == null || holder.text.isEmpty()) {
            return false;
        }
        int keep = (int) Math.max(0, holder.text.length() - overflow);
        String head = holder.text.substring(0, safeCut(holder.text, keep));
        holder.parent.put(holder.field,
                head + "\n…[truncated " + (holder.text.length() - head.length()) + " characters]");
        return true;
    }

    private void findLongestText(JsonNode node, ObjectNode parent, String field, TextHolder into) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() > into.length) {
                into.length = text.length();
                into.parent = parent;
                into.field = field;
                into.text = text;
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    findLongestText(entry.getValue(), (ObjectNode) node, entry.getKey(), into));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                findLongestText(child, null, null, into);
            }
        }
    }

    /** Removes the last element of the array that contributes the most characters. */
    private boolean trimLongestArray(JsonNode node) {
        ArrayNode longest = null;
        if (node.isObject()) {
            for (JsonNode child : node) {
                ArrayNode candidate = longestArray(child);
                if (candidate != null && (longest == null
                        || candidate.toString().length() > longest.toString().length())) {
                    longest = candidate;
                }
            }
        } else {
            longest = longestArray(node);
        }
        if (longest == null || longest.isEmpty()) {
            return false;
        }
        longest.remove(longest.size() - 1);
        return true;
    }

    private ArrayNode longestArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            return (ArrayNode) node;
        }
        ArrayNode best = null;
        if (node.isObject()) {
            for (JsonNode child : node) {
                ArrayNode candidate = longestArray(child);
                if (candidate != null && (best == null
                        || candidate.toString().length() > best.toString().length())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** An index that never lands between the two halves of a surrogate pair. */
    private static int safeCut(String text, int index) {
        if (index <= 0) {
            return 0;
        }
        if (index >= text.length()) {
            return text.length();
        }
        return Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1 : index;
    }

    /** Where the longest textual leaf lives, so it can be replaced in place. */
    private static final class TextHolder {
        private int length;
        private ObjectNode parent;
        private String field;
        private String text = "";
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
