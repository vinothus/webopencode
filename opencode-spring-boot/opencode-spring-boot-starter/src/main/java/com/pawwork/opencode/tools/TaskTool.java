package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code task} tool: launches a sub-agent — a nested, isolated conversation that runs its own
 * model⇄tool loop and returns only its final answer.
 *
 * <p>The sub-agent gets a fresh session id, so its intermediate steps never pollute the parent
 * conversation's history: the parent sees one request in, one answer out. Depth is bounded so a
 * {@code task} that calls {@code task} cannot recurse forever.</p>
 */
public final class TaskTool {

    /** What the tool needs from the chat layer: run one isolated turn. */
    @FunctionalInterface
    public interface SubAgent {
        /**
         * @param prompt     the task for the sub-agent
         * @param depth      how deep this sub-agent sits (1 for a task called from a normal turn)
         * @return the sub-agent's final answer
         */
        String run(String prompt, int depth);
    }

    private final ObjectMapper mapper;
    private final SubAgent subAgent;
    private final int maxDepth;

    public TaskTool(ObjectMapper mapper, SubAgent subAgent, int maxDepth) {
        this.mapper = mapper;
        this.subAgent = subAgent;
        this.maxDepth = Math.max(1, maxDepth);
    }

    public ToolHandler handler() {
        return handler(1);
    }

    /** A handler bound to a recursion depth, so nested calls can refuse to go deeper. */
    public ToolHandler handler(int depth) {
        return new ToolHandler() {
            @Override
            public String name() {
                return "task";
            }

            @Override
            public String description() {
                return "Launch a sub-agent to handle a complex, multi-step task autonomously. "
                        + "The sub-agent runs its own tool loop in a fresh context and returns only "
                        + "its final answer. Use for research or multi-step work; use read/grep "
                        + "directly for a single file or symbol lookup.";
            }

            @Override
            public JsonNode parameters() {
                ObjectNode properties = mapper.createObjectNode();
                properties.set("description", typed("string", "A short (3-5 word) description of the task"));
                properties.set("prompt", typed("string", "The task for the sub-agent to perform"));
                properties.set("subagent_type", typed("string", "Optional agent type label"));

                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", properties);
                schema.putArray("required").add("description").add("prompt");
                return schema;
            }

            @Override
            public String execute(JsonNode arguments) {
                if (depth > maxDepth) {
                    return error("task recursion limit reached (" + maxDepth
                            + " levels) — finish this level without launching another sub-agent");
                }
                String prompt = arguments.path("prompt").asText("").trim();
                if (prompt.isEmpty()) {
                    return error("'prompt' is required");
                }
                String description = arguments.path("description").asText("").trim();

                long started = System.currentTimeMillis();
                try {
                    String answer = subAgent.run(prompt, depth + 1);
                    ObjectNode result = mapper.createObjectNode();
                    result.put("description", description);
                    result.put("answer", answer == null ? "" : answer);
                    result.put("durationMs", System.currentTimeMillis() - started);
                    return result.toString();
                } catch (Exception e) {
                    return error("sub-agent failed: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
        };
    }

    private ObjectNode typed(String type, String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("description", description);
        return node;
    }

    private String error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }
}
