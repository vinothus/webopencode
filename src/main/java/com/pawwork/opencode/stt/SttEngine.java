package com.pawwork.opencode.stt;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The local speech-to-text engine: Whisper through the sherpa-onnx JNI bindings, running inside
 * this JVM.
 *
 * <p>Audio goes in, text comes out — nothing is sent anywhere. English and Tamil are supported,
 * and the language can be forced ({@code en} / {@code ta}) or detected ({@code auto}).</p>
 *
 * <p>The recognisers are built lazily per (model, language), cached for the life of the process
 * and warmed in the background at startup, so the first request after a boot is not the one that
 * pays the load cost. Decodes are serialised by a single lock: the native recogniser is not
 * re-entrant and auto-detection chains two passes over the same clip.</p>
 */
public class SttEngine implements AutoCloseable {

    /** Whisper's native sample rate. */
    static final int TARGET_RATE = 16000;

    /** The languages this engine serves. */
    public static final List<String> LANGUAGES = List.of("auto", "en", "ta");

    private final SttProperties properties;

    /** Installed models: name -&gt; absolute directory, in catalogue order. */
    private final Map<String, String> modelDirs = new LinkedHashMap<>();

    /** Loaded recognisers: {@code model:language} -&gt; recogniser. */
    private final Map<String, OfflineRecognizer> recognizers = new LinkedHashMap<>();

    private final ReentrantLock decodeLock = new ReentrantLock();

    private final long startedAt = System.currentTimeMillis();

    private volatile long requests = 0;
    private volatile String defaultModel = "medium";
    private volatile Path vadModelPath;

    /** Set when the native library could not be loaded — the engine cannot decode anything. */
    private volatile String engineError = null;

    public SttEngine(SttProperties properties) {
        this.properties = properties;
        this.defaultModel = properties.getDefaultModel();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Scans the models directory and warms the engine.
     *
     * <p>Never throws: a models-less installation is still a working installation. It reports
     * {@code status:"no-models"} and answers transcription requests with a clear 503 until a
     * model is installed.</p>
     */
    public void start() {
        reloadModels();
        if (modelDirs.isEmpty()) {
            log("no models under " + modelsDir() + " — starting in model-less mode; "
                    + "install one from the voice page (Models panel)");
            return;
        }
        // Preload the default so the first request is fast, then warm the rest in the background
        // (large-v3 takes a while to load; requests arriving meanwhile wait on the same lock).
        for (String lang : new String[]{"en", "ta"}) {
            try {
                recognizer(defaultModel, lang);
            } catch (Throwable e) {
                // The native library may be missing (UnsatisfiedLinkError) — that is a
                // configuration problem, not a crash: the app keeps running and the health
                // endpoint explains what is wrong.
                engineError = describe(e);
                log("FAILED to load " + defaultModel + "/" + lang + ": " + e);
                break;
            }
        }
        if (engineError != null) {
            return;
        }
        Thread warmer = new Thread(() -> {
            for (String m : modelDirs.keySet()) {
                for (String lang : new String[]{"en", "ta"}) {
                    try {
                        recognizer(m, lang);
                    } catch (Throwable t) {
                        log("FAILED to load " + m + "/" + lang + ": " + t);
                    }
                }
            }
            log("all models warm: " + recognizers.keySet());
        }, "stt-warmer");
        warmer.setDaemon(true);
        warmer.start();
    }

    /** A short, human-readable description of why the engine could not start. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        String detail = (message == null || message.isBlank())
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
        if (t instanceof UnsatisfiedLinkError) {
            return "the sherpa-onnx native library could not be loaded (" + detail
                    + ") — put libsherpa-onnx-jni.so and libonnxruntime.so in ./lib and start with "
                    + "-Djava.library.path=lib";
        }
        return detail;
    }

    @Override
    public void close() {
        synchronized (this) {
            recognizers.values().forEach(r -> {
                try { r.release(); } catch (Throwable ignored) { }
            });
            recognizers.clear();
        }
    }

    // ------------------------------------------------------------------ models

    /** The directory the models live in. */
    public String modelsDir() {
        return new File(properties.getModelsDir()).getAbsolutePath();
    }

    /** The installed models, in catalogue order. */
    public Map<String, String> installedModels() {
        synchronized (this) {
            return new LinkedHashMap<>(modelDirs);
        }
    }

    /** The model used when a request does not name one. */
    public String defaultModel() {
        return defaultModel;
    }

    /** Rescan the models directory so a freshly downloaded model appears without a restart. */
    public synchronized void reloadModels() {
        modelDirs.clear();
        scanModels(properties.getModelsDir());
        applyDefaultModel();
        log("rescan: models=" + modelDirs.keySet() + ", default=" + defaultModel);
    }

    /**
     * The effective default model: the configured one when it is installed, otherwise the first
     * model that is.
     *
     * <p>Re-evaluated on every rescan, so a model installed after start-up counts too — the
     * voice page's dropdown follows the same rule.</p>
     */
    private void applyDefaultModel() {
        String configured = properties.getDefaultModel();
        if (modelDirs.isEmpty()) {
            defaultModel = configured;
            return;
        }
        if (modelDirs.containsKey(configured)) {
            defaultModel = configured;
            return;
        }
        String fallback = modelDirs.keySet().iterator().next();
        if (!fallback.equals(defaultModel)) {
            log("warning: default model '" + configured + "' is not installed, using " + fallback);
        }
        defaultModel = fallback;
    }

    /** Discover models under the models directory: sub-directories with encoder/decoder/tokens. */
    private void scanModels(String modelsDir) {
        File root = new File(modelsDir);
        File[] subs = root.listFiles(File::isDirectory);
        if (subs == null) return;
        List<File> found = new ArrayList<>();
        for (File d : subs) {
            if (new File(d, "encoder.int8.onnx").isFile()
                    && new File(d, "decoder.int8.onnx").isFile()
                    && new File(d, "tokens.txt").isFile()) {
                found.add(d);
            }
        }
        List<File> ordered = new ArrayList<>();
        for (String p : new String[]{"small", "medium", "large-v3"}) {
            for (File d : new ArrayList<>(found)) {
                if (d.getName().equals(p)) { ordered.add(d); found.remove(d); }
            }
        }
        found.sort(Comparator.comparing(File::getName));
        ordered.addAll(found);
        for (File d : ordered) modelDirs.put(d.getName(), d.getAbsolutePath());
    }

    /** Lazily build and cache the recogniser for (model, language). */
    public OfflineRecognizer recognizer(String model, String lang) {
        String key = model + ":" + lang;
        OfflineRecognizer r = recognizers.get(key);
        if (r != null) return r;
        synchronized (this) {
            r = recognizers.get(key);
            if (r == null) {
                String dir = modelDirs.get(model);
                if (dir == null) {
                    throw new IllegalStateException("unknown model '" + model + "'");
                }
                long t = System.currentTimeMillis();
                log("loading " + key + " ...");
                r = buildRecognizer(dir + "/encoder.int8.onnx", dir + "/decoder.int8.onnx",
                        dir + "/tokens.txt", lang, properties.getThreads());
                recognizers.put(key, r);
                log("loaded " + key + " in " + (System.currentTimeMillis() - t) + " ms");
            }
            return r;
        }
    }

    static OfflineRecognizer buildRecognizer(String encoder, String decoder, String tokens,
                                             String language, int threads) {
        OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .setLanguage(language)
                .setTask("transcribe")
                .build();
        OfflineModelConfig model = OfflineModelConfig.builder()
                .setWhisper(whisper)
                .setTokens(tokens)
                .setNumThreads(threads)
                .setDebug(false)
                .build();
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(model)
                .setDecodingMethod("greedy_search")
                .build();
        return new OfflineRecognizer(config);
    }

    // ------------------------------------------------------------------ transcription

    /**
     * Transcribe a WAV clip.
     *
     * @param wav      the raw audio bytes, in any format {@link #parseWav} understands
     * @param language {@code auto}, {@code en} or {@code ta}
     * @param model    the Whisper size to use; blank means the default
     */
    public SttTranscription transcribe(byte[] wav, String language, String model) throws SttException {
        long t0 = System.currentTimeMillis();

        if (engineError != null) {
            throw new SttException(503, "the speech-to-text engine could not start: " + engineError);
        }
        if (modelDirs.isEmpty()) {
            throw new SttException(503, "no speech-to-text model is installed yet — "
                    + "install one from the voice page (Models panel), then try again");
        }
        String lang = (language == null || language.isBlank()) ? "auto" : language.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(lang)) {
            throw new SttException(400, "language must be one of " + LANGUAGES);
        }
        String size = (model == null || model.isBlank()) ? defaultModel : model.trim();
        if (!modelDirs.containsKey(size)) {
            throw new SttException(400, "unknown model '" + size
                    + "' — available: " + modelDirs.keySet());
        }
        if (wav == null || wav.length == 0) {
            throw new SttException(400, "empty request — send the audio as multipart "
                    + "field 'audio' or as a raw WAV body");
        }

        PcmData pcm;
        try {
            pcm = parseWav(wav);
        } catch (Exception e) {
            throw new SttException(400, "could not read audio: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }

        int origRate = pcm.sampleRate;
        pcm.sampleRate = TARGET_RATE;
        // We do our own resampling — sherpa-onnx's kernel is noticeably worse on non-integer
        // ratios such as the 22050 Hz clips browsers produce.
        pcm.samples = resample(pcm.samples, origRate, TARGET_RATE);

        // Optional Silero VAD: great for trimming long silence, but it can clip soft Tamil
        // plosives, so it is off by default.
        List<float[]> segments = properties.isVad() ? vadSegments(pcm.samples) : null;
        if (segments != null && segments.isEmpty()) {
            segments = null;   // VAD heard nothing — fall back to the whole clip
        }

        long inferMs = 0;
        String text;
        String detected;
        if (rms(pcm.samples) < 0.005) {
            // Effectively silent — whisper hallucinates on silence, so don't even decode.
            text = "";
            detected = lang.equals("auto") ? "en" : lang;
        } else {
            long tInfer = System.currentTimeMillis();
            try {
                decodeLock.lock();
                try {
                    float[][] clips = segments == null ? new float[][]{pcm.samples}
                            : segments.toArray(new float[0][]);
                    if (lang.equals("auto")) {
                        // Pass 1: English over every VAD segment. Real English comes back as
                        // plain text; non-English speech as a bracketed/starred stage direction
                        // (or nothing) — then pass 2 re-decodes as Tamil.
                        StringBuilder en = new StringBuilder();
                        boolean enMeta = true;
                        for (float[] clip : clips) {
                            String t = decode(size, "en", clip).trim();
                            if (!isMetaOrEmpty(t)) { enMeta = false; en.append(t).append(' '); }
                        }
                        String enText = en.toString().trim();
                        if (enMeta || enText.isEmpty()) {
                            StringBuilder ta = new StringBuilder();
                            for (float[] clip : clips) {
                                ta.append(stripMeta(decode(size, "ta", clip).trim())).append(' ');
                            }
                            text = ta.toString().trim();
                            detected = text.isEmpty() ? "en" : "ta";
                        } else {
                            text = enText;
                            detected = "en";
                        }
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (float[] clip : clips) {
                            sb.append(stripMeta(decode(size, lang, clip).trim())).append(' ');
                        }
                        text = sb.toString().trim();
                        detected = lang;
                    }
                } finally {
                    decodeLock.unlock();
                }
            } catch (Throwable t) {
                throw new SttException(500, "transcription failed: "
                        + String.valueOf(t.getMessage()), t);
            }
            inferMs = System.currentTimeMillis() - tInfer;
        }
        requests++;

        double duration = pcm.samples.length / (double) pcm.sampleRate;
        return new SttTranscription(text, detected, size, round2(duration), pcm.sampleRate,
                System.currentTimeMillis() - t0, inferMs);
    }

    /** Round to two decimals, the way the transcript JSON has always reported durations. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Run one decode under the caller's lock. */
    private String decode(String model, String language, float[] samples) {
        OfflineRecognizer rec = recognizer(model, language);
        OfflineStream stream = rec.createStream();
        try {
            stream.acceptWaveform(samples, TARGET_RATE);
            rec.decode(stream);
            OfflineRecognizerResult result = rec.getResult(stream);
            return result.getText() == null ? "" : result.getText();
        } finally {
            stream.release();
        }
    }

    /** Silero VAD over the whole clip; returns the speech segments (16 kHz floats). */
    List<float[]> vadSegments(float[] samples) {
        List<float[]> out = new ArrayList<>();
        Path model = vadModel();
        if (model == null) {
            log("VAD is enabled but no Silero model was found — decoding the whole clip");
            return out;
        }
        VadModelConfig cfg = VadModelConfig.builder()
                .setSileroVadModelConfig(SileroVadModelConfig.builder()
                        .setModel(model.toAbsolutePath().toString()).setThreshold(0.5f)
                        .setMinSilenceDuration(0.35f).setMinSpeechDuration(0.25f)
                        .setWindowSize(512).setMaxSpeechDuration(30f).build())
                .setSampleRate(TARGET_RATE).setNumThreads(1).setDebug(false).build();
        Vad vad = new Vad(cfg);
        try {
            int win = 512;
            for (int i = 0; i + win <= samples.length; i += win) {
                float[] frame = new float[win];
                System.arraycopy(samples, i, frame, 0, win);
                vad.acceptWaveform(frame);
                while (!vad.empty()) {
                    SpeechSegment seg = vad.front();
                    if (seg != null && seg.getSamples() != null && seg.getSamples().length > 0) {
                        out.add(seg.getSamples());
                    }
                    vad.pop();
                }
            }
            vad.flush();
            while (!vad.empty()) {
                SpeechSegment seg = vad.front();
                if (seg != null && seg.getSamples() != null && seg.getSamples().length > 0) {
                    out.add(seg.getSamples());
                }
                vad.pop();
            }
        } finally {
            vad.release();
        }
        return out;
    }

    /**
     * The Silero VAD model: the configured path, or the copy bundled in the jar. The bundled copy
     * is extracted to a temporary file once, because the native loader needs a real path.
     */
    private Path vadModel() {
        Path cached = vadModelPath;
        if (cached != null) return cached;
        synchronized (this) {
            if (vadModelPath != null) return vadModelPath;
            String configured = properties.getVadModel();
            if (configured != null && !configured.isBlank()) {
                Path p = new File(configured).toPath();
                if (Files.isRegularFile(p)) {
                    vadModelPath = p;
                    return p;
                }
                log("configured VAD model not found: " + configured);
            }
            try (InputStream in = SttEngine.class.getResourceAsStream("/silero_vad.onnx")) {
                if (in == null) return null;
                Path tmp = Files.createTempFile("silero-vad-", ".onnx");
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                vadModelPath = tmp;
                return tmp;
            } catch (IOException e) {
                log("could not extract the bundled Silero VAD model: " + e.getMessage());
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ status

    /** The engine's status, in the shape the API and the voice page expect. */
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> models = new ArrayList<>(modelDirs.keySet());
        List<String> loaded;
        synchronized (this) {
            loaded = new ArrayList<>(recognizers.keySet());
        }
        boolean empty = modelDirs.isEmpty();
        out.put("ok", true);
        out.put("status", engineError != null ? "error" : (empty ? "no-models" : "ready"));
        if (engineError != null) {
            out.put("error", engineError);
        }
        out.put("model", empty ? "" : defaultModel);
        out.put("models", models);
        out.put("default_model", defaultModel);
        out.put("loaded", loaded);
        out.put("engine", empty ? "sherpa-onnx (Java), no models installed" : "sherpa-onnx (Java)");
        out.put("languages", List.of("en", "ta"));
        out.put("modelsDir", modelsDir());
        out.put("requests", requests);
        out.put("uptime_seconds", (System.currentTimeMillis() - startedAt) / 1000);
        return out;
    }

    /** How many transcriptions this engine has served. */
    public long requestCount() {
        return requests;
    }

    // ------------------------------------------------------------------ audio helpers

    /** Decoded mono PCM plus its rate. */
    static final class PcmData {
        float[] samples;
        int sampleRate;

        PcmData(float[] s, int r) { samples = s; sampleRate = r; }
    }

    /**
     * Whisper's answer when the clip does not match the language it was forced into (or contains
     * no speech): a short bracketed or starred stage direction such as
     * {@code (speaking in foreign language)}, {@code [music]}, {@code *gibberish*}.
     */
    static boolean isMetaOrEmpty(String text) {
        if (text.isEmpty()) return true;
        String t = text.trim();
        if (t.length() >= 80) return false;
        return (t.startsWith("(") && t.endsWith(")"))
                || (t.startsWith("[") && t.endsWith("]"))
                || (t.startsWith("*") && t.endsWith("*"));
    }

    /** Bracketed stage directions are not transcript — drop them. */
    static String stripMeta(String text) {
        String t = text.trim();
        return isMetaOrEmpty(t) ? "" : t;
    }

    /**
     * Windowed-sinc resampler. sherpa-onnx resamples internally too, but with a cheaper kernel;
     * for a 22050 -&gt; 16000 conversion the browser clips hit a non-integer ratio and the
     * difference is audible to the model. Converting here gives every clip a clean 16 kHz signal,
     * which measurably helps English accuracy.
     */
    static float[] resample(float[] input, int from, int to) {
        if (from == to || input.length == 0) return input;
        double ratio = (double) to / from;
        int outLen = (int) Math.ceil(input.length * ratio);
        float[] out = new float[outLen];
        final int taps = 16;                 // kernel half-width in input samples
        final double cutoff = 0.95;          // slightly below Nyquist to tame ringing
        double scale = cutoff * Math.min(1.0, ratio);
        for (int i = 0; i < outLen; i++) {
            double srcPos = i / ratio;
            int center = (int) Math.floor(srcPos);
            double frac = srcPos - center;
            double acc = 0, norm = 0;
            for (int k = -taps + 1; k <= taps; k++) {
                int idx = center + k;
                if (idx < 0 || idx >= input.length) continue;
                double x = (k - frac) * scale;
                double w = 0.5 * (1.0 + Math.cos(Math.PI * (k - frac) / taps)); // Hann window
                double sinc = Math.abs(x) < 1e-6 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double h = w * sinc;
                acc += input[idx] * h;
                norm += h;
            }
            out[i] = (float) (norm != 0 ? acc / norm : 0);
        }
        return out;
    }

    /** Root-mean-square of the samples — used to skip silence before decoding. */
    static double rms(float[] samples) {
        if (samples.length == 0) return 0;
        double sum = 0;
        for (float s : samples) sum += (double) s * s;
        return Math.sqrt(sum / samples.length);
    }

    /** Minimal RIFF/WAVE parser: PCM (format 1) and IEEE float (format 3), downmixed to mono. */
    static PcmData parseWav(byte[] b) {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
            throw new IllegalArgumentException("not a WAV file (missing RIFF/WAVE header)");
        }
        int pos = 12;
        int format = -1, channels = 0, sampleRate = 0, bits = 0;
        byte[] data = null;
        while (pos + 8 <= b.length) {
            String chunk = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = le32(b, pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > b.length) size = b.length - body;
            if ("fmt ".equals(chunk) && size >= 16) {
                format = le16(b, body);
                channels = le16(b, body + 2);
                sampleRate = le32(b, body + 4);
                bits = le16(b, body + 14);
            } else if ("data".equals(chunk)) {
                data = new byte[size];
                System.arraycopy(b, body, data, 0, size);
            }
            pos = body + size + (size % 2);   // chunks are word-aligned
        }
        if (format == -1 || data == null) throw new IllegalArgumentException("WAV missing fmt/data chunk");
        if (channels < 1) throw new IllegalArgumentException("WAV has zero channels");
        if (!(format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32))
                && !(format == 3 && bits == 32)) {
            throw new IllegalArgumentException("unsupported WAV encoding (format=" + format
                    + ", " + bits + "-bit); send PCM");
        }

        int bytesPerSample = bits / 8;
        int frames = data.length / (bytesPerSample * channels);
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            int base = f * bytesPerSample * channels;
            for (int c = 0; c < channels; c++) {
                int off = base + c * bytesPerSample;
                float v;
                if (format == 3) {
                    v = Float.intBitsToFloat(le32(data, off));
                } else if (bits == 8) {
                    v = ((data[off] & 0xFF) - 128) / 128f;
                } else if (bits == 16) {
                    v = le16(data, off) / 32768f;
                } else if (bits == 24) {
                    int s = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                            | ((data[off + 2] & 0xFF) << 16);
                    if ((s & 0x800000) != 0) s |= 0xFF000000;
                    v = s / 8388608f;
                } else {
                    v = le32(data, off) / 2147483648f;
                }
                sum += v;
            }
            out[f] = sum / channels;
        }
        return new PcmData(out, sampleRate);
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void log(String message) {
        System.out.println("[stt] " + message);
    }
}
