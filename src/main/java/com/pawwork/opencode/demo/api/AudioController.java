package com.pawwork.opencode.demo.api;

import com.pawwork.opencode.stt.SttException;
import com.pawwork.opencode.stt.SttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;
import java.util.Map;

/**
 * Server-side speech-to-text.
 *
 * <p>The browser only <em>captures</em> audio (WebRTC {@code getUserMedia} + {@code MediaRecorder});
 * the audio is POSTed here as-is and everything below — decoding, language detection and the
 * Whisper inference — happens on this machine, in the sherpa-onnx engine that runs inside this
 * JVM. No audio and no transcript is sent to any third party.</p>
 *
 * <p>Supported languages: English ({@code en}) and Tamil ({@code ta}), plus {@code auto} to let the
 * model pick between them.</p>
 */
@RestController
@RequestMapping("/api/audio")
@Tag(name = "Audio", description = "Local speech-to-text: audio in, text out, English + Tamil, "
        + "transcribed on this server.")
public class AudioController {

    private static final List<String> LANGUAGES = List.of("auto", "en", "ta");

    private final SttService stt;

    public AudioController(SttService stt) {
        this.stt = stt;
    }

    // ------------------------------------------------------------ transcribe

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe an uploaded audio clip (English / Tamil)",
            description = """
                    Multipart upload: `audio` is the raw recording (WAV — `voice-chat.html` packs \
                    PCM WAV in the browser), `language` is `auto` (default), `en` or `ta`, and \
                    `model` names the Whisper size (`small`, `medium`, `large-v3`). The answer is JSON:

                    `{ok, text, language, language_probability, duration, elapsed_ms, inference_ms}`

                    `language` in the answer is what the model detected, even when `auto` was asked \
                    for. Nothing leaves this server.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The transcript",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "400", description = "Missing/empty audio or bad language"),
            @ApiResponse(responseCode = "413", description = "Audio larger than stt.max-bytes"),
            @ApiResponse(responseCode = "503", description = "No model installed, or the engine is unavailable"),
            @ApiResponse(responseCode = "500", description = "Transcription failed in the engine")})
    public ResponseEntity<String> transcribe(
            @Parameter(description = "Audio clip", required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam(value = "audio", required = false) MultipartFile audio,
            @Parameter(description = "auto | en | ta", example = "auto")
            @RequestParam(value = "language", defaultValue = "auto") String language,
            @Parameter(description = "Whisper size: small, medium or large-v3", example = "medium")
            @RequestParam(value = "model", required = false) String model) throws IOException {

        if (audio == null || audio.isEmpty()) {
            return JsonResponses.error(400,
                    "no audio in the request — send the clip as multipart field 'audio'");
        }
        String lang = normaliseLanguage(language);
        if (lang == null) {
            return JsonResponses.error(400, "language must be one of " + LANGUAGES);
        }
        byte[] bytes = audio.getBytes();
        try {
            return JsonResponses.ok(stt.transcribe(bytes, lang, model));
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    @PostMapping(value = "/transcribe/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe a raw audio body (no multipart)",
            description = "Same engine as POST /transcribe, but the request body is the audio itself: "
                    + "`Content-Type` says what it is, `X-Language` (auto|en|ta, optional) says which "
                    + "language to expect and `X-Model` names the Whisper size.")
    public ResponseEntity<String> transcribeRaw(
            @RequestBody byte[] body,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "X-Language", required = false, defaultValue = "auto") String language,
            @RequestHeader(value = "X-Model", required = false) String model,
            @RequestHeader(value = "X-Filename", required = false) String filename) throws IOException {

        if (body == null || body.length == 0) {
            return JsonResponses.error(400, "empty body — send the audio bytes");
        }
        String lang = normaliseLanguage(language);
        if (lang == null) {
            return JsonResponses.error(400, "language must be one of " + LANGUAGES);
        }
        try {
            return JsonResponses.ok(stt.transcribe(body, lang, model));
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- health

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Speech-to-text engine status",
            description = "The engine's model name, load state and the languages it serves (en, ta). "
                    + "Answers 503 when the engine cannot be reached.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Engine reachable"),
            @ApiResponse(responseCode = "503", description = "Engine not reachable")})
    public ResponseEntity<String> health() {
        try {
            return JsonResponses.ok(stt.health());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------ models

    // A model is a few hundred MB. Downloading runs on the engine's own thread; these endpoints
    // only start it and report progress, so none of them can block for long.

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List speech-to-text models",
            description = "Installed models, downloadable sizes, free disk and the available "
                    + "sources. Also reports status 'no-models' when the engine has none yet.")
    public ResponseEntity<String> models() {
        try {
            return JsonResponses.ok(stt.listModels());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    @GetMapping(value = "/models/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Model download progress",
            description = "Phase (idle/downloading/extracting/done/error), bytes received, total "
                    + "and percent for the in-flight download.")
    public ResponseEntity<String> modelsProgress() {
        try {
            return JsonResponses.ok(stt.modelProgress());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
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
        try {
            return ResponseEntity.status(202).contentType(MediaType.APPLICATION_JSON)
                    .body(JsonResponses.ok(stt.downloadModel(model, source)).getBody());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    @PostMapping(value = "/models/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Remove an installed model",
            description = "Deletes a model directory under the models root and frees its disk. "
                    + "Refuses any path outside the models root.")
    public ResponseEntity<String> modelsDelete(
            @Parameter(description = "Model name to remove")
            @RequestParam(name = "model") String model) {
        try {
            return JsonResponses.ok(stt.deleteModel(model));
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    // -------------------------------------------------------------- plumbing

    private static String normaliseLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "auto";
        }
        String value = language.trim().toLowerCase();
        return LANGUAGES.contains(value) ? value : null;
    }
}
