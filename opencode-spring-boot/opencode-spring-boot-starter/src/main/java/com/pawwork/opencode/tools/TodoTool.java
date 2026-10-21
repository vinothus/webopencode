package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code todowrite} tool: a structured task list the model maintains for a session.
 *
 * <p>Each call replaces that session's whole list, exactly like the OpenCode CLI's tool. The state is
 * held in memory — it is scratch space for the model's planning, not durable storage — and can be
 * read back by the UI to render progress.</p>
 */
public final class TodoTool {

    private final ObjectMapper mapper;
    /** sessionId → the current list, in order. */
    private final Map<String, List<ObjectNode>> bySession = new ConcurrentHashMap<>();
    /** sessionId → generation counter, bumped on every write, for cheap change detection. */
    private final Map<String, Integer> revisions = new ConcurrentHashMap<>();

    public TodoTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ToolHandler handler() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "todowrite";
            }

            @Override
            public String description() {
                return "Create and update a structured task list for the current session. "
                        + "Replaces the whole list on every call. Use when work has three or more "
                        + "distinct steps, marking exactly one item in_progress at a time.";
            }

            @Override
            public JsonNode parameters() {
                ObjectNode todoItem = mapper.createObjectNode();
                todoItem.put("type", "object");
                ObjectNode itemProps = mapper.createObjectNode();
                itemProps.set("content", typed("string", "Brief description of the task"));
                itemProps.set("status", typed("string",
                        "One of: pending, in_progress, completed, cancelled"));
                itemProps.set("priority", typed("string", "One of: high, medium, low"));
                todoItem.set("properties", itemProps);
                ArrayNode itemRequired = todoItem.putArray("required");
                itemRequired.add("content");
                itemRequired.add("status");
                itemRequired.add("priority");

                ObjectNode todos = mapper.createObjectNode();
                todos.put("type", "array");
                todos.set("items", todoItem);
                todos.put("description", "The updated todo list");

                ObjectNode properties = mapper.createObjectNode();
                properties.set("todos", todos);

                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", properties);
                schema.putArray("required").add("todos");
                return schema;
            }

            @Override
            public String execute(JsonNode arguments) {
                return write(arguments);
            }
        };
    }

    /** Validates and stores the list, returning a small summary the model can rely on. */
    private String write(JsonNode arguments) {
        JsonNode todos = arguments.path("todos");
        if (!todos.isArray()) {
            return "{\"error\":\"'todos' must be an array\"}";
        }

        List<ObjectNode> parsed = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int inProgress = 0;

        for (int i = 0; i < todos.size(); i++) {
            JsonNode item = todos.get(i);
            String content = item.path("content").asText("").trim();
            String status = item.path("status").asText("").trim().toLowerCase();
            String priority = item.path("priority").asText("").trim().toLowerCase();

            if (content.isEmpty()) {
                problems.add("item " + i + ": 'content' is required");
                continue;
            }
            if (!List.of("pending", "in_progress", "completed", "cancelled").contains(status)) {
                problems.add("item " + i + ": bad status '" + status + "'");
                continue;
            }
            if (!List.of("high", "medium", "low").contains(priority)) {
                problems.add("item " + i + ": bad priority '" + priority + "'");
                continue;
            }
            if ("in_progress".equals(status)) {
                inProgress++;
            }

            ObjectNode stored = mapper.createObjectNode();
            stored.put("content", content);
            stored.put("status", status);
            stored.put("priority", priority);
            parsed.add(stored);
        }

        if (!problems.isEmpty()) {
            ObjectNode error = mapper.createObjectNode();
            error.put("error", "invalid todo list: " + String.join("; ", problems));
            return error.toString();
        }

        String sessionId = ToolContext.current().sessionId();
        bySession.put(sessionId, parsed);
        revisions.merge(sessionId, 1, Integer::sum);

        ObjectNode result = mapper.createObjectNode();
        result.put("updated", parsed.size());
        result.put("inProgress", inProgress);
        result.put("pending", count(parsed, "pending"));
        result.put("completed", count(parsed, "completed"));
        result.put("cancelled", count(parsed, "cancelled"));
        ArrayNode list = result.putArray("todos");
        parsed.forEach(list::add);
        if (inProgress > 1) {
            result.put("warning", inProgress + " items are in_progress; keep it to one at a time.");
        }
        return result.toString();
    }

    private static long count(List<ObjectNode> items, String status) {
        return items.stream().filter(item -> status.equals(item.path("status").asText())).count();
    }

    /** The current list for a session, newest first-write wins; empty when never set. */
    public List<ObjectNode> todos(String sessionId) {
        List<ObjectNode> stored = bySession.get(sessionId == null ? "" : sessionId);
        return stored == null ? List.of() : List.copyOf(stored);
    }

    /** Monotonic revision for a session, so a poller can skip unchanged lists. */
    public int revision(String sessionId) {
        return revisions.getOrDefault(sessionId == null ? "" : sessionId, 0);
    }

    /** Everything, for a global view. */
    public Map<String, List<ObjectNode>> all() {
        return new LinkedHashMap<>(bySession);
    }

    public void clear(String sessionId) {
        bySession.remove(sessionId == null ? "" : sessionId);
        revisions.remove(sessionId == null ? "" : sessionId);
    }

    private ObjectNode typed(String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }
}
