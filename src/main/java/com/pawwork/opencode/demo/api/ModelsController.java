package com.pawwork.opencode.demo.api;

import com.pawwork.opencode.stt.SttException;
import com.pawwork.opencode.stt.SttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Model management at the top level: {@code /api/models*}.
 *
 * <p>This is the path the voice page's Models panel calls. It is the same service the
 * {@code /api/audio/models*} endpoints expose — they are kept so a deployment that routes
 * {@code /api/models} elsewhere keeps working.</p>
 */
@RestController
@RequestMapping("/api/models")
@Tag(name = "Audio", description = "Local speech-to-text: audio in, text out, English + Tamil, "
        + "transcribed on this server.")
public class ModelsController {

    private final SttService stt;

    public ModelsController(SttService stt) {
        this.stt = stt;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List speech-to-text models",
            description = "Installed models, downloadable sizes, free disk and the available "
                    + "sources.")
    public ResponseEntity<String> list() {
        try {
            return JsonResponses.ok(stt.listModels());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    @GetMapping(value = "/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Model download progress",
            description = "Phase (idle/downloading/extracting/done/error), bytes received, total "
                    + "and percent for the in-flight download.")
    public ResponseEntity<String> progress() {
        try {
            return JsonResponses.ok(stt.modelProgress());
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }

    @PostMapping(value = "/download", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Install a speech-to-text model",
            description = "Starts a background download of a Whisper size (small, medium or "
                    + "large-v3) from the chosen source (github, huggingface). Answers 202 at once; "
                    + "poll /api/models/progress for progress. Needs enough free disk.")
    public ResponseEntity<String> download(
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

    @PostMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Remove an installed model",
            description = "Deletes a model directory under the models root and frees its disk. "
                    + "Refuses any path outside the models root.")
    public ResponseEntity<String> delete(
            @Parameter(description = "Model name to remove")
            @RequestParam(name = "model") String model) {
        try {
            return JsonResponses.ok(stt.deleteModel(model));
        } catch (SttException e) {
            return JsonResponses.error(e.status(), String.valueOf(e.getMessage()));
        }
    }
}
