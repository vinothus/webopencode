package com.pawwork.opencode.stt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the local speech-to-text engine.
 *
 * <p>In the single-jar setup the engine runs <em>inside</em> this JVM, so the only setting that
 * really matters is {@link #modelsDir} — where the Whisper models live. The remaining fields are
 * the knobs the standalone service used to take as system properties.</p>
 *
 * <p>When {@link #mode} is {@link Mode#REMOTE} the app keeps talking to a separate
 * speech-to-text service over HTTP instead — the arrangement used when the web app runs in a
 * container and the transcriber runs on the host.</p>
 */
@ConfigurationProperties(prefix = "stt")
public class SttProperties {

    /** How the application reaches the speech-to-text engine. */
    public enum Mode {
        /** The engine runs in this JVM. The default: one jar, one process. */
        EMBEDDED,
        /** A separate speech-to-text service is contacted over HTTP. */
        REMOTE
    }

    /** {@link Mode#EMBEDDED} (default) or {@link Mode#REMOTE}. */
    private Mode mode = Mode.EMBEDDED;

    /** Where the speech-to-text service lives, in {@link Mode#REMOTE}. */
    private String baseUrl = "http://127.0.0.1:8092/api/audio";

    /** Directory holding the Whisper models, one sub-directory per size. */
    private String modelsDir = "models";

    /** Threads each recogniser may use. */
    private int threads = 8;

    /** The model used when a request does not name one. */
    private String defaultModel = "medium";

    /** Silero voice-activity detection: splits long clips on pauses. Off by default. */
    private boolean vad = false;

    /**
     * Path to the Silero VAD model. Blank means "the copy bundled in the jar"
     * ({@code silero_vad.onnx}), which is extracted to a temporary file on first use.
     */
    private String vadModel = "";

    /** How long a transcription may take before the request is abandoned. */
    private int timeoutSeconds = 180;

    /** Largest accepted audio payload, in bytes. */
    private long maxBytes = 26214400L;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModelsDir() { return modelsDir; }
    public void setModelsDir(String modelsDir) { this.modelsDir = modelsDir; }

    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }

    public boolean isVad() { return vad; }
    public void setVad(boolean vad) { this.vad = vad; }

    public String getVadModel() { return vadModel; }
    public void setVadModel(String vadModel) { this.vadModel = vadModel; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
}
