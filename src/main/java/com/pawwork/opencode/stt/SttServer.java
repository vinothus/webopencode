package com.pawwork.opencode.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawwork.opencode.config.TrustStoreSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The speech-to-text service, standalone.
 *
 * <p>The engine normally runs <em>inside</em> the web application — one jar, one process, and
 * {@code POST /api/audio/transcribe} calls it directly. This launcher is the other option: the
 * same jar, started as a dedicated process that answers the same HTTP surface, for deployments
 * where the transcriber runs on the host and the web app reaches it over the network
 * ({@code stt.mode=remote}).</p>
 *
 * <pre>
 * java -cp target/opencode-spring-boot-1.0.0.jar \
 *      -Djava.library.path=lib \
 *      -Dstt.models.dir=./models -Dstt.bind=0.0.0.0 -Dstt.port=8092 \
 *      com.pawwork.opencode.stt.SttServer
 * </pre>
 */
public final class SttServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SttEngine engine;
    private final SttModelManager models;

    private SttServer(SttEngine engine, SttModelManager models) {
        this.engine = engine;
        this.models = models;
    }

    public static void main(String[] args) throws Exception {
        // A VPN that re-signs HTTPS breaks the model downloads; the truststore has to be set
        // before the first HTTP client is built.
        TrustStoreSupport.configure();

        SttProperties properties = new SttProperties();
        properties.setModelsDir(System.getProperty("stt.models.dir", properties.getModelsDir()));
        properties.setThreads(Integer.getInteger("stt.threads", properties.getThreads()));
        properties.setDefaultModel(System.getProperty("stt.default-model", properties.getDefaultModel()));
        properties.setVad(Boolean.getBoolean("stt.vad"));
        properties.setVadModel(System.getProperty("stt.vad.model", properties.getVadModel()));

        SttEngine engine = new SttEngine(properties);
        engine.start();
        SttModelManager models = new SttModelManager(engine, properties);

        String bind = System.getProperty("stt.bind", "127.0.0.1");
        int port = Integer.getInteger("stt.port", 8092);

        SttServer app = new SttServer(engine, models);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/audio/health", app::health);
        server.createContext("/api/audio/transcribe", app::transcribe);
        server.createContext("/api/models", app::models);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[stt] shutting down");
            server.stop(0);
            engine.close();
        }));
        server.start();
        System.out.println("[stt] listening on http://" + bind + ":" + port
                + " (whisper int8, models=" + engine.installedModels().keySet()
                + ", default=" + engine.defaultModel() + ", " + properties.getThreads() + " threads)");
    }

    // ------------------------------------------------------------------ endpoints

    private void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Map.of("ok", false, "error", "GET only"));
            return;
        }
        send(ex, 200, engine.health());
    }

    private void transcribe(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Map.of("ok", false, "error", "POST only"));
            return;
        }
        String language = firstNonBlank(queryParam(ex, "language"), header(ex, "X-Language"), "auto");
        String model = firstNonBlank(queryParam(ex, "model"), header(ex, "X-Model"), "");

        byte[] body;
        try {
            body = readLimited(ex.getRequestBody(), 25L * 1024 * 1024);
        } catch (SizeLimitExceeded e) {
            send(ex, 413, Map.of("ok", false, "error", "audio too large"));
            return;
        }
        if (body.length == 0) {
            send(ex, 400, Map.of("ok", false, "error",
                    "empty request — send the audio as multipart field 'audio' or as a raw WAV body"));
            return;
        }

        String contentType = firstNonBlank(header(ex, "Content-Type"), "");
        byte[] wav = body;
        if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            String fieldLang = multipartTextField(body, contentType, "language");
            if (fieldLang != null && !fieldLang.isBlank()) language = fieldLang.trim();
            String fieldModel = multipartTextField(body, contentType, "model");
            if (fieldModel != null && !fieldModel.isBlank()) model = fieldModel.trim();
            wav = multipartField(body, contentType, "audio");
            if (wav == null || wav.length == 0) {
                send(ex, 400, Map.of("ok", false, "error",
                        "no audio in the multipart body (expected field 'audio')"));
                return;
            }
        }

        try {
            SttTranscription result = engine.transcribe(wav, language, model);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true);
            out.put("text", result.text());
            out.put("language", result.language());
            out.put("model", result.model());
            out.put("language_probability", 1.0);
            out.put("duration", result.duration());
            out.put("sample_rate", result.sampleRate());
            out.put("elapsed_ms", result.elapsedMs());
            out.put("inference_ms", result.inferenceMs());
            send(ex, 200, out);
        } catch (SttException e) {
            send(ex, e.status(), Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    private void models(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        try {
            if (path.equals("/api/models") && method.equals("GET")) {
                send(ex, 200, models.list());
            } else if (path.equals("/api/models/progress") && method.equals("GET")) {
                send(ex, 200, models.progress());
            } else if (path.equals("/api/models/download") && method.equals("POST")) {
                models.startDownload(queryParam(ex, "model"),
                        firstNonBlank(queryParam(ex, "source"), "github"));
                send(ex, 202, Map.of("ok", true, "started", true,
                        "model", String.valueOf(queryParam(ex, "model"))));
            } else if (path.equals("/api/models/delete") && method.equals("POST")) {
                send(ex, 200, models.delete(queryParam(ex, "model")));
            } else {
                send(ex, 404, Map.of("ok", false, "error", "unknown models endpoint"));
            }
        } catch (SttException e) {
            send(ex, e.status(), Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static final class SizeLimitExceeded extends Exception { }

    private static byte[] readLimited(InputStream in, long cap) throws IOException, SizeLimitExceeded {
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

    static void send(HttpExchange ex, int status, Object json) throws IOException {
        byte[] out = MAPPER.writeValueAsBytes(json);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
