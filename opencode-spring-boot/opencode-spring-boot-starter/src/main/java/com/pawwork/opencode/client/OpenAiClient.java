package com.pawwork.opencode.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.config.ProviderProfile;
import com.pawwork.opencode.model.ChatChunk;
import com.pawwork.opencode.model.ChatRequest;
import com.pawwork.opencode.model.ChatResponse;
import com.pawwork.opencode.model.ToolDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The OpenAI-compatible chat-completions client that speaks the OpenCode CLI dialect.
 *
 * <p>Behaviour mirrored from the Android reference client ({@code ChatFragment}):</p>
 * <ul>
 *   <li>gateway calls are always streamed ({@code stream:true} + {@code stream_options.include_usage})
 *       and aggregated back into a single {@link ChatResponse} — the Zen free tier answers
 *       {@code 403} to non-streaming requests;</li>
 *   <li>the {@code x-opencode-session} / {@code x-opencode-client} / {@code x-opencode-project} /
 *       {@code x-opencode-request} headers and CLI {@code User-Agent} are attached to every gateway
 *       call;</li>
 *   <li>the official CLI tool schemas are injected so the free-tier gate does not reject the call;</li>
 *   <li>transient I/O failures are retried up to 3 times with a short backoff (DNS gets its own,
 *       shorter pause because a flaky lookup often clears on the immediate retry).</li>
 * </ul>
 */
public class OpenAiClient implements AutoCloseable {

    /** Receives decoded chunks of one streaming call. */
    public interface ChunkListener {

        void onChunk(ChatChunk chunk);

        default void onDone() {
        }
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final OpenCodeProperties properties;
    private final OpenCodeIdentity identity;
    private final List<ToolDefinition> officialTools;
    private final ScheduledExecutorService watchdog;
    private final AtomicBoolean closed = new AtomicBoolean();

    public OpenAiClient(OpenCodeProperties properties,
                        OpenCodeIdentity identity,
                        ObjectMapper mapper,
                        List<ToolDefinition> officialTools) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.officialTools = officialTools == null ? List.of() : List.copyOf(officialTools);
        this.mapper = mapper == null ? defaultMapper() : mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "opencode-stream-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    /** An ObjectMapper that tolerates the extra fields every gateway adds to its payloads. */
    public static ObjectMapper defaultMapper() {
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------ public API

    /**
     * Runs one chat completion and returns the assembled response.
     *
     * <p>When the provider forces streaming, the SSE stream is consumed here and folded into a
     * single response — callers never have to know which transport the gateway prefers.</p>
     *
     * @throws OpenCodeException on transport failure, a non-2xx answer, or a dead stream
     */
    public ChatResponse complete(ProviderProfile profile, ChatRequest request) {
        ChatRequest wire = prepare(profile, request);

        if (Boolean.TRUE.equals(wire.getStream())) {
            SseAccumulator accumulator = new SseAccumulator();
            stream(profile, wire, accumulator::accept);
            ChatResponse assembled = accumulator.toResponse();
            if (assembled.getModel() == null) {
                assembled.setModel(profile.defaultModel());
            }
            return assembled;
        }

        String body = toJson(wire);
        int maxAttempts = attempts();
        OpenCodeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(
                        buildRequest(profile, body, "application/json"),
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse(response.body());
                }
                last = new OpenCodeException(status, extractError(status, response.body()), truncate(response.body()));
                if (!isRetryableStatus(status)) {
                    throw last;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenCodeException(0, "interrupted while calling the gateway", null, e);
            } catch (IOException e) {
                last = new OpenCodeException(0, describeIo(e), null, e);
                if (attempt >= maxAttempts) {
                    throw last;
                }
                sleep(backoffMillis(attempt, e));
                continue;
            }
            if (attempt >= maxAttempts) {
                throw last;
            }
            sleep(backoffMillis(attempt, null));
        }
        throw last != null ? last : new OpenCodeException(0, "chat completion failed without a cause");
    }

    /**
     * Streams one chat completion, delivering every decoded chunk to {@code listener} as it lands.
     *
     * <p>The call is only retried while nothing has been delivered yet — replaying a stream that
     * already produced output would duplicate text on the caller's wire.</p>
     */
    public void stream(ProviderProfile profile, ChatRequest request, ChunkListener listener) {
        ChatRequest wire = prepare(profile, request);
        wire.setStream(true);

        String body = toJson(wire);
        int maxAttempts = attempts();
        OpenCodeException last = null;
        CountingListener counting = new CountingListener(listener);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            counting.reset();
            try {
                HttpResponse<InputStream> response = http.send(
                        buildRequest(profile, body, "text/event-stream, application/json"),
                        HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();
                if (status >= 400) {
                    String errorBody = readAll(response.body());
                    OpenCodeException error = new OpenCodeException(status, extractError(status, errorBody), truncate(errorBody));
                    if (!isRetryableStatus(status)) {
                        throw error;
                    }
                    last = error;
                } else {
                    pump(response.body(), counting);
                    listener.onDone();
                    return;
                }
            } catch (OpenCodeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenCodeException(0, "interrupted while streaming from the gateway", null, e);
            } catch (IOException e) {
                if (counting.delivered() > 0) {
                    // Output already went out — surface the break instead of replaying it.
                    throw new OpenCodeException(0, "stream aborted after " + counting.delivered()
                            + " chunks: " + describeIo(e), null, e);
                }
                last = new OpenCodeException(0, describeIo(e), null, e);
                if (attempt >= maxAttempts) {
                    throw last;
                }
            }
            if (attempt >= maxAttempts) {
                throw last != null ? last : new OpenCodeException(0, "streaming request failed without a cause");
            }
            sleep(backoffMillis(attempt, null));
        }
        throw last != null ? last : new OpenCodeException(0, "streaming request failed without a cause");
    }

    // ------------------------------------------------------------------ request building

    /**
     * Normalises a caller's request for the chosen provider: default model, forced streaming and
     * gateway tool schemas where the gateway demands them.
     */
    private ChatRequest prepare(ProviderProfile profile, ChatRequest request) {
        ChatRequest wire = deepCopy(request);

        if (wire.getModel() == null || wire.getModel().isBlank()) {
            wire.setModel(profile.defaultModel());
        }
        if (wire.getMessages() == null) {
            wire.setMessages(new ArrayList<>());
        }

        if (profile.usesGatewayHeaders()) {
            // The free tier serves streaming only, and asks for the CLI's own output budget.
            wire.setStream(true);
            wire.setStreamOptions(includeUsageOptions());
            if (wire.getMaxTokens() == null && profile.maxTokens() != null) {
                wire.setMaxTokens(profile.maxTokens());
            }
            if (properties.isIncludeOfficialTools()) {
                injectOfficialTools(wire);
            }
        } else {
            if (wire.getStream() == null) {
                wire.setStream(false);
            }
            if (wire.getMaxTokens() == null && profile.maxTokens() != null
                    && !isReasoningFirstModel(wire.getModel())) {
                wire.setMaxTokens(profile.maxTokens());
            }
        }
        return wire;
    }

    /**
     * Prepends the 11 official CLI tool schemas, skipping names the caller already advertises.
     *
     * <p>The free-tier gate inspects tool <em>names</em> only — schemas and descriptions are free
     * — so the official set has to be present, but a caller's own tools survive untouched.</p>
     */
    private void injectOfficialTools(ChatRequest wire) {
        if (officialTools.isEmpty()) {
            return;
        }
        // Names the caller already advertises win: a caller that implements (say) `bash`
        // itself must keep its own schema, the official one only fills the gaps.
        Map<String, Boolean> existing = new LinkedHashMap<>();
        if (wire.getTools() != null) {
            for (ToolDefinition definition : wire.getTools()) {
                String name = definition.functionName();
                if (!name.isBlank()) {
                    existing.put(name, Boolean.TRUE);
                }
            }
        }

        List<ToolDefinition> merged = new ArrayList<>(officialTools.size() + existing.size());
        for (ToolDefinition official : officialTools) {
            String name = official.functionName();
            if (!name.isBlank() && !existing.containsKey(name)) {
                merged.add(official);
            }
        }
        merged.addAll(wire.getTools() == null ? List.of() : wire.getTools());
        wire.setTools(merged);
    }

    private HttpRequest buildRequest(ProviderProfile profile, String body, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(profile.chatCompletionsUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyIdentity(profile, builder);
        return builder.build();
    }

    /**
     * Fetches a provider's {@code GET /models} catalogue with the same identity headers, so a
     * client can list what the gateway serves without going around the free-tier gate.
     */
    public com.fasterxml.jackson.databind.JsonNode listModels(ProviderProfile profile) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(profile.endpoint() + "/models"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())))
                .GET();
        applyIdentity(profile, builder);
        HttpRequest request = builder.build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new OpenCodeException(status, extractError(status, response.body()), truncate(response.body()));
            }
            try {
                return mapper.readTree(response.body());
            } catch (JsonProcessingException e) {
                throw new OpenCodeException(0, "Gateway returned a body that is not JSON", truncate(response.body()), e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenCodeException(0, "interrupted while listing models", null, e);
        } catch (IOException e) {
            throw new OpenCodeException(0, describeIo(e), null, e);
        }
    }

    /** Attaches the bearer key and, for gateway providers, the OpenCode CLI identity headers. */
    private void applyIdentity(ProviderProfile profile, HttpRequest.Builder builder) {
        String apiKey = profile.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        if (profile.usesGatewayHeaders()) {
            // What the OpenCode CLI sends, so the gateway accepts the call:
            // 1. x-opencode-session — required (400 MissingSessionID without it)
            // 2. x-opencode-client + x-opencode-project — anonymous free-pool gateway routing
            // 3. x-opencode-request — the CLI's per-turn id shape
            // 4. opencode/* User-Agent — identifies us as the CLI rather than a scraper
            builder.header("x-opencode-session", identity.sessionId());
            builder.header("x-opencode-client", properties.getClientName());
            builder.header("x-opencode-project", properties.getProjectName());
            builder.header("x-opencode-request", identity.requestId());
            builder.header("User-Agent", profile.userAgent());
        }
    }

    // ------------------------------------------------------------------ SSE plumbing

    /** Reads {@code data:} lines until {@code [DONE]} or the stream ends. */
    private void pump(InputStream in, CountingListener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        IdleGuard guard = new IdleGuard(in);
        try {
            String line;
            while (true) {
                guard.arm();
                try {
                    line = reader.readLine();
                } finally {
                    guard.disarm();
                }
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("data: [DONE]") || trimmed.equals("[DONE]")) {
                    break;
                }
                if (!trimmed.startsWith("data:")) {
                    // Comments (: keep-alive) and event:/id: metadata carry no payload.
                    continue;
                }
                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                ChatChunk chunk = parseChunk(payload);
                if (chunk != null) {
                    listener.onChunk(chunk);
                }
            }
            if (guard.timedOut()) {
                throw new OpenCodeException(0, "SSE stream idle for more than "
                        + properties.getStreamIdleTimeoutSeconds() + "s and was closed");
            }
        } catch (OpenCodeException e) {
            throw e;
        } catch (IOException e) {
            if (guard.timedOut()) {
                throw new OpenCodeException(0, "SSE stream idle for more than "
                        + properties.getStreamIdleTimeoutSeconds() + "s and was closed", null, e);
            }
            throw e;
        } finally {
            guard.disarm();
            try {
                reader.close();
            } catch (IOException ignored) {
                // closing the socket is the whole point
            }
        }
    }

    private ChatChunk parseChunk(String payload) {
        try {
            return mapper.readValue(payload, ChatChunk.class);
        } catch (JsonProcessingException e) {
            // A malformed frame must not kill an otherwise healthy stream.
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private ChatResponse parseResponse(String body) {
        try {
            return mapper.readValue(body, ChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new OpenCodeException(0, "Gateway returned a body that is not JSON: " + truncate(body), truncate(body), e);
        }
    }

    private ChatRequest deepCopy(ChatRequest request) {
        try {
            return mapper.readValue(mapper.writeValueAsString(request), ChatRequest.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Could not copy the chat request", e);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode includeUsageOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("include_usage", true);
        return mapper.valueToTree(options);
    }

    private String toJson(ChatRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Could not serialise the chat request", e);
        }
    }

    /** Reads a whole (error) body into a string. */
    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Pulls the provider's own words out of the many error shapes in the wild. */
    private static String extractError(int status, String body) {
        String upstream = null;
        if (body != null && !body.isBlank()) {
            try {
                ChatResponse parsed = new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(body, ChatResponse.class);
                upstream = parsed.errorMessage();
            } catch (Exception ignored) {
                // not JSON — fall through to the raw body
            }
        }
        // The gateway's own explanation always wins: guessing "bad API key" for every 401 would
        // be wrong (it also uses 401 for an unsupported model).
        if (upstream != null && !upstream.isBlank()) {
            return "API " + status + ": " + upstream;
        }
        String hint = status == 401 ? " — API key rejected, check your key"
                : status == 403 ? " — the gateway refused this request (free-tier gate?)" : "";
        return "API " + status + hint + ": " + truncate(body);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    private static String describeIo(IOException e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return (e instanceof UnknownHostException ? "DNS lookup failed" : "network error") + ": " + detail;
    }

    private int attempts() {
        return Math.max(1, properties.getRetry().getMaxAttempts());
    }

    private long backoffMillis(int attempt, IOException cause) {
        OpenCodeProperties.Retry retry = properties.getRetry();
        if (cause instanceof UnknownHostException) {
            return Math.max(1, retry.getDnsBackoffMillis());
        }
        return Math.max(1, retry.getBackoffMillis()) * Math.max(1, attempt - 1L);
    }

    private boolean isRetryableStatus(int status) {
        if (!properties.getRetry().isRetryOnTransientHttp()) {
            return false;
        }
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static boolean isReasoningFirstModel(String model) {
        // Models whose answers spill into the reasoning channel must not be given an output cap.
        return model != null && model.startsWith("big-pickle");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenCodeException(0, "interrupted while waiting to retry", null, e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            watchdog.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ nested types

    /** Counts delivered chunks so a retry only happens before any output escaped. */
    private static final class CountingListener implements ChunkListener {

        private final ChunkListener delegate;
        private final AtomicInteger count = new AtomicInteger();

        private CountingListener(ChunkListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onChunk(ChatChunk chunk) {
            count.incrementAndGet();
            delegate.onChunk(chunk);
        }

        @Override
        public void onDone() {
            delegate.onDone();
        }

        private int delivered() {
            return count.get();
        }

        private void reset() {
            count.set(0);
        }
    }

    /**
     * Closes a stream that has gone silent. The JDK HTTP client has no per-read timeout, so
     * without this a dropped connection would block {@code readLine()} forever.
     */
    private final class IdleGuard {

        private final InputStream in;
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private volatile ScheduledFuture<?> pending;

        private IdleGuard(InputStream in) {
            this.in = in;
        }

        private void arm() {
            disarm();
            int seconds = properties.getStreamIdleTimeoutSeconds();
            if (seconds <= 0) {
                return;
            }
            pending = watchdog.schedule(() -> {
                timedOut.set(true);
                try {
                    in.close();
                } catch (IOException ignored) {
                    // closing is the goal; the reader will notice
                }
            }, seconds, TimeUnit.SECONDS);
        }

        private void disarm() {
            ScheduledFuture<?> future = pending;
            pending = null;
            if (future != null) {
                future.cancel(false);
            }
        }

        private boolean timedOut() {
            return timedOut.get();
        }
    }
}
