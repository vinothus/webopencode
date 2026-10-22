package com.pawwork.opencode.demo.api;

import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.demo.dto.ChatApiReply;
import com.pawwork.opencode.demo.dto.ChatApiRequest;
import com.pawwork.opencode.demo.dto.ProviderInfo;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatResult;
import com.pawwork.opencode.service.ChatService;
import com.pawwork.opencode.tools.ToolApproval;
import com.pawwork.opencode.tools.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The library's own REST surface: conversation sessions, the server-side model⇄tool loop, and
 * server-sent-event streaming.
 *
 * <p>Tool consent lives here because it is inherently interactive: the served page chooses
 * {@code auto} / {@code ask} / {@code off}, and in {@code ask} mode the stream pauses on a
 * {@code tool_approval} event until the browser answers through {@code /api/tool-approval}.</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Chat",
        description = "Library-level chat: conversation sessions, server-side tool execution and SSE streaming.")
public class ChatController {

    private final ChatService chatService;
    private final OpenCodeProperties properties;
    private final ToolApproval baseApproval;
    private final ToolRegistry registry;
    private final ExecutorService executor;

    /** Pending approvals, keyed by the id the UI is shown. Filled by the SSE thread, released by the REST thread. */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    public ChatController(ChatService chatService, OpenCodeProperties properties,
                          ToolApproval baseApproval, ToolRegistry registry) {
        this.chatService = chatService;
        this.properties = properties;
        this.baseApproval = baseApproval == null ? ToolApproval.alwaysAllow() : baseApproval;
        this.registry = registry;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chat-sse");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ chat

    @PostMapping("/chat")
    @Operation(summary = "Send one message and wait for the final answer",
            description = """
                    Runs a full turn server-side: the message is appended to the session's OpenAI-wire \
                    history, the model is called, and any tool calls it makes are executed here and \
                    fed back — up to `opencode.max-tool-rounds` rounds — until a final answer comes \
                    back. The session's history (including tool messages) is kept for the next turn.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The final answer",
                    content = @Content(schema = @Schema(implementation = ChatApiReply.class))),
            @ApiResponse(responseCode = "502", description = "The gateway refused or failed the call",
                    content = @Content(schema = @Schema(implementation = Map.class)))})
    public ChatApiReply chat(@RequestBody ChatApiRequest request) {
        request.validate();

        String sessionId = normaliseSessionId(request.getSessionId());
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            chatService.seed(sessionId, ChatMessage.system(request.getSystemPrompt().trim()));
        }

        ChatResult result = chatService.chat(sessionId, request.getProvider(), request.getModel(),
                request.getMessage());
        return toReply(result, request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send one message and stream the turn as server-sent events",
            description = """
                    Same turn as POST /api/chat, but progress is pushed as it happens. Events:

                    * `start` — `{sessionId, provider, model}` (use the sessionId for later turns)
                    * `round` — `{round}` before each model call
                    * `token` — `{text}` visible answer fragments, in order
                    * `reasoning` — `{text}` reasoning-channel fragments
                    * `tool_start` / `tool_result` — `{name, arguments}` / `{name, result, success}`
                    * `done` — the full ChatApiReply
                    * `error` — `{message}`

                    Note: tokens of a round that turns out to be a tool call are provisional —
                    `tool_start` is the signal that the answer is not out yet.""")
    @ApiResponse(responseCode = "200", description = "text/event-stream of the events listed above",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    public SseEmitter stream(@RequestBody ChatApiRequest request) {
        request.validate();

        String sessionId = normaliseSessionId(request.getSessionId());
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            chatService.seed(sessionId, ChatMessage.system(request.getSystemPrompt().trim()));
        }

        ProviderProfile profile = withOverrides(properties.resolve(request.getProvider()), request);
        String model = request.getModel() == null || request.getModel().isBlank()
                ? profile.defaultModel() : request.getModel();

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(error -> open.set(false));

        executor.execute(() -> {
            try {
                send(emitter, open, "start", Map.of(
                        "sessionId", sessionId,
                        "provider", profile.name(),
                        "model", String.valueOf(model)));

                chatService.chatStream(sessionId, profile.name(), model, request.getMessage(),
                        new ChatService.StreamListener() {
                            @Override
                            public void onToken(String text) {
                                send(emitter, open, "token", Map.of("text", text));
                            }

                            @Override
                            public void onReasoning(String text) {
                                send(emitter, open, "reasoning", Map.of("text", text));
                            }

                            @Override
                            public void onRound(int round) {
                                send(emitter, open, "round", Map.of("round", round));
                            }

                            @Override
                            public void onToolStart(String name, String arguments) {
                                send(emitter, open, "tool_start",
                                        Map.of("name", name, "arguments", String.valueOf(arguments)));
                            }

                            @Override
                            public void onToolResult(String name, String result, boolean success) {
                                send(emitter, open, "tool_result",
                                        Map.of("name", name, "result", String.valueOf(result), "success", success));
                            }

                            @Override
                            public ToolApproval.Decision onToolApproval(String name, String arguments) {
                                String approvalId = "apr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                                PendingApproval pending = new PendingApproval();
                                pendingApprovals.put(approvalId, pending);
                                send(emitter, open, "tool_approval", Map.of(
                                        "id", approvalId,
                                        "name", name,
                                        "arguments", String.valueOf(arguments)));

                                boolean answered = pending.await(properties.getToolApprovalTimeoutSeconds());
                                pendingApprovals.remove(approvalId);
                                if (!answered) {
                                    send(emitter, open, "tool_approval_timeout", Map.of("id", approvalId));
                                    return ToolApproval.Decision.DENY;
                                }
                                return pending.decision;
                            }

                            @Override
                            public void onDone(ChatResult result) {
                                send(emitter, open, "done", toReply(result, request));
                            }
                        }, approvalFor(request));
            } catch (Exception e) {
                send(emitter, open, "error", Map.of("message",
                        e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // the client may already be gone
                }
            }
        });
        return emitter;
    }

    // ------------------------------------------------------------------ tool approval

    /**
     * Applies a caller-supplied endpoint/key to one request.
     *
     * <p>The UI lets people add their own OpenAI-compatible models. Those are not in
     * {@code opencode.providers.*}, so the request carries the base URL and the key the user
     * entered; they apply to this call only and are never stored server-side. A custom endpoint
     * speaks plain OpenAI, not the OpenCode CLI dialect, so those flags are turned off.</p>
     */
    static ProviderProfile withOverrides(ProviderProfile base, ChatApiRequest request) {
        String baseUrl = request.getBaseUrl() == null ? "" : request.getBaseUrl().trim();
        String apiKey = request.getApiKey() == null ? "" : request.getApiKey().trim();
        if (baseUrl.isEmpty() && apiKey.isEmpty()) {
            return base;
        }
        boolean customEndpoint = !baseUrl.isEmpty();
        return new ProviderProfile(
                base.name(),
                customEndpoint ? baseUrl : base.baseUrl(),
                apiKey.isEmpty() ? base.apiKey() : apiKey,
                base.defaultModel(),
                customEndpoint ? false : base.opencodeGateway(),
                base.toolsEnabled(),
                base.maxTokens(),
                base.userAgent(),
                customEndpoint ? false : base.forceStream());
    }

    /**
     * Builds the consent policy for one request from its {@code toolMode}. Unknown or missing modes
     * fall back to {@code ask}, the safe default: a client that does not say gets prompted rather
     * than silently letting the model run shell commands.
     */
    private ToolApproval approvalFor(ChatApiRequest request) {
        String mode = request.getToolMode();
        final String resolved = ToolApproval.isKnownMode(mode) ? mode : "ask";
        return new ToolApproval() {
            @Override
            public String mode() {
                return resolved;
            }

            @Override
            public Decision decide(String toolName, String arguments, boolean force) {
                return "off".equals(resolved) ? Decision.DENY : Decision.ALLOW;
            }

            @Override
            public boolean requiresConfirmation(String toolName, com.fasterxml.jackson.databind.JsonNode arguments) {
                return baseApproval.requiresConfirmation(toolName, arguments);
            }
        };
    }

    @PostMapping("/tool-approval")
    @Operation(summary = "Answer a pending tool-approval request",
            description = "Releases a turn that is paused on a `tool_approval` event. "
                    + "`allow:false` makes the loop hand the model a refusal instead of running the tool.")
    public ResponseEntity<Map<String, Object>> toolApproval(@RequestBody ToolApprovalRequest request) {
        if (request.getId() == null || request.getId().isBlank()) {
            throw new IllegalArgumentException("'id' is required");
        }
        PendingApproval pending = pendingApprovals.get(request.getId());
        if (pending == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "id", request.getId(), "released", false, "reason", "unknown or already answered"));
        }
        pending.release(request.isAllow());
        return ResponseEntity.ok(Map.of(
                "id", request.getId(), "released", true, "allow", request.isAllow()));
    }

    /** Request body of {@code POST /api/tool-approval}. */
    public static class ToolApprovalRequest {
        private String id;
        private boolean allow;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public boolean isAllow() {
            return allow;
        }

        public void setAllow(boolean allow) {
            this.allow = allow;
        }
    }

    /** A tool call waiting for the browser's answer. */
    private static final class PendingApproval {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile ToolApproval.Decision decision = ToolApproval.Decision.DENY;

        void release(boolean allow) {
            this.decision = allow ? ToolApproval.Decision.ALLOW : ToolApproval.Decision.DENY;
            latch.countDown();
        }

        boolean await(int seconds) {
            try {
                return latch.await(Math.max(1, seconds), java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ sessions

    @GetMapping("/chat/{sessionId}/history")
    @Operation(summary = "Read a session's full wire history",
            description = "Every message the library keeps for the session — system, user, assistant "
                    + "and the tool messages the loop produced.")
    public List<ChatMessage> history(
            @Parameter(description = "Session id returned by a chat call", example = "sess_9f2c4ab1de77aa10b3c4")
            @PathVariable String sessionId) {
        return chatService.history(sessionId);
    }

    @DeleteMapping("/chat/{sessionId}")
    @Operation(summary = "Forget a session", description = "Drops the server-side history for one session.")
    public ResponseEntity<Map<String, Object>> clear(
            @PathVariable String sessionId) {
        boolean existed = !chatService.history(sessionId).isEmpty();
        chatService.clear(sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "cleared", existed));
    }

    @GetMapping("/sessions")
    @Operation(summary = "Session count", description = "How many conversations are held in memory.")
    public Map<String, Object> sessions() {
        return Map.of("sessions", chatService.conversationCount());
    }

    // ------------------------------------------------------------------ providers

    @GetMapping("/providers")
    @Operation(summary = "Configured providers",
            description = "Every provider from `opencode.*` / `opencode.providers.*` the library can call.")
    public List<ProviderInfo> providers() {
        List<ProviderInfo> out = new ArrayList<>();
        properties.profiles().forEach((name, profile) -> {
            ProviderInfo info = new ProviderInfo();
            info.setName(name);
            info.setBaseUrl(profile.endpoint());
            info.setDefaultModel(profile.defaultModel());
            info.setOpencodeGateway(profile.usesGatewayHeaders());
            info.setTools(profile.toolsEnabled());
            info.setSecured(profile.apiKey() != null && !profile.apiKey().isBlank());
            info.setModels(profile.defaultModel() == null || profile.defaultModel().isBlank()
                    ? List.of() : List.of(profile.defaultModel()));
            out.add(info);
        });
        return out;
    }

    @GetMapping("/tools")
    @Operation(summary = "Tools the model may call",
            description = "Locally registered tools, plus the official OpenCode CLI tool names that are "
                    + "advertised to the gateway.")
    public Map<String, Object> tools() {
        // Two different questions, deliberately reported apart:
        //   "executable"  — ToolHandlers actually registered, so a call will run;
        //   "advertised"  — every definition sent to the gateway (official schemas included), which
        //                   is a superset: a name here may still be unimplemented.
        List<String> executable = registry.names();
        List<String> advertised = chatService.tools().stream()
                .map(definition -> definition.functionName()).distinct().toList();
        List<String> advertisedOnly = advertised.stream()
                .filter(name -> !executable.contains(name)).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executable", executable);
        out.put("advertised", advertised);
        out.put("advertisedOnly", advertisedOnly);
        out.put("executableCount", executable.size());
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private ChatApiReply toReply(ChatResult result, ChatApiRequest request) {
        ChatApiReply reply = new ChatApiReply();
        reply.setSessionId(result.getSessionId());
        reply.setProvider(result.getProvider());
        reply.setModel(result.getModel());
        reply.setReply(result.getReply());
        reply.setRounds(result.getRounds());
        reply.setToolExecutions(result.getToolExecutions());
        reply.setUsage(result.getUsage());
        reply.setWarning(result.getWarning());
        reply.setHistorySize(chatService.history(result.getSessionId()).size());
        return reply;
    }

    private String normaliseSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? chatService.newSessionId() : sessionId.trim();
    }

    private void send(SseEmitter emitter, AtomicBoolean open, String event, Object payload) {
        if (!open.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client went away mid-turn — stop emitting, let the turn finish quietly.
            open.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
