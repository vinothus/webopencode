package com.pawwork.opencode.stt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The application-facing speech-to-text service.
 *
 * <p>In the default {@link SttProperties.Mode#EMBEDDED} mode the Whisper engine runs in this
 * JVM and every call is a plain method call — no HTTP hop, no second process. In
 * {@link SttProperties.Mode#REMOTE} mode the same calls are forwarded to a separate
 * speech-to-text service over HTTP, which is the arrangement for a containerised web app talking
 * to a transcriber on the host.</p>
 *
 * <p>Both modes answer with the same JSON, so the front end does not care which one is in use.</p>
 */
public class SttService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SttProperties properties;
    private final ObjectProvider<SttEngine> engineProvider;
    private final ObjectProvider<SttModelManager> modelsProvider;
    private final HttpClient http;
    private final String baseUrl;
    private final String modelsBaseUrl;

    public SttService(SttProperties properties,
                      ObjectProvider<SttEngine> engineProvider,
                      ObjectProvider<SttModelManager> modelsProvider) {
        this.properties = properties;
        this.engineProvider = engineProvider;
        this.modelsProvider = modelsProvider;
        String url = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        // Model management lives at /api/models on the same service, so drop the /audio suffix.
        this.modelsBaseUrl = this.baseUrl.endsWith("/api/audio")
                ? this.baseUrl.substring(0, this.baseUrl.length() - "/api/audio".length())
                : this.baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** True when the engine runs inside this process. */
    public boolean isEmbedded() {
        return properties.getMode() != SttProperties.Mode.REMOTE;
    }

    // ------------------------------------------------------------------ transcription

    /** Transcribe a WAV clip. */
    public Map<String, Object> transcribe(byte[] audio, String language, String model)
            throws SttException {
        if (audio == null || audio.length == 0) {
            throw new SttException(400, "empty request — send the audio as multipart "
                    + "field 'audio' or as a raw WAV body");
        }
        long cap = properties.getMaxBytes();
        if (cap > 0 && audio.length > cap) {
            throw new SttException(413, "audio too large (" + audio.length
                    + " bytes, cap " + cap + ")");
        }
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/transcribe?language="
                            + encode(language)))
                    .header("Content-Type", "application/octet-stream")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                    .build());
        }
        SttEngine engine = engine();
        SttTranscription result = engine.transcribe(audio, language, model);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("text", result.text());
        out.put("language", result.language());
        out.put("model", result.model());
        out.put("language_probability", 1.0);
        out.put("duration", result.duration());
        out.put("sample_rate", result.sampleRate());
        out.put("elapsed_ms", result.elapsedMs());
        out.put("inference_ms", result.inferenceMs());
        return out;
    }

    // ------------------------------------------------------------------ status

    /** The engine's status. */
    public Map<String, Object> health() throws SttException {
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build());
        }
        return engine().health();
    }

    // ------------------------------------------------------------------ models

    /** The catalogue plus what is installed. */
    public Map<String, Object> listModels() throws SttException {
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(modelsBaseUrl + "/api/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build());
        }
        return modelManager().list();
    }

    /** The progress of the in-flight download. */
    public Map<String, Object> modelProgress() throws SttException {
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(modelsBaseUrl + "/api/models/progress"))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build());
        }
        return modelManager().progress();
    }

    /** Start a background model download. */
    public Map<String, Object> downloadModel(String model, String source) throws SttException {
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(modelsBaseUrl + "/api/models/download?model="
                            + encode(model) + "&source=" + encode(source)))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
        }
        modelManager().startDownload(model, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("started", true);
        out.put("model", model);
        out.put("source", source);
        return out;
    }

    /** Remove an installed model. */
    public Map<String, Object> deleteModel(String model) throws SttException {
        if (!isEmbedded()) {
            return forwardMap(HttpRequest.newBuilder()
                    .uri(URI.create(modelsBaseUrl + "/api/models/delete?model=" + encode(model)))
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());
        }
        return modelManager().delete(model);
    }

    // ------------------------------------------------------------------ plumbing

    private SttEngine engine() throws SttException {
        SttEngine engine = engineProvider.getIfAvailable();
        if (engine == null) {
            throw new SttException(503, "the local speech-to-text engine is not available — "
                    + "the sherpa-onnx bindings are missing from the classpath");
        }
        return engine;
    }

    private SttModelManager modelManager() throws SttException {
        SttModelManager manager = modelsProvider.getIfAvailable();
        if (manager == null) {
            throw new SttException(503, "the local speech-to-text engine is not available — "
                    + "the sherpa-onnx bindings are missing from the classpath");
        }
        return manager;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Forward a request to the remote service and parse its JSON answer. */
    private Map<String, Object> forwardMap(HttpRequest request) throws SttException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new SttException(503, "speech-to-text timed out after "
                    + properties.getTimeoutSeconds() + "s");
        } catch (IOException e) {
            throw new SttException(503, "speech-to-text service unavailable at " + baseUrl
                    + " (" + e.getClass().getSimpleName() + ") — check that the transcriber is running");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SttException(503, "interrupted while waiting for the speech-to-text service");
        }
        String body = response.body() == null ? "{}" : response.body();
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            parsed = new LinkedHashMap<>();
            parsed.put("ok", false);
            parsed.put("error", body);
        }
        if (response.statusCode() >= 400) {
            Object message = parsed.get("error");
            throw new SttException(response.statusCode(),
                    message == null ? ("HTTP " + response.statusCode()) : String.valueOf(message));
        }
        return parsed;
    }
}
