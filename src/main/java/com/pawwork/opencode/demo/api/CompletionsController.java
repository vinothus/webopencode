package com.pawwork.opencode.demo.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.client.OpenAiClient;
import com.pawwork.opencode.client.OpenCodeException;
import com.pawwork.opencode.client.SseAccumulator;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatMessage;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.ToolCall;
import com.pawwork.opencode.tools.DeclaredTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI-spec pass-through: the surface Cursor, LangChain, Claude Code (OpenAI mode) and every
 * OpenAI SDK expect.
 *
 * <p>Endpoints: {@code POST /v1/chat/completions} (JSON or {@code stream:true} SSE),
 * {@code GET /v1/models}, {@code GET /v1/models/{model}}.</p>
 *
 * <h2>Why the strict tool handling exists</h2>
 * The OpenCode free tier rejects any gateway body that does not carry the 11 official CLI tool
 * names, so the starter injects them into every upstream request. A client that only asked for
 * {@code calculate} must still only ever see {@code calculate} in {@code tool_calls} — so:</p>
 * <ul>
 *   <li>a caller with no tools gets {@code tool_choice:"none"} upstream (plain content only);</li>
 *   <li>a caller with tools gets a strict pass: any call to a tool it never declared is answered
 *       in-band and the model is asked again, so only declared tools come back to the client.</li>
 * </ul>
 */
@RestController
@Tag(name = "OpenAI-compatible",
        description = "Drop-in OpenAI chat-completions surface backed by the OpenCode gateway.")
public class CompletionsController {

    /** How often the model is asked again after requesting a tool the client never declared. */
    private static final int MAX_CORRECTION_ROUNDS = 3;

    private final OpenAiClient client;
    private final OpenCodeProperties properties;
    private final ObjectMapper mapper;

    public CompletionsController(OpenAiClient client, OpenCodeProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
    }

    // ----------------------------------------------------------------- POST /v1/chat/completions

    @PostMapping(value = "/v1/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @Operation(summary = "Create a chat completion",
            description = """
                    OpenAI-compatible chat completion. With `stream=false` (or no `stream` field) the \
                    response is a single `chat.completion` JSON object. With `stream=true` the response \
                    is `text/event-stream`: `data: {…chunk…}` frames followed by `data: [DONE]`.

                    `tools`, `tool_choice`, `temperature`, `top_p`, `max_tokens`, `stop`, `seed`, \
                    `response_format` and any other OpenAI field are forwarded to the provider.

                    Provider selection: send `X-Provider: <name>` to pick a provider from \
                    `opencode.providers.*`; otherwise the default provider is used. The gateway's \
                    `x-opencode-*` identity headers and the official CLI tool schemas are added \
                    automatically.""")
    @ApiResponse(responseCode = "200", description = "A `chat.completion` object, or — for "
            + "`stream=true` — `chat.completion.chunk` frames terminated by `data: [DONE]`",
            content = {
                    @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ChatResponse.class)),
                    @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(implementation = ChatChunk.class))})
    public void completions(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-Provider", required = false)
            @Parameter(description = "Provider name from `opencode.providers.*`.", example = "zen")
            String provider,
            @RequestHeader(value = "Authorization", required = false)
            @Parameter(description = "Accepted and ignored: the upstream credential comes from "
                    + "`opencode.providers.*.api-key`. HTTP-bearer security is declared for OpenAPI "
                    + "compatibility.")
            String authorization,
            HttpServletResponse response) throws IOException {

        ProviderProfile profile = properties.resolve(provider);
        Set<String> declared = DeclaredTools.declared(request);

        if (declared.isEmpty() && request.getToolChoice() == null) {
            // The free tier still gets the official tool names (it refuses bodies without them),
            // but the model is told not to call anything — this caller asked for plain chat.
            request.setToolChoice(mapper.getNodeFactory().textNode("none"));
        }

        if (Boolean.TRUE.equals(request.getStream())) {
            streamSse(profile, request, declared, response);
            return;
        }
        respond(profile, request, declared, response);
    }

    // ----------------------------------------------------------------- GET /v1/models

    @GetMapping("/v1/models")
    @Operation(summary = "List models",
            description = "Proxies the provider's `GET /models` catalogue, in the OpenAI `model` list shape.")
    @ApiResponse(responseCode = "200", description = "{\"object\":\"list\",\"data\":[…]}")
    public JsonNode models(
            @RequestHeader(value = "X-Provider", required = false)
            @Parameter(description = "Provider name from `opencode.providers.*`.", example = "zen")
            String provider,
            @RequestParam(value = "provider", required = false) String providerParam) {
        return client.listModels(properties.resolve(provider != null ? provider : providerParam));
    }

    @GetMapping("/v1/models/{model}")
    @Operation(summary = "Retrieve a model",
            description = "OpenAI's `models.retrieve`: returns the model object, or a 404 "
                    + "`invalid_request_error` when the provider does not list it.")
    @ApiResponse(responseCode = "200", description = "{\"id\":\"big-pickle\",\"object\":\"model\"}")
    @ApiResponse(responseCode = "404", description = "Unknown model")
    public JsonNode model(
            @PathVariable("model") String model,
            @RequestHeader(value = "X-Provider", required = false) String provider,
            @RequestParam(value = "provider", required = false) String providerParam) {
        JsonNode catalogue = client.listModels(properties.resolve(provider != null ? provider : providerParam));
        for (JsonNode entry : catalogue.path("data")) {
            if (model.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        throw new OpenCodeException(404, "The model '" + model + "' does not exist", null);
    }

    @GetMapping("/v1/health")
    @Operation(summary = "Library health",
            description = "Confirms the library is loaded and reports which provider would be used by default.")
    public Map<String, Object> health() {
        ProviderProfile profile = properties.resolve(null);
        return Map.of(
                "status", "UP",
                "library", "opencode-spring-boot",
                "defaultProvider", profile.name(),
                "defaultModel", String.valueOf(profile.defaultModel()),
                "openCodeGateway", profile.usesGatewayHeaders());
    }

    // ----------------------------------------------------------------- non-streaming path

    /** Folds the gateway's forced stream into one object and returns only declared tool calls. */
    private void respond(ProviderProfile profile, ChatRequest request, Set<String> declared,
                         HttpServletResponse response) throws IOException {
        ChatResponse result = correct(profile, request, declared, client.complete(profile, request));
        normaliseId(result);

        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter writer = response.getWriter();
        writer.write(mapper.writeValueAsString(result));
        writer.flush();
    }

    /**
     * Keeps re-asking while the model reaches for a tool the client never declared, answering each
     * rejected call in-band so the conversation stays on the wire. Stops as soon as every call in
     * the answer belongs to the client (usually the first try).
     */
    private ChatResponse correct(ProviderProfile profile, ChatRequest request, Set<String> declared,
                                 ChatResponse first) {
        ChatResponse result = first;
        for (int round = 0; round < MAX_CORRECTION_ROUNDS
                && DeclaredTools.hasUndeclared(result, declared); round++) {
            ChatMessage asked = result.firstMessage();
            request.addMessage(asked);
            int position = 0;
            for (ToolCall call : DeclaredTools.toolCalls(result)) {
                boolean declaredCall = declared.contains(call.functionName());
                // Every tool call must be answered by a tool message; only malformed upstream
                // output (an id that never arrived) needs a synthesized one.
                String callId = call.getId() == null || call.getId().isBlank()
                        ? "call_unknown_" + position : call.getId();
                request.addMessage(ChatMessage.tool(callId,
                        feedback(call.functionName(), declared, declaredCall)));
                position++;
            }
            result = client.complete(profile, request);
        }
        return result;
    }

    private String feedback(String name, Set<String> declared, boolean declaredCall) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (declaredCall) {
            body.put("error", "tool '" + name + "' is declared by the calling client but was not "
                    + "executed in this round; the round is being re-issued. Call it again now if "
                    + "you still need it, otherwise answer directly.");
        } else {
            body.put("error", "tool '" + name + "' is not available on this endpoint. Only tools "
                    + "the calling client declared may be called: "
                    + (declared.isEmpty() ? "it declared none, so answer directly."
                    : String.join(", ", declared)));
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"tool '" + name + "' is not available on this endpoint\"}";
        }
    }

    // ----------------------------------------------------------------- streaming path

    /**
     * Relays the upstream SSE stream. Role, reasoning and content frames go out the moment they
     * arrive — only frames carrying {@code tool_calls} are held, because those names have to be
     * checked against what the client declared. A response that asked for a tool the client never
     * declared (and wrote no text yet) is corrected and re-emitted before its tool frames are
     * seen; anything already relayed is never rewritten.
     */
    private void streamSse(ProviderProfile profile, ChatRequest request, Set<String> declared,
                           HttpServletResponse response) throws IOException {
        SseAccumulator folded = new SseAccumulator();
        List<ChatChunk> held = new ArrayList<>();
        boolean[] contentRelayed = {false};
        boolean[] written = {false};
        PrintWriter[] sink = new PrintWriter[1];

        try {
            client.stream(profile, request, chunk -> {
                try {
                    if (chunk.getId() != null && !chunk.getId().isBlank()) {
                        chunk.setId(openAiId(chunk.getId()));
                    }
                    folded.accept(chunk);
                    if (DeclaredTools.carriesToolCalls(chunk)) {
                        held.add(chunk);
                        return;
                    }
                    if (DeclaredTools.carriesContent(chunk)) {
                        contentRelayed[0] = true;
                    }
                    writeFrame(sink, response, written, chunk);
                } catch (IOException e) {
                    // The reader hung up mid-stream — abort the relay.
                    throw new UncheckedIOException(e);
                }
            });

            ChatResponse assembled = folded.toResponse();
            if (DeclaredTools.hasUndeclared(assembled, declared) && !contentRelayed[0]) {
                // Nothing of the offending answer reached the client yet: correct it instead.
                emitAsChunks(sink, response, written,
                        correct(profile, request, declared, assembled));
            } else {
                for (ChatChunk frame : held) {
                    writeFrame(sink, response, written, frame);
                }
            }
            writeRaw(sink, response, written, "data: [DONE]\n\n");
        } catch (UncheckedIOException e) {
            // Client disconnected; nothing left to do.
        } catch (OpenCodeException e) {
            handleStreamError(sink, response, written, e);
        }
    }

    /**
     * The stream broke. If nothing has gone out yet the HTTP status is still changeable, so the
     * normal error advice renders a real OpenAI error object with the right status; once frames
     * are on the wire we can only append an error frame and terminate the stream.
     */
    private void handleStreamError(PrintWriter[] sink, HttpServletResponse response,
                                   boolean[] written, OpenCodeException e) throws IOException {
        if (!written[0] && sink[0] == null) {
            response.reset();
            throw e;
        }
        Map<String, Object> error = Map.of("error", Map.of(
                "message", e.getMessage() == null ? "gateway error" : e.getMessage(),
                "type", "api_error"));
        writeFrame(sink, response, written, error);
        writeRaw(sink, response, written, "data: [DONE]\n\n");
    }

    /** Replays a folded response as OpenAI chunks (used when a stream had to be corrected). */
    private void emitAsChunks(PrintWriter[] sink, HttpServletResponse response, boolean[] written,
                              ChatResponse result) throws IOException {
        normaliseId(result);
        String id = result.getId();
        long created = result.getCreated() != null ? result.getCreated()
                : System.currentTimeMillis() / 1000L;
        String model = result.getModel() == null ? "unknown" : result.getModel();
        ChatMessage message = result.firstMessage();

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        if (message.getReasoningContent() != null && !message.getReasoningContent().isBlank()) {
            delta.put("reasoning_content", message.getReasoningContent());
        }
        String content = message.contentAsText();
        if (!content.isBlank()) {
            delta.put("content", content);
        }
        writeFrame(sink, response, written,
                chunkFrame(id, created, model, delta, null));

        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            int index = 0;
            for (ToolCall call : message.getToolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.functionName());
                function.put("arguments", call.rawArguments());
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("index", index++);
                if (call.getId() != null) {
                    wire.put("id", call.getId());
                }
                wire.put("type", call.getType() == null ? "function" : call.getType());
                wire.put("function", function);
                calls.add(wire);
            }
            writeFrame(sink, response, written,
                    chunkFrame(id, created, model, Map.of("tool_calls", calls), null));
        }

        String finish = result.getChoices() == null || result.getChoices().isEmpty()
                ? "stop" : result.getChoices().get(0).getFinishReason();
        writeFrame(sink, response, written,
                chunkFrame(id, created, model, Map.of(), finish == null ? "stop" : finish));
    }

    private Map<String, Object> chunkFrame(String id, long created, String model,
                                           Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", id);
        frame.put("object", "chat.completion.chunk");
        frame.put("created", created);
        frame.put("model", model);
        frame.put("choices", List.of(choice));
        return frame;
    }

    // ----------------------------------------------------------------- wire helpers

    /** OpenAI completions are identified as {@code chatcmpl-…}; the gateway uses other prefixes. */
    private void normaliseId(ChatResponse result) {
        result.setId(openAiId(result.getId()));
        if (result.getObject() == null || result.getObject().isBlank()) {
            result.setObject("chat.completion");
        }
    }

    /** Applies the {@code chatcmpl-} prefix once, so streamed and non-streamed ids agree. */
    private String openAiId(String id) {
        if (id == null || id.isBlank()) {
            return "chatcmpl-" + Long.toHexString(System.currentTimeMillis());
        }
        return id.startsWith("chatcmpl-") ? id : "chatcmpl-" + id;
    }

    /**
     * Writes one {@code data:} frame, taking care to fix the response status and headers on the
     * very first byte so that a failure before it can still become a real HTTP error.
     */
    private void writeFrame(PrintWriter[] sink, HttpServletResponse response, boolean[] written,
                            Object payload) throws IOException {
        String frame;
        try {
            frame = "data: " + mapper.writeValueAsString(payload) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new IOException("could not serialise an SSE frame", e);
        }
        writeRaw(sink, response, written, frame);
    }

    private void writeRaw(PrintWriter[] sink, HttpServletResponse response, boolean[] written,
                          String frame) throws IOException {
        if (sink[0] == null) {
            response.setStatus(200);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            sink[0] = response.getWriter();
        }
        sink[0].print(frame);
        sink[0].flush();
        written[0] = true;
    }
}
