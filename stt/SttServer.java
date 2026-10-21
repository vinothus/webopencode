// EngTop speech-to-text service — Java + sherpa-onnx (JNI) + Whisper (multilingual int8).
//
// Replaces the Python faster-whisper sidecar. No Python involved: audio in, text out,
// English + Tamil, transcribed on this machine.
//
// Several Whisper sizes live under <stt.models.dir>/<model>/{encoder.int8.onnx,
// decoder.int8.onnx, tokens.txt} and are selectable per request (the UI ships a dropdown):
// small (fastest), medium (balanced), large-v3 (best quality, slowest). Recognizers are
// built lazily per (model, language), cached, and warmed in the background at startup.
//
//   GET  /api/audio/health                 -> {ok, status, model, models, languages, ...}
//   POST /api/audio/transcribe             -> multipart (fields "audio", optional "language",
//                                             optional "model") OR raw audio body
//        ?language=auto|en|ta              (query param, or X-Language header)
//        ?model=small|medium|large-v3      (query param, or X-Model header)
//
// The audio must be a WAV file (PCM 8/16/24/32-bit int or 32-bit float, any channel count
// and sample rate — sherpa-onnx resamples internally). The browser records PCM/WAV directly.
//
// Auto strategy: whisper's built-in language auto-detect is unreliable with sherpa-onnx
// (its tokens file carries no <|xx|> special tokens), so "auto" decodes as English first;
// when the model answers with a bracketed meta line ("[speaking in foreign language]",
// "[music]", ...) or nothing at all, the clip is re-decoded as Tamil. The two languages we
// serve have disjoint scripts, so this is deterministic in practice.
//
// Response: {ok:true, text, language, language_probability, duration, elapsed_ms, inference_ms}

import com.k2fsa.sherpa.onnx.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class SttServer {

    static final int PORT = Integer.getInteger("stt.port", 8092);
    static final long MAX_BYTES = 25L * 1024 * 1024;   // matches spring multipart cap (25 MB)

    // Models live in <models.dir>/<name>/{encoder.int8.onnx,decoder.int8.onnx,tokens.txt} and
    // appear in the UI dropdown. Whisper's language is baked into the config, so one recognizer
    // per (model, language); recognizers are built lazily, cached forever (RAM is plentiful) and
    // guarded by a class-wide lock that also serialises decodes (auto mode chains two passes).
    static final Map<String, String> MODEL_DIRS = new LinkedHashMap<>();       // name -> directory
    static final Map<String, OfflineRecognizer> RECOGNIZERS = new HashMap<>(); // "model:lang" -> recognizer
    static final ReentrantLock DECODE_LOCK = new ReentrantLock();
    static String DEFAULT_MODEL = "medium";
    static String MODELS_ROOT = "/home/opc/java-stt/models";
    static int THREADS = 8;
    static boolean VAD_ENABLED = false;
    static String VAD_MODEL = "/home/opc/java-stt/silero_vad.onnx";

    static volatile long requests = 0;
    static final long STARTED_AT = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        String modelsDir = System.getProperty("stt.models.dir", "/home/opc/java-stt/models");
        MODELS_ROOT = modelsDir;
        THREADS = Integer.getInteger("stt.threads", 8);
        VAD_ENABLED = Boolean.getBoolean("stt.vad");
        VAD_MODEL = System.getProperty("stt.vad.model", VAD_MODEL);
        scanModels(modelsDir);

        // No throw: a models-less service is still useful. It answers /health honestly and the
        // browser can install a model through /api/models/download. Transcribe returns a clear
        // 503 until a model exists.
        if (MODEL_DIRS.isEmpty()) {
            System.out.println("[stt] no models under " + modelsDir
                    + " — starting in model-less mode; install one from the UI (/api/models/download)");
        } else {
            DEFAULT_MODEL = System.getProperty("stt.default-model",
                    MODEL_DIRS.containsKey("medium") ? "medium" : MODEL_DIRS.keySet().iterator().next());
            if (!MODEL_DIRS.containsKey(DEFAULT_MODEL)) {
                System.out.println("[stt] warning: default model '" + DEFAULT_MODEL
                        + "' not found, falling back to " + MODEL_DIRS.keySet().iterator().next());
                DEFAULT_MODEL = MODEL_DIRS.keySet().iterator().next();
            }

            // preload the default model so the first request is fast, then warm the rest in the
            // background (large-v3 takes a while to load; requests arriving meanwhile just wait
            // on the same lock the warmer holds)
            for (String lang : new String[]{"en", "ta"}) recognizer(DEFAULT_MODEL, lang);
            Thread warmer = new Thread(() -> {
                for (String m : MODEL_DIRS.keySet()) {
                    for (String lang : new String[]{"en", "ta"}) {
                        try {
                            recognizer(m, lang);
                        } catch (Throwable t) {
                            System.out.println("[stt] FAILED to load " + m + "/" + lang + ": " + t);
                        }
                    }
                }
                System.out.println("[stt] all models warm: " + RECOGNIZERS.keySet());
            }, "stt-warmer");
            warmer.setDaemon(true);
            warmer.start();
        }

        String bind = System.getProperty("stt.bind", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/audio/health", SttServer::health);
        server.createContext("/api/audio/transcribe", SttServer::transcribe);
        server.createContext("/api/models", SttServer::modelsEndpoint);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[stt] shutting down");
            server.stop(0);
            RECOGNIZERS.values().forEach(r -> { try { r.release(); } catch (Throwable ignored) {} });
        }));
        server.start();
        System.out.println("[stt] listening on http://127.0.0.1:" + PORT
                + " (whisper int8, models=" + MODEL_DIRS.keySet() + ", default=" + DEFAULT_MODEL
                + ", " + THREADS + " threads)");
    }

    /** Rescan the models dir so a freshly downloaded model appears without a restart. */
    static synchronized void reloadModels() {
        MODEL_DIRS.clear();
        scanModels(MODELS_ROOT);
        System.out.println("[stt] rescan: models=" + MODEL_DIRS.keySet());
    }

    /** Discover models under the models dir: subdirs holding encoder/decoder/tokens files. */
    static void scanModels(String modelsDir) {
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
        for (File d : ordered) MODEL_DIRS.put(d.getName(), d.getAbsolutePath());
    }

    /** Lazily build + cache the recognizer for (model, language). */
    static OfflineRecognizer recognizer(String model, String lang) {
        String key = model + ":" + lang;
        OfflineRecognizer r = RECOGNIZERS.get(key);
        if (r != null) return r;
        synchronized (SttServer.class) {
            r = RECOGNIZERS.get(key);
            if (r == null) {
                String dir = MODEL_DIRS.get(model);
                long t = System.currentTimeMillis();
                System.out.println("[stt] loading " + key + " ...");
                r = buildRecognizer(dir + "/encoder.int8.onnx", dir + "/decoder.int8.onnx",
                        dir + "/tokens.txt", lang, THREADS);
                RECOGNIZERS.put(key, r);
                System.out.println("[stt] loaded " + key + " in " + (System.currentTimeMillis() - t) + " ms");
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

    // ------------------------------------------------------------- endpoints

    static void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"GET only\"}");
            return;
        }
        long up = (System.currentTimeMillis() - STARTED_AT) / 1000;
        StringBuilder models = new StringBuilder();
        for (String m : MODEL_DIRS.keySet()) {
            if (models.length() > 0) models.append(',');
            models.append('"').append(m).append('"');
        }
        StringBuilder loaded = new StringBuilder();
        synchronized (SttServer.class) {
            for (String k : RECOGNIZERS.keySet()) {
                if (loaded.length() > 0) loaded.append(',');
                loaded.append('"').append(k).append('"');
            }
        }
        String status = MODEL_DIRS.isEmpty() ? "no-models" : "ready";
        String engine = MODEL_DIRS.isEmpty()
                ? "sherpa-onnx (Java), no models installed"
                : "sherpa-onnx (Java)";
        send(ex, 200, "{\"ok\":true,\"status\":\"" + status + "\",\"model\":\""
                + (MODEL_DIRS.isEmpty() ? "" : DEFAULT_MODEL) + "\","
                + "\"models\":[" + models + "],\"default_model\":\"" + DEFAULT_MODEL + "\","
                + "\"loaded\":[" + loaded + "],"
                + "\"engine\":\"" + engine + "\",\"languages\":[\"en\",\"ta\"],"
                + "\"modelsDir\":\"" + jsonEscape(MODELS_ROOT) + "\","
                + "\"requests\":" + requests + ",\"uptime_seconds\":" + up + "}");
    }

    static void transcribe(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"POST only\"}");
            return;
        }
        if (MODEL_DIRS.isEmpty()) {
            send(ex, 503, "{\"ok\":false,\"error\":\"no speech-to-text model is installed yet — "
                    + "install one from the voice page (Models panel), then try again\"}");
            return;
        }
        long t0 = System.currentTimeMillis();

        String language = firstNonBlank(queryParam(ex, "language"),
                header(ex, "X-Language"), "auto");
        String model = firstNonBlank(queryParam(ex, "model"), header(ex, "X-Model"), DEFAULT_MODEL);
        if (!validLanguage(language)) {
            send(ex, 400, "{\"ok\":false,\"error\":\"language must be one of auto, en, ta\"}");
            return;
        }
        if (!MODEL_DIRS.containsKey(model)) {
            send(ex, 400, "{\"ok\":false,\"error\":\"unknown model '" + jsonEscape(model)
                    + "' — available: " + MODEL_DIRS.keySet() + "\"}");
            return;
        }

        byte[] body;
        try {
            body = readLimited(ex.getRequestBody(), MAX_BYTES);
        } catch (SizeLimitExceeded e) {
            send(ex, 413, "{\"ok\":false,\"error\":\"audio too large (cap " + MAX_BYTES + " bytes)\"}");
            return;
        }
        if (body.length == 0) {
            send(ex, 400, "{\"ok\":false,\"error\":\"empty request — send the audio as multipart "
                    + "field 'audio' or as a raw WAV body\"}");
            return;
        }

        byte[] wav = body;
        String contentType = firstNonBlank(header(ex, "Content-Type"), "");
        if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            String fieldLang = multipartTextField(body, contentType, "language");
            if (fieldLang != null && !fieldLang.isBlank()) language = fieldLang.trim();
            if (!validLanguage(language)) {
                send(ex, 400, "{\"ok\":false,\"error\":\"language must be one of auto, en, ta\"}");
                return;
            }
            String fieldModel = multipartTextField(body, contentType, "model");
            if (fieldModel != null && !fieldModel.isBlank()) model = fieldModel.trim();
            if (!MODEL_DIRS.containsKey(model)) {
                send(ex, 400, "{\"ok\":false,\"error\":\"unknown model '" + jsonEscape(model)
                        + "' — available: " + MODEL_DIRS.keySet() + "\"}");
                return;
            }
            wav = multipartField(body, contentType, "audio");
            if (wav == null || wav.length == 0) {
                send(ex, 400, "{\"ok\":false,\"error\":\"no audio in the multipart body "
                        + "(expected field 'audio')\"}");
                return;
            }
        }

        PcmData pcm;
        try {
            pcm = parseWav(wav);
        } catch (Exception e) {
            send(ex, 400, "{\"ok\":false,\"error\":\"could not read audio: "
                    + jsonEscape(e.getMessage()) + "\"}");
            return;
        }

        int origRate = pcm.sampleRate;
        pcm.sampleRate = TARGET_RATE;
        pcm.samples = resample(pcm.samples, origRate, TARGET_RATE);  // we do our own resampling

        // Optional Silero VAD: great for trimming long silence / splitting pauses, but it can
        // clip soft Tamil plosives, so it is off by default (stt.vad=true to enable).
        List<float[]> segments = VAD_ENABLED ? vadSegments(pcm.samples) : null;
        if (segments != null && segments.isEmpty()) {
            segments = null;   // VAD heard nothing — fall back to the whole clip
        }

        long inferMs = 0;
        String text, detected;
        if (rms(pcm.samples) < 0.005) {
            // effectively silent — whisper hallucinates on silence, so don't even decode
            text = "";
            detected = language.equals("auto") ? "en" : language;
        } else {
            long tInfer = System.currentTimeMillis();
            try {
                DECODE_LOCK.lock();
                try {
                    float[][] clips = segments == null ? new float[][]{pcm.samples}
                            : segments.toArray(new float[0][]);
                    if (language.equals("auto")) {
                        // pass 1: English over every VAD segment. Real English comes back as
                        // plain text; non-English speech as a bracketed/starred stage
                        // direction (or nothing) — then pass 2 re-decodes as Tamil.
                        StringBuilder en = new StringBuilder();
                        boolean enMeta = true;
                        for (float[] clip : clips) {
                            String t = decode(model, "en", clip).trim();
                            if (!isMetaOrEmpty(t)) { enMeta = false; en.append(t).append(' '); }
                        }
                        String enText = en.toString().trim();
                        if (enMeta || enText.isEmpty()) {
                            StringBuilder ta = new StringBuilder();
                            for (float[] clip : clips) {
                                ta.append(stripMeta(decode(model, "ta", clip).trim())).append(' ');
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
                            sb.append(stripMeta(decode(model, language, clip).trim())).append(' ');
                        }
                        text = sb.toString().trim();
                        detected = language;
                    }
                } finally {
                    DECODE_LOCK.unlock();
                }
            } catch (Throwable t) {
                t.printStackTrace();
                send(ex, 500, "{\"ok\":false,\"error\":\"transcription failed: "
                        + jsonEscape(String.valueOf(t.getMessage())) + "\"}");
                return;
            }
            inferMs = System.currentTimeMillis() - tInfer;
        }
        requests++;

        double duration = pcm.samples.length / (double) pcm.sampleRate;
        String bodyJson = "{\"ok\":true,\"text\":\"" + jsonEscape(text) + "\","
                + "\"language\":\"" + detected + "\","
                + "\"model\":\"" + model + "\","
                + "\"language_probability\":1.0,"
                + "\"duration\":" + String.format(Locale.ROOT, "%.2f", duration) + ","
                + "\"sample_rate\":" + pcm.sampleRate + ","
                + "\"elapsed_ms\":" + (System.currentTimeMillis() - t0) + ","
                + "\"inference_ms\":" + inferMs + "}";
        send(ex, 200, bodyJson);
    }

    /** Run one decode under the caller's lock. */
    static String decode(String model, String language, float[] samples) {
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
    static List<float[]> vadSegments(float[] samples) {
        List<float[]> out = new ArrayList<>();
        VadModelConfig cfg = VadModelConfig.builder()
                .setSileroVadModelConfig(SileroVadModelConfig.builder()
                        .setModel(VAD_MODEL).setThreshold(0.5f)
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
     * Whisper's answer when the clip does not match the language it was forced into (or
     * contains no speech): a short bracketed or starred stage direction such as
     * {@code (speaking in foreign language)}, {@code [music]}, {@code *gibberish*}, {@code *Song*}.
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

    // ------------------------------------------------------------- audio

    static final class PcmData {
        float[] samples; int sampleRate;
        PcmData(float[] s, int r) { samples = s; sampleRate = r; }
    }

    static final int TARGET_RATE = 16000;   // Whisper's native rate

    /**
     * Windowed-sinc resampler. sherpa-onnx resamples internally too, but with a cheaper
     * kernel; for a 22050 -> 16000 conversion the browser / Android / macOS clips hit a
     * non-integer ratio and the difference is audible to the model. Converting here gives
     * every clip a clean 16 kHz signal, which measurably helps English accuracy.
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

    // ------------------------------------------------------------- model management

    /**
     * One downloadable Whisper size, described for <em>both</em> remote layouts.
     *
     * <p>GitHub publishes a single {@code .tar.bz2} per model; Hugging Face publishes loose files
     * named {@code <name>-encoder.int8.onnx} and friends. We keep the facts for each so one
     * installer can serve either source.</p>
     */
    static final class ModelSpec {
        final String name;          // "small" — the output dir / dropdown value
        final String tarAsset;      // GitHub release file name
        final long archiveBytes;    // GitHub tar size — used for the free-space pre-flight
        final long installedBytes;  // approximate extracted size, shown in the UI
        final String tarRoot;       // top-level dir inside the GitHub tar
        final String hfRepo;        // Hugging Face repo holding the loose files
        final String hfPrefix;      // files there are <hfPrefix>-encoder.int8.onnx …

        ModelSpec(String name, String tarAsset, long archiveBytes, long installedBytes,
                  String tarRoot, String hfRepo, String hfPrefix) {
            this.name = name;
            this.tarAsset = tarAsset;
            this.archiveBytes = archiveBytes;
            this.installedBytes = installedBytes;
            this.tarRoot = tarRoot;
            this.hfRepo = hfRepo;
            this.hfPrefix = hfPrefix;
        }
    }

    // Sizes are the real release-asset sizes (checked against the GitHub API); installed sizes are
    // measured from the models already on disk. Peak disk during a GitHub install is roughly
    // archiveBytes + 2×installedBytes (the tar, plus the full extracted tree we then trim), so the
    // pre-flight in modelsDownload() accounts for that. Neither source needs an API key.
    static final List<ModelSpec> CATALOGUE = List.of(
            new ModelSpec("small", "sherpa-onnx-whisper-small.tar.bz2",
                    639_400_000L, 359_000_000L, "sherpa-onnx-whisper-small",
                    "csukuangfj/sherpa-onnx-whisper-small", "small"),
            new ModelSpec("medium", "sherpa-onnx-whisper-medium.tar.bz2",
                    1_931_400_000L, 903_000_000L, "sherpa-onnx-whisper-medium",
                    "csukuangfj/sherpa-onnx-whisper-medium", "medium"),
            new ModelSpec("large-v3", "sherpa-onnx-whisper-large-v3.tar.bz2",
                    1_068_500_000L, 1_700_000_000L, "sherpa-onnx-whisper-large-v3",
                    "csukuangfj/sherpa-onnx-whisper-large-v3", "large-v3"));

    /** GitHub: one tar per model. */
    static final String GITHUB_BASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";

    /** Hugging Face: loose int8 files per repo. */
    static String hfBase(ModelSpec spec) {
        return "https://huggingface.co/" + spec.hfRepo + "/resolve/main/";
    }

    /** Progress of at most one in-flight download, so the UI can poll it. */
    static final class DownloadState {
        volatile String model = null;
        volatile String source = null;
        volatile String phase = "idle";   // idle|downloading|extracting|done|error
        volatile long received = 0;
        volatile long total = 0;
        volatile String error = null;
        volatile long startedAt = 0;
    }
    static final DownloadState DOWNLOAD = new DownloadState();
    static final Object DOWNLOAD_LOCK = new Object();

    static void modelsEndpoint(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

        // GET  /api/models            → catalogue + installed
        // GET  /api/models/progress   → current download progress
        // POST /api/models/download   → start a download  (query: model, source)
        // POST /api/models/delete     → remove a model    (query: model)
        if (path.equals("/api/models") && method.equals("GET")) {
            modelsList(ex);
        } else if (path.equals("/api/models/progress") && method.equals("GET")) {
            modelsProgress(ex);
        } else if (path.equals("/api/models/download") && method.equals("POST")) {
            modelsDownload(ex);
        } else if (path.equals("/api/models/delete") && method.equals("POST")) {
            modelsDelete(ex);
        } else {
            send(ex, 404, "{\"ok\":false,\"error\":\"unknown models endpoint\"}");
        }
    }

    static void modelsList(HttpExchange ex) throws IOException {
        reloadModels();
        File root = new File(MODELS_ROOT);
        long free = root.getUsableSpace();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"modelsDir\":\"").append(jsonEscape(MODELS_ROOT)).append("\",");
        sb.append("\"freeBytes\":").append(free).append(',');
        sb.append("\"default\":\"").append(jsonEscape(DEFAULT_MODEL)).append("\",");
        sb.append("\"sources\":[");
        sb.append("{\"id\":\"github\",\"label\":\"GitHub releases (k2-fsa/sherpa-onnx)\"},");
        sb.append("{\"id\":\"huggingface\",\"label\":\"Hugging Face mirror (csukuangfj)\"},");
        sb.append("{\"id\":\"upload\",\"label\":\"Upload files already on disk\"}");
        sb.append("],\"available\":[");
        boolean first = true;
        for (ModelSpec spec : CATALOGUE) {
            if (!first) sb.append(',');
            first = false;
            boolean installed = MODEL_DIRS.containsKey(spec.name);
            sb.append("{\"name\":\"").append(spec.name).append("\",");
            sb.append("\"installed\":").append(installed).append(',');
            sb.append("\"archiveBytes\":").append(spec.archiveBytes).append(',');
            sb.append("\"installedBytes\":").append(spec.installedBytes).append(',');
            sb.append("\"fits\":").append(free > spec.archiveBytes + spec.installedBytes).append(',');
            sb.append("\"dir\":\"").append(jsonEscape(
                    new File(MODELS_ROOT, spec.name).getAbsolutePath())).append("\"}");
        }
        sb.append("],\"installed\":[");
        first = true;
        for (Map.Entry<String, String> e : MODEL_DIRS.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(jsonEscape(e.getKey())).append("\",");
            sb.append("\"dir\":\"").append(jsonEscape(e.getValue())).append("\",");
            sb.append("\"bytes\":").append(dirSize(new File(e.getValue()))).append('}');
        }
        sb.append("]}");
        send(ex, 200, sb.toString());
    }

    static void modelsProgress(HttpExchange ex) throws IOException {
        DownloadState d = DOWNLOAD;
        long pct = d.total > 0 ? (d.received * 100 / d.total) : 0;
        send(ex, 200, "{\"ok\":true,\"phase\":\"" + d.phase + "\","
                + "\"model\":\"" + jsonEscape(d.model == null ? "" : d.model) + "\","
                + "\"source\":\"" + jsonEscape(d.source == null ? "" : d.source) + "\","
                + "\"received\":" + d.received + ",\"total\":" + d.total + ","
                + "\"percent\":" + pct + ","
                + "\"elapsedMs\":" + (d.startedAt == 0 ? 0 : System.currentTimeMillis() - d.startedAt) + ","
                + "\"error\":\"" + jsonEscape(d.error == null ? "" : d.error) + "\"}");
    }

    static void modelsDownload(HttpExchange ex) throws IOException {
        String model = firstNonBlank(queryParam(ex, "model"), "");
        String source = firstNonBlank(queryParam(ex, "source"), "github");
        if (model == null || model.isBlank()) {
            send(ex, 400, "{\"ok\":false,\"error\":\"'model' is required\"}");
            return;
        }
        ModelSpec chosen = null;
        for (ModelSpec s : CATALOGUE) if (s.name.equals(model)) chosen = s;
        if (chosen == null) {
            send(ex, 400, "{\"ok\":false,\"error\":\"unknown model '" + jsonEscape(model)
                    + "' — available: small, medium, large-v3\"}");
            return;
        }
        final ModelSpec spec = chosen;
        final String src = source;

        synchronized (DOWNLOAD_LOCK) {
            if ("downloading".equals(DOWNLOAD.phase) || "extracting".equals(DOWNLOAD.phase)) {
                send(ex, 409, "{\"ok\":false,\"error\":\"a download is already in progress ("
                        + jsonEscape(DOWNLOAD.model) + ")\"}");
                return;
            }
            // Pre-flight: the tar, the full extracted tree (we trim it afterwards) and room to
            // spare. A GitHub install peaks near archiveBytes + 2×installedBytes.
            long need = "huggingface".equals(source)
                    ? spec.installedBytes
                    : spec.archiveBytes + (2 * spec.installedBytes);
            long free = new File(MODELS_ROOT).getUsableSpace();
            if (free < need) {
                send(ex, 507, "{\"ok\":false,\"error\":\"not enough disk: need about "
                        + (need / (1024 * 1024)) + " MB free, have " + (free / (1024 * 1024))
                        + " MB\"}");
                return;
            }
            DOWNLOAD.model = model;
            DOWNLOAD.source = source;
            DOWNLOAD.phase = "downloading";
            DOWNLOAD.received = 0;
            DOWNLOAD.total = spec.archiveBytes;
            DOWNLOAD.error = null;
            DOWNLOAD.startedAt = System.currentTimeMillis();
        }

        Thread worker = new Thread(() -> runDownload(spec, src), "stt-download");
        worker.setDaemon(true);
        worker.start();
        send(ex, 202, "{\"ok\":true,\"started\":true,\"model\":\"" + jsonEscape(model)
                + "\",\"source\":\"" + jsonEscape(source) + "\"}");
    }

    /** Download (with HTTP Range resume) and install. Runs off the request thread. */
    static void runDownload(ModelSpec spec, String source) {
        try {
            if (source.equals("huggingface")) {
                runDownloadHuggingFace(spec);
            } else {
                runDownloadGithub(spec);
            }
            reloadModels();
            DOWNLOAD.phase = "done";
            System.out.println("[stt] installed model '" + spec.name + "' from " + source);
            // Warm it in the background so the first real request is quick.
            if (MODEL_DIRS.containsKey(spec.name)) {
                Thread warm = new Thread(() -> {
                    for (String lang : new String[]{"en", "ta"}) {
                        try { recognizer(spec.name, lang); } catch (Throwable ignored) { }
                    }
                }, "stt-warm-" + spec.name);
                warm.setDaemon(true);
                warm.start();
            }
        } catch (Throwable t) {
            DOWNLOAD.phase = "error";
            DOWNLOAD.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            System.out.println("[stt] download failed: " + DOWNLOAD.error);
        }
    }

    /** GitHub path: one tar.bz2 → extract → rename the top dir to <name>. */
    static void runDownloadGithub(ModelSpec spec) throws Exception {
        File root = new File(MODELS_ROOT);
        root.mkdirs();
        File part = new File(root, spec.tarAsset + ".part");
        File finalDir = new File(root, spec.name);

        String url = GITHUB_BASE + spec.tarAsset;
        fetch(url, part, spec.archiveBytes);

        DOWNLOAD.phase = "extracting";
        File tmp = new File(root, ".extract-" + spec.name);
        deleteRecursively(tmp);
        tmp.mkdirs();
        List<String> cmd = List.of("tar", "-xjf", part.getAbsolutePath(),
                "-C", tmp.getAbsolutePath(), spec.tarRoot);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String tarOut = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("tar failed (" + rc + "): " + tarOut.trim());

        File extracted = new File(tmp, spec.tarRoot);
        if (!extracted.isDirectory()) {
            throw new IOException("archive did not contain " + spec.tarRoot);
        }
        // The tar ships <prefix>-encoder.int8.onnx (and a large fp32 copy we do not need).
        // Stage only the three files the recognizer reads, under their unprefixed names.
        File staging = new File(tmp, ".final");
        staging.mkdirs();
        String[] wanted = {"encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"};
        for (String target : wanted) {
            File src = new File(extracted, spec.hfPrefix + "-" + target);
            if (!src.isFile()) src = new File(extracted, target);   // tolerate either layout
            if (!src.isFile()) {
                throw new IOException("archive is missing " + spec.hfPrefix + "-" + target);
            }
            if (!src.renameTo(new File(staging, target))) {
                java.nio.file.Files.copy(src.toPath(), new File(staging, target).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        deleteRecursively(finalDir);
        if (!staging.renameTo(finalDir)) {
            copyRecursively(staging.toPath(), finalDir.toPath());
            deleteRecursively(staging);
        }
        deleteRecursively(tmp);
        part.delete();
    }

    /**
     * Hugging Face path: three loose files, renamed on the way in. There is no tar to resume, so
     * each file restarts if interrupted — but a completed file is kept and skipped on a retry.
     */
    static void runDownloadHuggingFace(ModelSpec spec) throws Exception {
        File root = new File(MODELS_ROOT);
        root.mkdirs();
        File finalDir = new File(root, spec.name);
        File staging = new File(root, ".hf-" + spec.name);
        deleteRecursively(staging);
        staging.mkdirs();

        String base = hfBase(spec);
        // remote file name → local name
        Map<String, String> files = new LinkedHashMap<>();
        files.put(spec.hfPrefix + "-encoder.int8.onnx", "encoder.int8.onnx");
        files.put(spec.hfPrefix + "-decoder.int8.onnx", "decoder.int8.onnx");
        files.put(spec.hfPrefix + "-tokens.txt", "tokens.txt");

        long expected = spec.installedBytes;
        DOWNLOAD.total = expected;
        DOWNLOAD.received = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            File dest = new File(staging, e.getValue());
            if (dest.isFile() && dest.length() > 0) {
                DOWNLOAD.received += dest.length();
                continue;   // already fetched on an earlier attempt
            }
            File part = new File(staging, e.getValue() + ".part");
            fetch(base + e.getKey(), part, -1);
            if (!part.renameTo(dest)) {
                throw new IOException("could not finalise " + e.getValue());
            }
            DOWNLOAD.received += dest.length();
        }
        if (!new File(staging, "encoder.int8.onnx").isFile()
                || !new File(staging, "decoder.int8.onnx").isFile()
                || !new File(staging, "tokens.txt").isFile()) {
            throw new IOException("Hugging Face did not provide all three model files");
        }
        deleteRecursively(finalDir);
        if (!staging.renameTo(finalDir)) {
            copyRecursively(staging.toPath(), finalDir.toPath());
            deleteRecursively(staging);
        }
    }

    /**
     * GET {@code url} into {@code dest}, resuming from the bytes already present when the server
     * honours a Range request. {@code expectedBytes} &lt; 0 means "unknown" (no pre-flight check).
     */
    static void fetch(String url, File dest, long expectedBytes) throws Exception {
        long already = dest.isFile() ? dest.length() : 0;
        if (already > 0) {
            System.out.println("[stt] resuming " + dest.getName() + " at " + already + " bytes");
        }
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
        java.net.http.HttpRequest.Builder rb =
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofHours(2))
                        .header("User-Agent", "pawwork-opencode-spring/1.0");
        if (already > 0) rb.header("Range", "bytes=" + already + "-");

        java.net.http.HttpResponse<java.io.InputStream> resp = client.send(rb.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code != 200 && code != 206) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        boolean append = code == 206;
        if (!append) already = 0;

        long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1);
        if (expectedBytes > 0) {
            DOWNLOAD.total = already + (contentLength > 0 ? contentLength : expectedBytes - already);
        } else if (contentLength > 0) {
            DOWNLOAD.total = DOWNLOAD.received + already + contentLength;
        }
        DOWNLOAD.received = DOWNLOAD.received - (append ? 0 : already) + already;

        try (java.io.InputStream in = resp.body();
             java.io.OutputStream out = new java.io.FileOutputStream(dest, append)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                DOWNLOAD.received += n;
            }
        }
    }

    static void modelsDelete(HttpExchange ex) throws IOException {
        String model = firstNonBlank(queryParam(ex, "model"), "");
        if (model == null || model.isBlank()) {
            send(ex, 400, "{\"ok\":false,\"error\":\"'model' is required\"}");
            return;
        }
        synchronized (DOWNLOAD_LOCK) {
            if ("downloading".equals(DOWNLOAD.phase) || "extracting".equals(DOWNLOAD.phase)) {
                send(ex, 409, "{\"ok\":false,\"error\":\"a download is in progress\"}");
                return;
            }
        }
        String dir = MODEL_DIRS.get(model);
        if (dir == null) {
            // Allow deleting a half-present directory too, as long as it is inside the models root.
            File candidate = new File(MODELS_ROOT, model);
            if (!candidate.isDirectory()) {
                send(ex, 404, "{\"ok\":false,\"error\":\"model '" + jsonEscape(model) + "' is not installed\"}");
                return;
            }
            dir = candidate.getAbsolutePath();
        }
        // Never delete outside the models root.
        File target = new File(dir).getCanonicalFile();
        File rootC = new File(MODELS_ROOT).getCanonicalFile();
        if (!target.toPath().startsWith(rootC.toPath())) {
            send(ex, 403, "{\"ok\":false,\"error\":\"refusing to delete outside the models directory\"}");
            return;
        }
        synchronized (SttServer.class) {
            for (String lang : new String[]{"en", "ta"}) {
                OfflineRecognizer r = RECOGNIZERS.remove(model + ":" + lang);
                if (r != null) { try { r.release(); } catch (Throwable ignored) { } }
            }
        }
        long freed = dirSize(target);
        deleteRecursively(target);
        reloadModels();
        send(ex, 200, "{\"ok\":true,\"deleted\":\"" + jsonEscape(model)
                + "\",\"freedBytes\":" + freed + "}");
    }

    static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] files = f.listFiles();
        if (files != null) for (File c : files) deleteRecursively(c);
        f.delete();
    }

    static void copyRecursively(java.nio.file.Path from, java.nio.file.Path to) throws IOException {
        java.nio.file.Files.walk(from).forEach(src -> {
            try {
                java.nio.file.Path dst = to.resolve(from.relativize(src).toString());
                if (java.nio.file.Files.isDirectory(src)) {
                    java.nio.file.Files.createDirectories(dst);
                } else {
                    java.nio.file.Files.createDirectories(dst.getParent());
                    java.nio.file.Files.copy(src, dst,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ------------------------------------------------------------- plumbing


    static final class SizeLimitExceeded extends Exception {}

    static byte[] readLimited(InputStream in, long cap) throws IOException, SizeLimitExceeded {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] tmp = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > cap) throw new SizeLimitExceeded();
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    /** Extract a named part from a multipart/form-data body (flat, no nested boundaries). */
    static byte[] multipartField(byte[] body, String contentType, String field) {
        int[] range = multipartPartRange(body, contentType, field);
        if (range == null) return null;
        byte[] out = new byte[range[1] - range[0]];
        System.arraycopy(body, range[0], out, 0, out.length);
        return out;
    }

    static String multipartTextField(byte[] body, String contentType, String field) {
        int[] range = multipartPartRange(body, contentType, field);
        if (range == null) return null;
        return new String(body, range[0], range[1] - range[0], StandardCharsets.UTF_8);
    }

    static int[] multipartPartRange(byte[] body, String contentType, String field) {
        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                boundary = part.substring("boundary=".length()).replace("\"", "");
            }
        }
        if (boundary == null) return null;
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int next = indexOf(body, delim, pos + delim.length);
            if (next < 0) break;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                    pos + delim.length);
            if (headerEnd < 0 || headerEnd > next) { pos = next; continue; }
            String headers = new String(body, pos + delim.length, headerEnd - pos - delim.length,
                    StandardCharsets.ISO_8859_1);
            boolean nameMatches = false;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition")) {
                    for (String param : line.split(";")) {
                        param = param.trim();
                        if (param.toLowerCase(Locale.ROOT).startsWith("name=")
                                && param.substring(5).replace("\"", "").equals(field)) {
                            nameMatches = true;
                        }
                    }
                }
            }
            if (nameMatches) {
                int end = next;
                if (end >= 2 && body[end - 2] == '\r' && body[end - 1] == '\n') end -= 2;
                return new int[]{headerEnd + 4, end};
            }
            pos = next;
        }
        return null;
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq < 0 ? kv : kv.substring(0, eq);
            if (k.equals(name)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(kv.substring(eq + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    static boolean validLanguage(String language) {
        return language.equals("auto") || language.equals("en") || language.equals("ta");
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
