package com.pawwork.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.OpenCodeIdentity;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.prompt.AgentProfile;
import com.pawwork.opencode.prompt.AgentPrompt;
import com.pawwork.opencode.prompt.ServerEnvironment;
import com.pawwork.opencode.prompt.ToolNotes;
import com.pawwork.opencode.service.ChatService;
import com.pawwork.opencode.tools.FileTools;
import com.pawwork.opencode.tools.OpenCodeToolSchemas;
import com.pawwork.opencode.tools.ShellLauncher;
import com.pawwork.opencode.tools.ShellTool;
import com.pawwork.opencode.tools.ToolApproval;
import com.pawwork.opencode.tools.ToolRegistry;
import com.pawwork.opencode.tools.Workspace;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually goes out on the wire.
 *
 * <p>Two things were quietly missing, and both of them are invisible in the source: the request
 * carried the user's message and nothing else — no system prompt, no environment, no
 * instructions — and the tool descriptions were replaced by one-liners. Together they explain why
 * this agent was so much worse than the OpenCode CLI it is a port of: the CLI's own descriptions
 * carry most of its instructions, and they were being thrown away.</p>
 *
 * <p>This test captures a real request against a stub gateway and checks both are there.</p>
 */
class AgentWireTest {

    private final ObjectMapper mapper = OpenAiClient.defaultMapper();

    /** The description mass the CLI sends: 16 161 characters for its eleven tools. */
    private static final int MINIMUM_DESCRIPTION_MASS = 12_000;

    @Test
    void theRequestCarriesTheSystemPromptAndTheOfficialToolDescriptions(@TempDir Path workspaceRoot)
            throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer gateway = stubGateway(bodies);
        try {
            OpenCodeProperties properties = properties(gateway, workspaceRoot);
            OpenCodeToolSchemas schemas = OpenCodeToolSchemas.load(mapper);
            ChatService chat = chatService(properties, schemas, workspaceRoot);

            chat.chat("sess_wire", "stub", null, "list the files in this project");

            assertThat(bodies).hasSize(1);
            JsonNode wire = mapper.readTree(bodies.get(0));

            // 1. The system prompt is there, and it is the CLI's shape.
            JsonNode messages = wire.path("messages");
            assertThat(messages).hasSize(2);
            JsonNode system = messages.get(0);
            assertThat(system.path("role").asText()).isEqualTo("system");
            String prompt = system.path("content").asText();
            assertThat(prompt).contains("You are an AI agent running in OpenCode");
            assertThat(prompt).contains("Today's date:");
            assertThat(prompt).contains("<env>");
            assertThat(prompt).contains("Workspace root folder: " + workspaceRoot);
            assertThat(prompt).contains("Is directory a git repo:");
            assertThat(prompt).contains("Platform:");
            assertThat(messages.get(1).path("role").asText()).isEqualTo("user");

            // 2. The descriptions are the CLI's own, not one-liners.
            JsonNode tools = wire.path("tools");
            assertThat(tools).isNotEmpty();
            int mass = 0;
            for (JsonNode tool : tools) {
                mass += tool.path("function").path("description").asText().length();
            }
            assertThat(mass)
                    .as("the CLI's tool instructions must survive the merge")
                    .isGreaterThanOrEqualTo(MINIMUM_DESCRIPTION_MASS);

            JsonNode read = tool(tools, "read");
            assertThat(read.path("function").path("description").asText())
                    .contains("Contents are returned with each line prefixed by its line number");
            // The CLI's argument names win, so the model uses the names the descriptions talk about.
            assertThat(read.path("function").path("parameters").path("properties").has("filePath")).isTrue();
            assertThat(read.path("function").path("parameters").path("properties").has("path")).isFalse();

            JsonNode edit = tool(tools, "edit");
            assertThat(edit.path("function").path("parameters").path("properties").has("oldString")).isTrue();
            assertThat(edit.path("function").path("parameters").path("properties").has("newString")).isTrue();

            JsonNode bash = tool(tools, "bash");
            assertThat(bash.path("function").path("description").asText())
                    .contains("Be aware: OS:")
                    .doesNotContain("/tmp/opencode");

            // A tool this server does not implement is advertised — the gateway's gate wants the
            // names — but it says so, so the model does not spend a turn on it.
            assertThat(tool(tools, "websearch").path("function").path("description").asText())
                    .contains("Not implemented by this server");
        } finally {
            gateway.stop(0);
        }
    }

    @Test
    void aCallerSuppliedSystemPromptIsKeptBesideTheDefaultOne(@TempDir Path workspaceRoot)
            throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer gateway = stubGateway(bodies);
        try {
            OpenCodeProperties properties = properties(gateway, workspaceRoot);
            OpenCodeToolSchemas schemas = OpenCodeToolSchemas.load(mapper);
            ChatService chat = chatService(properties, schemas, workspaceRoot);
            chat.seed("sess_seeded", com.pawwork.opencode.model.ChatMessage.system("Speak like a pirate."));

            chat.chat("sess_seeded", "stub", null, "hello");

            JsonNode messages = mapper.readTree(bodies.get(0)).path("messages");
            assertThat(messages.get(0).path("content").asText()).contains("You are an AI agent");
            assertThat(messages.get(1).path("content").asText()).isEqualTo("Speak like a pirate.");
            assertThat(messages.get(2).path("role").asText()).isEqualTo("user");
        } finally {
            gateway.stop(0);
        }
    }

    // ------------------------------------------------------------------ helpers

    private OpenCodeProperties properties(HttpServer gateway, Path workspaceRoot) {
        OpenCodeProperties properties = new OpenCodeProperties();
        properties.getTools().setWorkspace(workspaceRoot.toString());

        OpenCodeProperties.Provider stub = new OpenCodeProperties.Provider();
        stub.setBaseUrl("http://127.0.0.1:" + gateway.getAddress().getPort() + "/v1");
        stub.setApiKey("");
        stub.setDefaultModel("stub-model");
        stub.setOpenCodeGateway(false);
        stub.setForceStream(false);
        stub.setTools(true);
        properties.getProviders().put("stub", stub);
        return properties;
    }

    private ChatService chatService(OpenCodeProperties properties, OpenCodeToolSchemas schemas,
                                    Path workspaceRoot) {
        Workspace workspace = new Workspace(workspaceRoot.toString());
        FileTools files = new FileTools(workspace, mapper);
        ShellTool shell = new ShellTool(workspace, mapper, ShellLauncher.discover());
        ToolRegistry registry = new ToolRegistry(mapper, List.of(
                files.read(), files.glob(), files.grep(), files.write(), files.edit(), shell.handler()),
                properties.getToolResultLimit());

        ServerEnvironment environment = new ServerEnvironment(properties);
        AgentProfile profile = new AgentProfile(environment, new AgentPrompt(properties),
                new ToolNotes(environment));

        OpenAiClient client = new OpenAiClient(properties, new OpenCodeIdentity("wire-test"),
                mapper, schemas.tools());
        return new ChatService(client, properties, registry, mapper, schemas.tools(),
                ToolApproval.alwaysAllow(), 0, profile);
    }

    private static JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("function").path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("no tool named '" + name + "' in " + tools);
    }

    /** A gateway that answers every call with one plain message and remembers the request. */
    private static HttpServer stubGateway(List<String> bodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] answer = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"done\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return server;
    }
}
