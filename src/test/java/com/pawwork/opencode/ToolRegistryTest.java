package com.pawwork.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.model.ToolExecution;
import com.pawwork.opencode.tools.ToolHandler;
import com.pawwork.opencode.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool execution contract: failures come back as results the model can read, never as exceptions
 * that would break the loop.
 */
class ToolRegistryTest {

    private final ObjectMapper mapper = OpenAiClient.defaultMapper();

    private ToolRegistry registry(int resultLimit) {
        return new ToolRegistry(mapper, List.of(
                new ToolHandler() {
                    @Override
                    public String name() {
                        return "echo";
                    }

                    @Override
                    public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                        return "{\"echo\":\"" + arguments.path("text").asText("") + "\"}";
                    }
                },
                new ToolHandler() {
                    @Override
                    public String name() {
                        return "boom";
                    }

                    @Override
                    public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                        throw new IllegalStateException("kaboom");
                    }
                },
                new ToolHandler() {
                    @Override
                    public String name() {
                        return "big";
                    }

                    @Override
                    public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                        return "x".repeat(500);
                    }
                }), resultLimit);
    }

    @Test
    void executesARegisteredTool() {
        ToolRegistry registry = registry(4000);
        ToolExecution execution = registry.executeDetailed("echo", "{\"text\":\"hi\"}");

        assertThat(execution.isSuccess()).isTrue();
        assertThat(execution.getResult()).isEqualTo("{\"echo\":\"hi\"}");
        assertThat(registry.names()).containsExactly("echo", "boom", "big");
    }

    @Test
    void unknownToolComesBackAsAnErrorPayloadTheModelCanRead() {
        ToolExecution execution = registry(4000).executeDetailed("nope", "{}");

        assertThat(execution.isSuccess()).isFalse();
        assertThat(execution.getResult()).contains("unknown tool: nope").contains("echo");
    }

    @Test
    void aThrowingToolDoesNotEscapeTheRegistry() {
        ToolExecution execution = registry(4000).executeDetailed("boom", "{}");

        assertThat(execution.isSuccess()).isFalse();
        assertThat(execution.getResult()).contains("kaboom");
    }

    @Test
    void blankOrNullArgumentsAreTreatedAsAnEmptyObject() {
        ToolRegistry registry = registry(4000);

        assertThat(registry.executeDetailed("echo", null).isSuccess()).isTrue();
        assertThat(registry.executeDetailed("echo", "  ").isSuccess()).isTrue();
        // Malformed JSON from the model degrades to {} instead of failing the call.
        assertThat(registry.executeDetailed("echo", "not json").isSuccess()).isTrue();
    }

    @Test
    void longResultsAreTruncatedToTheConfiguredLimit() {
        ToolExecution execution = registry(100).executeDetailed("big", "{}");

        assertThat(execution.getResult()).hasSizeLessThanOrEqualTo(100 + 40);
        assertThat(execution.getResult()).contains("[truncated at 100 chars]");
    }

    /**
     * Truncation must not cut through a JSON string. The old implementation sliced the payload at
     * an arbitrary character, so a quoted value came back half-written and the document no longer
     * parsed — which is what a truncated shell result looked like to the model.
     */
    @Test
    void truncationKeepsJsonValidAndPreservesShortQuotedFields() throws Exception {
        ToolRegistry registry = registry(400);
        registry.register(new ToolHandler() {
            @Override
            public String name() {
                return "big_json";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
                out.put("command", "echo \"hello world\"");
                out.put("output", "start " + "x".repeat(5000));
                return out.toString();
            }
        });

        ToolExecution execution = registry.executeDetailed("big_json", "{}");
        com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(execution.getResult());

        assertThat(execution.getResult()).hasSizeLessThanOrEqualTo(400);
        assertThat(parsed.path("truncated").asBoolean()).isTrue();
        assertThat(parsed.path("command").asText()).isEqualTo("echo \"hello world\"");
        assertThat(parsed.path("output").asText()).startsWith("start ");
    }

    /** grep-shaped results are long because of many entries, not one giant string. */
    @Test
    void truncationDropsTrailingArrayEntriesInsteadOfBreakingTheDocument() throws Exception {
        ToolRegistry registry = registry(400);
        registry.register(new ToolHandler() {
            @Override
            public String name() {
                return "many";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
                com.fasterxml.jackson.databind.node.ArrayNode matches = out.putArray("matches");
                for (int i = 0; i < 200; i++) {
                    matches.addObject().put("file", "a/b/c.txt").put("line", i);
                }
                return out.toString();
            }
        });

        ToolExecution execution = registry.executeDetailed("many", "{}");
        com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(execution.getResult());

        assertThat(execution.getResult()).hasSizeLessThanOrEqualTo(400);
        assertThat(parsed.path("truncated").asBoolean()).isTrue();
        assertThat(parsed.path("matches").isArray()).isTrue();
        assertThat(parsed.path("matches")).isNotEmpty();
        assertThat(parsed.path("matches").size()).isLessThan(200);
    }

    @Test
    void explicitFailureMarkersCountAsFailures() {
        ToolRegistry registry = registry(4000);
        ToolHandler alwaysFails = new ToolHandler() {
            @Override
            public String name() {
                return "not_ok";
            }

            @Override
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return "{\"ok\":false,\"reason\":\"nope\"}";
            }
        };
        registry.register(alwaysFails);

        assertThat(registry.executeDetailed("not_ok", "{}").isSuccess()).isFalse();
    }

    @Test
    void definitionsCarryNameDescriptionAndSchema() {
        assertThat(registry(4000).definitions())
                .allSatisfy(definition -> {
                    assertThat(definition.functionName()).isNotBlank();
                    assertThat(definition.getFunction().getDescription()).isNotBlank();
                    assertThat(definition.getFunction().getParameters().path("type").asText())
                            .isEqualTo("object");
                });
    }
}
