package com.pawwork.opencode.demo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
import java.util.List;
import java.util.Map;

/**
 * Server-side speech-to-text.
 *
 * <p>The browser only <em>captures</em> audio (WebRTC {@code getUserMedia} + {@code MediaRecorder});
 * the audio is POSTed here as-is and everything below — decoding, language detection and the
 * Whisper inference — happens on the host, in the Java {@code sherpa-onnx} Whisper service
 * ({@code engtop-stt} on port 8092). No audio and no transcript is sent to any third party.</p>
 *
 * <p>Supported languages: English ({@code en}) and Tamil ({@code ta}), plus {@code auto} to let the
 * model pick between them.</p>
 */
@RestController
@RequestMapping("/api/audio")
@Tag(name = "Audio", description = "Local speech-to-text: audio in, text out, English + Tamil, "
        + "transcribed on this server.")
public class AudioController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> LANGUAGES = List.of("auto", "en", "ta");

    private final HttpClient http;
    private final String baseUrl;
    private final String modelsBaseUrl;
    private final long timeoutSeconds;
    private final long maxBytes;

    public AudioController(
            @Value("${stt.base-url:http://host.containers.internal:8092/api/audio}") String baseUrl,
            @Value("${stt.timeout-seconds:180}") long timeoutSeconds,
            @Value("${stt.max-bytes:26214400}") long maxBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Model management lives at /api/models on the same service, so drop the /audio suffix.
        this.modelsBaseUrl = this.baseUrl.endsWith("/api/audio")
                ? this.baseUrl.substring(0, this.baseUrl.length() - "/api/audio".length())
                : this.baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.maxBytes = maxBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------ transcribe

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe an uploaded audio clip (English / Tamil)",
            description = """
                    Multipart upload: `audio` is the raw recording (WAV — `voice-chat.html` packs \
                    PCM WAV in the browser), `language` is `auto` (default), `en` or `ta`. The bytes \
                    are handed to the host's Java Whisper service, which returns JSON:

                    `{ok, text, language, language_probability, duration, elapsed_ms, segments[]}`

                    `language` in the answer is what the model detected, even when `auto` was asked \
                    for. Nothing leaves this server.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The transcript",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "400", description = "Missing/empty audio or bad language"),
            @ApiResponse(responseCode = "413", description = "Audio larger than stt.max-bytes"),
            @ApiResponse(responseCode = "503", description = "The local speech-to-text service is not reachable"),
            @ApiResponse(responseCode = "500", description = "Transcription failed in the speech-to-text service")})
    public ResponseEntity<String> transcribe(
            @Parameter(description = "Audio clip", required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("audio") MultipartFile audio,
            @Parameter(description = "auto | en | ta", example = "auto")
            @RequestParam(value = "language", defaultValue = "auto") String language) throws IOException {

        if (audio == null || audio.isEmpty()) {
            return error(400, "no audio in the request — send the clip as multipart field 'audio'");
        }
        String lang = normaliseLanguage(language);
        if (lang == null) {
            return error(400, "language must be one of " + LANGUAGES);
        }
        byte[] bytes = audio.getBytes();
        return transcribeBytes(bytes,
                audio.getContentType() == null ? "application/octet-stream" : audio.getContentType(),
                audio.getOriginalFilename(), lang);
    }

    @PostMapping(value = "/transcribe/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe a raw audio body (no multipart)",
            description = "Same engine as POST /transcribe, but the request body is the audio itself: "
                    + "is the audio, `Content-Type` says what it is, `X-Language` (auto|en|ta, "
                    + "optional) says which language to expect.")
    public ResponseEntity<String> transcribeRaw(
            @RequestBody byte[] body,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "X-Language", required = false, defaultValue = "auto") String language,
            @RequestHeader(value = "X-Filename", required = false) String filename) throws IOException {

        if (body == null || body.length == 0) {
            return error(400, "empty body — send the audio bytes");
        }
        String lang = normaliseLanguage(language);
        if (lang == null) {
            return error(400, "language must be one of " + LANGUAGES);
        }
        return transcribeBytes(body, contentType, filename, lang);
    }

    // ---------------------------------------------------------------- health

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Speech-to-text service status",
            description = "Forwards the host speech-to-text service's /health: model name, load "
                    + "state and the languages it serves (en, ta). Answers 503 when it is down.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sidecar reachable"),
            @ApiResponse(responseCode = "503", description = "Sidecar not reachable")})
    public ResponseEntity<String> health() {
        return forward(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET().build());
    }

    // ------------------------------------------------------------ models

    // A model is a few hundred MB. Downloading runs on the STT service's own thread; these
    // endpoints only start it and report progress, so none of them can block for long.

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List speech-to-text models",
            description = "Installed models, downloadable sizes, free disk and the available "
                    + "sources. Also reports status 'no-models' when the engine has none yet.")
    public ResponseEntity<String> models() {
        return forward(HttpRequest.newBuilder()
                .uri(URI.create(modelsBaseUrl + "/api/models"))
                .timeout(Duration.ofSeconds(10))
                .GET().build());
    }

    @GetMapping(value = "/models/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Model download progress",
            description = "Phase (idle/downloading/extracting/done/error), bytes received, total "
                    + "and percent for the in-flight download.")
    public ResponseEntity<String> modelsProgress() {
        return forward(HttpRequest.newBuilder()
                .uri(URI.create(modelsBaseUrl + "/api/models/progress"))
                .timeout(Duration.ofSeconds(10))
                .GET().build());
    }

    @PostMapping(value = "/models/download", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Install a speech-to-text model",
            description = "Starts a background download of a Whisper size (small, medium or "
                    + "large-v3) from the chosen source (github, huggingface). Answers 202 at once; "
                    + "poll /api/audio/models/progress for progress. Needs enough free disk.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Download started"),
            @ApiResponse(responseCode = "409", description = "A download is already running"),
            @ApiResponse(responseCode = "507", description = "Not enough free disk")})
    public ResponseEntity<String> modelsDownload(
            @Parameter(description = "Model name: small, medium or large-v3")
            @RequestParam(name = "model") String model,
            @Parameter(description = "Source id: github or huggingface")
            @RequestParam(name = "source", defaultValue = "github") String source) {
        String uri = modelsBaseUrl + "/api/models/download?model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + "&source=" + URLEncoder.encode(source, StandardCharsets.UTF_8);
        return forward(HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    @PostMapping(value = "/models/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Remove an installed model",
            description = "Deletes a model directory under the models root and frees its disk. "
                    + "Refuses any path outside the models root.")
    public ResponseEntity<String> modelsDelete(
            @Parameter(description = "Model name to remove")
            @RequestParam(name = "model") String model) {
        String uri = modelsBaseUrl + "/api/models/delete?model="
                + URLEncoder.encode(model, StandardCharsets.UTF_8);
        return forward(HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    // -------------------------------------------------------------- plumbing

    private ResponseEntity<String> transcribeBytes(byte[] bytes, String contentType,
                                                   String filename, String language) throws IOException {
        if (bytes.length > maxBytes) {
            return error(413, "audio too large (" + bytes.length + " bytes, cap " + maxBytes + ")");
        }
        String safeName = filename == null ? "" :
                URLEncoder.encode(filename.replaceAll("[^\\x20-\\x7E]", "_"), StandardCharsets.UTF_8);
        String uri = baseUrl + "/transcribe?language="
                + URLEncoder.encode(language, StandardCharsets.UTF_8)
                + (safeName.isEmpty() ? "" : "&name=" + safeName);

        return forward(HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType)
                .header("X-Filename", safeName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build());
    }

    private ResponseEntity<String> forward(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return error(503, "speech-to-text timed out after " + timeoutSeconds + "s");
        } catch (IOException e) {
            return error(503, "speech-to-text service unavailable at " + baseUrl
                    + " (" + e.getClass().getSimpleName() + ") — check engtop-stt on the host "
                    + "(systemctl status engtop-stt)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, "interrupted while waiting for the speech-to-text service");
        }
        String body = response.body() == null ? "{}" : response.body();
        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String normaliseLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "auto";
        }
        String value = language.trim().toLowerCase();
        return LANGUAGES.contains(value) ? value : null;
    }

    private static ResponseEntity<String> error(int status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(payload));
        } catch (IOException e) {                                  // jackson cannot fail here
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"ok\":false,\"error\":\"" + message + "\"}");
        }
    }
}
