package com.pawwork.opencode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.SseAccumulator;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.ChatResult;
import com.pawwork.opencode.model.Delta;
import com.pawwork.opencode.model.ToolCall;
import com.pawwork.opencode.model.ToolDefinition;
import com.pawwork.opencode.model.ToolExecution;
import com.pawwork.opencode.model.Usage;
import com.pawwork.opencode.prompt.AgentProfile;
import com.pawwork.opencode.tools.OpenCodeToolSchemas;
import com.pawwork.opencode.tools.ToolApproval;
import com.pawwork.opencode.tools.ToolContext;
import com.pawwork.opencode.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Library-level chat: keeps the OpenAI wire history per session and runs the model ⇄ tool loop
 * until the model produces a final answer.
 *
 * <p>This is the port of {@code ChatFragment.callAi()} from the Android reference client:
 * up to {@code max-tool-rounds} rounds, each round either a tool call (execute locally, append the
 * {@code tool} result, ask again) or the final text. The loop is guarded against a model that keeps
 * retrying the same failing call — after N identical consecutive failures it stops and says so.</p>
 *
 * <p>Two entry points: {@link #chat} returns when the whole loop has finished, {@link #chatStream}
 * also forwards content deltas and tool progress as they happen.</p>
 */
public class ChatService {

    /** Progress callbacks for {@link #chatStream}. All methods have empty defaults. */
    public interface StreamListener {

        /** Visible text of the answer so far, in order. */
        void onToken(String text);

        default void onReasoning(String text) {
        }

        default void onToolStart(String name, String arguments) {
        }

        default void onToolResult(String name, String result, boolean success) {
        }

        /**
         * Called before a tool runs when it needs the user's consent. The returned decision is
         * honoured by the loop; returning {@code null} means the listener cannot decide and the
         * caller's {@link ToolApproval} is consulted instead.
         */
        default ToolApproval.Decision onToolApproval(String name, String arguments) {
            return null;
        }

        /** Called once per model round, before the call goes out. */
        default void onRound(int round) {
        }

        default void onDone(ChatResult result) {
        }
    }

    private final OpenAiClient client;
    private final OpenCodeProperties properties;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final List<ToolDefinition> officialTools;
    private final ToolApproval approval;

    /** The deployment's system prompt and per-tool notes; {@code null} when the host has none. */
    private final AgentProfile profile;

    /** Sub-agent nesting depth for the turn currently running on this call. */
    private final int nestingDepth;

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ChatService(OpenAiClient client,
                       OpenCodeProperties properties,
                       ToolRegistry registry,
                       ObjectMapper mapper,
                       List<ToolDefinition> officialTools) {
        this(client, properties, registry, mapper, officialTools, ToolApproval.alwaysAllow());
    }

    /**
     * @param approval consulted before every tool call; {@link ToolApproval#alwaysAllow()} when the
     *                 host has no UI to ask
     */
    public ChatService(OpenAiClient client,
                       OpenCodeProperties properties,
                       ToolRegistry registry,
                       ObjectMapper mapper,
                       List<ToolDefinition> officialTools,
                       ToolApproval approval) {
        this(client, properties, registry, mapper, officialTools, approval, 0, null);
    }

    /**
     * @param nestingDepth how deep a sub-agent chain this service instance represents (0 at the top)
     */
    public ChatService(OpenAiClient client,
                       OpenCodeProperties properties,
                       ToolRegistry registry,
                       ObjectMapper mapper,
                       List<ToolDefinition> officialTools,
                       ToolApproval approval,
                       int nestingDepth) {
        this(client, properties, registry, mapper, officialTools, approval, nestingDepth, null);
    }

    /**
     * @param nestingDepth how deep a sub-agent chain this service instance represents (0 at the top)
     * @param profile      the deployment's system prompt and tool notes; {@code null} for none
     */
    public ChatService(OpenAiClient client,
                       OpenCodeProperties properties,
                       ToolRegistry registry,
                       ObjectMapper mapper,
                       List<ToolDefinition> officialTools,
                       ToolApproval approval,
                       int nestingDepth,
                       AgentProfile profile) {
        this.client = client;
        this.properties = properties;
        this.registry = registry;
        this.mapper = mapper;
        this.officialTools = officialTools == null ? List.of() : List.copyOf(officialTools);
        this.approval = approval == null ? ToolApproval.alwaysAllow() : approval;
        this.nestingDepth = Math.max(0, nestingDepth);
        this.profile = profile;
    }

    /** A copy of this service configured for a sub-agent one level deeper. */
    public ChatService nested(int depth) {
        return new ChatService(client, properties, registry, mapper, officialTools, approval, depth, profile);
    }

    /** This service's nesting depth (0 for the top-level loop). */
    public int nestingDepth() {
        return nestingDepth;
    }

    // ------------------------------------------------------------------ public API

    /** A fresh session id (the caller stores it and sends it back with the next message). */
    public String newSessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** Sends one user message using the default provider/model and blocks until the loop ends. */
    public ChatResult chat(String sessionId, String userText) {
        return chat(sessionId, null, null, userText);
    }

    /**
     * Sends one user message and waits for the full tool loop to finish.
     *
     * @param sessionId conversation key; blank generates one (reported back on the result)
     * @param provider  logical provider name, or {@code null} for the default
     * @param model     model id, or {@code null} for the provider's default
     */
    public ChatResult chat(String sessionId, String provider, String model, String userText) {
        return runTurn(sessionId, provider, model, userText, null);
    }

    /**
     * Streaming variant: tokens and tool progress are pushed to {@code listener} while the loop
     * runs; {@code listener.onDone} fires with the final result.
     *
     * <p>Text deltas are forwarded as they arrive, so a round that later turns out to be a tool
     * call may have already emitted a few tokens — the {@code onToolStart} callback is the signal
     * that the previous tokens were provisional and the answer is not out yet.</p>
     */
    public ChatResult chatStream(String sessionId, String provider, String model, String userText,
                                 StreamListener listener) {
        if (listener == null) {
            return chat(sessionId, provider, model, userText);
        }
        ChatResult result = runTurn(sessionId, provider, model, userText, listener);
        listener.onDone(result);
        return result;
    }

    /**
     * Streaming turn with an explicit per-request consent policy, so one page can offer
     * {@code auto} / {@code ask} / {@code off} without restarting the service.
     */
    public ChatResult chatStream(String sessionId, String provider, String model, String userText,
                                 StreamListener listener, ToolApproval requestApproval) {
        ToolApproval effective = requestApproval == null ? this.approval : requestApproval;
        if (listener == null) {
            return chat(sessionId, provider, model, userText);
        }
        ChatResult result = runTurn(sessionId, provider, model, userText, listener, effective);
        listener.onDone(result);
        return result;
    }

    /** Immutable snapshot of a session's wire history (including tool messages). */
    public List<ChatMessage> history(String sessionId) {        Conversation conversation = conversations.get(sessionId);
        if (conversation == null) {
            return List.of();
        }
        synchronized (conversation) {
            return new ArrayList<>(conversation.messages);
        }
    }

    /** Drops one session. */
    public void clear(String sessionId) {
        if (sessionId != null) {
            conversations.remove(sessionId);
        }
    }

    /**
     * Seeds a brand-new session with a system prompt. Ignored when the session already has
     * history — rewriting history in place could orphan a {@code tool} message from the assistant
     * call that asked for it.
     *
     * @return {@code true} when the message was applied
     */
    public boolean seed(String sessionId, ChatMessage systemMessage) {
        if (sessionId == null || systemMessage == null) {
            return false;
        }
        Conversation conversation = conversations.computeIfAbsent(sessionId.trim(), key -> new Conversation());
        synchronized (conversation) {
            if (!conversation.messages.isEmpty()) {
                return false;
            }
            conversation.messages.add(systemMessage);
            return true;
        }
    }

    /** Drops every session. */
    public void clearAll() {
        conversations.clear();
    }

    public int conversationCount() {
        return conversations.size();
    }

    // ------------------------------------------------------------------ the loop

    private ChatResult runTurn(String requestedSessionId, String providerName, String requestedModel,
                               String userText, StreamListener listener) {
        return runTurn(requestedSessionId, providerName, requestedModel, userText, listener, approval);
    }

    private ChatResult runTurn(String requestedSessionId, String providerName, String requestedModel,
                               String userText, StreamListener listener, ToolApproval effectiveApproval) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? newSessionId() : requestedSessionId.trim();
        ProviderProfile profile = properties.resolve(providerName);
        String model = requestedModel == null || requestedModel.isBlank()
                ? profile.defaultModel() : requestedModel;

        Conversation conversation = conversations.computeIfAbsent(sessionId, key -> new Conversation());

        ChatResult result = new ChatResult();
        result.setSessionId(sessionId);
        result.setProvider(profile.name());
        result.setModel(model);

        // One conversation at a time: interleaving two turns would corrupt the wire history
        // (a tool result must directly follow the assistant message that asked for it).
        synchronized (conversation) {
            List<ChatMessage> history = conversation.messages;
            history.add(ChatMessage.user(userText));

            Usage total = new Usage();
            String lastSignature = null;
            int repeatedFailures = 0;
            String reply = null;
            String warning = null;

            int maxRounds = Math.max(1, properties.getMaxToolRounds());
            int maxRepeated = Math.max(1, properties.getMaxRepeatedToolFailures());

            round:
            for (int round = 1; round <= maxRounds; round++) {
                result.setRounds(round);
                if (listener != null) {
                    listener.onRound(round);
                }

                ChatResponse response = exchange(profile, buildRequest(model, history, listener != null), listener);
                if (response.getUsage() != null) {
                    total.merge(response.getUsage());
                }

                ChatMessage message = response.firstMessage();
                List<ToolCall> calls = message.getToolCalls();

                if (calls != null && !calls.isEmpty()) {
                    ChatMessage assistantTurn = ChatMessage.assistant(message.contentAsText());
                    assistantTurn.setToolCalls(calls);
                    history.add(assistantTurn);

                    for (int i = 0; i < calls.size(); i++) {
                        ToolCall call = calls.get(i);
                        String name = call.functionName();
                        String arguments = call.rawArguments();
                        String callId = call.getId() == null || call.getId().isBlank()
                                ? "call_" + System.currentTimeMillis() + "_" + i : call.getId();

                        if (listener != null) {
                            listener.onToolStart(name, arguments);
                        }

                        ToolApproval.Decision decision = approvalFor(name, arguments, listener, effectiveApproval);
                        ToolExecution execution;
                        if (decision == ToolApproval.Decision.DENY) {
                            execution = new ToolExecution(name, arguments,
                                    denialJson(name, effectiveApproval.mode()), false, 0L);
                        } else {
                            // Publish the session (and nesting depth) so tools like todowrite and task
                            // can see which conversation they belong to.
                            try (ToolContext.Scope ignored = ToolContext.open(sessionId, nestingDepth)) {
                                execution = registry.executeDetailed(name, arguments);
                            }
                        }
                        String toolResult = execution.getResult();
                        history.add(ChatMessage.tool(callId, toolResult));
                        result.getToolExecutions().add(execution);
                        if (listener != null) {
                            listener.onToolResult(name, toolResult, execution.isSuccess());
                        }

                        // Loop-busting: only an identical call that KEEPS FAILING is a stuck loop —
                        // a model retrying the same call after a fix is progress.
                        String signature = name + "|" + abbreviate(arguments, 120);
                        if (signature.equals(lastSignature)) {
                            repeatedFailures = execution.isSuccess() ? 0 : repeatedFailures + 1;
                        } else {
                            lastSignature = signature;
                            repeatedFailures = execution.isSuccess() ? 0 : 1;
                        }
                        if (repeatedFailures >= maxRepeated) {
                            warning = "Tool loop stopped: '" + name + "' kept failing "
                                    + repeatedFailures + "x in a row — last: "
                                    + abbreviate(toolResult, 120);
                            break round;
                        }
                    }
                    continue;
                }

                String text = message.contentAsText().trim();
                if (text.isEmpty() && message.getReasoningContent() != null) {
                    text = message.getReasoningContent().trim();
                }
                history.add(ChatMessage.assistant(text));
                reply = text;
                break;
            }

            if (reply == null) {
                if (warning == null) {
                    warning = "Stopped after " + maxRounds + " tool rounds without a final answer.";
                }
                reply = "(no final answer) " + warning;
            }
            result.setReply(reply);
            result.setWarning(warning);
            result.setUsage(total.getTotalTokens() == null ? null : total);
            return result;
        }
    }

    /** Runs one model call — blocking, or streaming into the accumulator plus the listener. */
    private ChatResponse exchange(ProviderProfile profile, ChatRequest request, StreamListener listener) {
        if (listener == null) {
            return client.complete(profile, request);
        }
        SseAccumulator accumulator = new SseAccumulator();
        client.stream(profile, request, new OpenAiClient.ChunkListener() {
            @Override
            public void onChunk(ChatChunk chunk) {
                accumulator.accept(chunk);
                if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
                    return;
                }
                Delta delta = chunk.getChoices().get(0).getDelta();
                if (delta == null) {
                    return;
                }
                if (delta.getContent() != null && !delta.getContent().isEmpty()) {
                    listener.onToken(delta.getContent());
                }
                if (delta.getReasoningContent() != null && !delta.getReasoningContent().isEmpty()) {
                    listener.onReasoning(delta.getReasoningContent());
                }
            }
        });
        ChatResponse assembled = accumulator.toResponse();
        if (assembled.getModel() == null) {
            assembled.setModel(profile.defaultModel());
        }
        return assembled;
    }

    private ChatRequest buildRequest(String model, List<ChatMessage> history, boolean streaming) {
        ChatRequest request = new ChatRequest(model, messagesFor(history));
        request.setStream(streaming);
        List<ToolDefinition> tools = tools();
        if (!tools.isEmpty()) {
            request.setTools(tools);
        }
        return request;
    }

    /**
     * The messages of one model call: the default system message first, then the conversation.
     *
     * <p>The system prompt is prepended per request rather than stored in the conversation, so it
     * stays a preamble: it does not grow the history, and a system message the caller seeded is
     * still the conversation's own first word.</p>
     */
    private List<ChatMessage> messagesFor(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        String system = profile == null ? "" : profile.systemPrompt();
        if (system != null && !system.isBlank()) {
            messages.add(ChatMessage.system(system));
        }
        messages.addAll(history);
        return messages;
    }

    /**
     * Tools advertised on every turn: the official CLI schemas, described in the CLI's own words,
     * plus everything registered locally. A tool the CLI knows keeps the CLI's description and
     * argument schema — that text is what makes the model behave — with a note appended saying
     * what differs here.
     */
    public List<ToolDefinition> tools() {
        if (registry.isEmpty() && officialTools.isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> merged = OpenCodeToolSchemas.merge(officialTools, registry.definitions(),
                profile == null ? null : profile::toolDescription);
        merged.removeIf(definition -> {
            String name = definition.functionName();
            return name == null || name.isBlank();
        });
        return merged;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Resolves whether a tool call may run: the streaming listener (which can reach a UI) gets the
     * first say, then the configured {@link ToolApproval}. A tool that always needs confirmation —
     * the shell — cannot be waved through by {@code auto} mode alone; that is the whole point of
     * {@link ToolApproval#requiresConfirmation}.
     */
    private ToolApproval.Decision approvalFor(String name, String arguments, StreamListener listener,
                                              ToolApproval effectiveApproval) {
        boolean force = effectiveApproval.requiresConfirmation(name, parseJson(arguments));
        if (!force) {
            // Ordinary tool, ordinary modes: off ⇒ deny, ask ⇒ consult, auto ⇒ allow.
            if ("off".equals(effectiveApproval.mode())) {
                return ToolApproval.Decision.DENY;
            }
            if (!"ask".equals(effectiveApproval.mode())) {
                return ToolApproval.Decision.ALLOW;
            }
        }
        if (listener != null) {
            ToolApproval.Decision fromUi = listener.onToolApproval(name, arguments);
            if (fromUi != null) {
                return fromUi;
            }
        }
        return effectiveApproval.decide(name, arguments, force);
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String denialJson(String name, String mode) {
        String reason = "off".equals(mode)
                ? "tools are disabled for this conversation"
                : "the user declined this tool call";
        return "{\"error\":\"denied: " + name + " — " + reason + "\"}";
    }

    // ------------------------------------------------------------------ state

    /** Wire history of one session — full OpenAI messages, tool roles included. */
    private static final class Conversation {
        private final List<ChatMessage> messages = new ArrayList<>();
    }

    /** Exposed for tests/diagnostics: the mapper this service uses. */
    ObjectMapper mapper() {
        return mapper;
    }
}
