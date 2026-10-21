package com.pawwork.opencode.stt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs, lists and removes the Whisper models the engine decodes with.
 *
 * <p>Two sources are offered and neither needs an API key: GitHub release tarballs (downloaded
 * with HTTP Range resume, so an interrupted download continues) and the Hugging Face mirror (loose
 * int8 files). Only the three files the recogniser actually reads are kept — the full-precision
 * copies inside the tarball are discarded, so {@code small} lands at about 359 MB rather than
 * 1.3 GB.</p>
 *
 * <p>At most one download runs at a time; the UI polls {@link #progress()} while it is going.</p>
 */
public class SttModelManager {

    /**
     * One downloadable Whisper size, described for <em>both</em> remote layouts.
     *
     * <p>GitHub publishes a single {@code .tar.bz2} per model; Hugging Face publishes loose files
     * named {@code <name>-encoder.int8.onnx} and friends. We keep the facts for each so one
     * installer can serve either source.</p>
     */
    static final class ModelSpec {
        final String name;          // "small" — the output directory / dropdown value
        final String tarAsset;      // GitHub release file name
        final long archiveBytes;    // GitHub tar size — used for the free-space pre-flight
        final long installedBytes;  // approximate extracted size, shown in the UI
        final String tarRoot;       // top-level directory inside the GitHub tar
        final String hfRepo;        // Hugging Face repository holding the loose files
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
    // archiveBytes + 2x installedBytes (the tar, plus the full extracted tree we then trim), so
    // the pre-flight in startDownload() accounts for that.
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

    private final SttEngine engine;
    private final SttProperties properties;
    private final DownloadState download = new DownloadState();
    private final Object downloadLock = new Object();

    public SttModelManager(SttEngine engine, SttProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ queries

    /** The catalogue plus what is installed, plus the free space on the models volume. */
    public Map<String, Object> list() {
        engine.reloadModels();
        Map<String, String> installed = engine.installedModels();
        File root = new File(properties.getModelsDir());
        long free = usableSpace(root);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("modelsDir", engine.modelsDir());
        out.put("freeBytes", free);
        out.put("default", engine.defaultModel());

        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source("github", "GitHub releases (k2-fsa/sherpa-onnx)"));
        sources.add(source("huggingface", "Hugging Face mirror (csukuangfj)"));
        sources.add(source("upload", "Upload files already on disk"));
        out.put("sources", sources);

        List<Map<String, Object>> available = new ArrayList<>();
        for (ModelSpec spec : CATALOGUE) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", spec.name);
            entry.put("installed", installed.containsKey(spec.name));
            entry.put("archiveBytes", spec.archiveBytes);
            entry.put("installedBytes", spec.installedBytes);
            entry.put("fits", free > spec.archiveBytes + spec.installedBytes);
            entry.put("dir", new File(root, spec.name).getAbsolutePath());
            available.add(entry);
        }
        out.put("available", available);

        List<Map<String, Object>> installedList = new ArrayList<>();
        for (Map.Entry<String, String> e : installed.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", e.getKey());
            entry.put("dir", e.getValue());
            entry.put("bytes", dirSize(new File(e.getValue())));
            installedList.add(entry);
        }
        out.put("installed", installedList);
        return out;
    }

    /** The progress of the in-flight download. */
    public Map<String, Object> progress() {
        Map<String, Object> out = new LinkedHashMap<>();
        long pct = download.total > 0 ? (download.received * 100 / download.total) : 0;
        out.put("ok", true);
        out.put("phase", download.phase);
        out.put("model", download.model == null ? "" : download.model);
        out.put("source", download.source == null ? "" : download.source);
        out.put("received", download.received);
        out.put("total", download.total);
        out.put("percent", pct);
        out.put("elapsedMs", download.startedAt == 0 ? 0 : System.currentTimeMillis() - download.startedAt);
        out.put("error", download.error == null ? "" : download.error);
        return out;
    }

    // ------------------------------------------------------------------ actions

    /**
     * Start a background download.
     *
     * @throws SttException 400 for an unknown model, 409 when a download is already running,
     *                      507 when there is not enough free disk
     */
    public void startDownload(String model, String source) throws SttException {
        String name = model == null ? "" : model.trim();
        String from = (source == null || source.isBlank()) ? "github" : source.trim();
        if (name.isEmpty()) {
            throw new SttException(400, "'model' is required");
        }
        ModelSpec chosen = null;
        for (ModelSpec s : CATALOGUE) if (s.name.equals(name)) chosen = s;
        if (chosen == null) {
            throw new SttException(400, "unknown model '" + name + "' — available: small, medium, large-v3");
        }
        final ModelSpec spec = chosen;
        final String src = from;

        synchronized (downloadLock) {
            if ("downloading".equals(download.phase) || "extracting".equals(download.phase)) {
                throw new SttException(409, "a download is already in progress ("
                        + download.model + ")");
            }
            // Pre-flight: the tar, the full extracted tree (we trim it afterwards) and room to
            // spare. A GitHub install peaks near archiveBytes + 2x installedBytes.
            long need = "huggingface".equals(src)
                    ? spec.installedBytes
                    : spec.archiveBytes + (2 * spec.installedBytes);
            long free = usableSpace(new File(properties.getModelsDir()));
            if (free < need) {
                throw new SttException(507, "not enough disk: need about "
                        + (need / (1024 * 1024)) + " MB free, have " + (free / (1024 * 1024)) + " MB");
            }
            download.model = name;
            download.source = src;
            download.phase = "downloading";
            download.received = 0;
            download.total = spec.archiveBytes;
            download.error = null;
            download.startedAt = System.currentTimeMillis();
        }

        Thread worker = new Thread(() -> runDownload(spec, src), "stt-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** Download (with HTTP Range resume) and install. Runs off the request thread. */
    private void runDownload(ModelSpec spec, String source) {
        try {
            if ("huggingface".equals(source)) {
                runDownloadHuggingFace(spec);
            } else {
                runDownloadGithub(spec);
            }
            engine.reloadModels();
            download.phase = "done";
            System.out.println("[stt] installed model '" + spec.name + "' from " + source);
            // Warm it in the background so the first real request is quick.
            if (engine.installedModels().containsKey(spec.name)) {
                Thread warm = new Thread(() -> {
                    for (String lang : new String[]{"en", "ta"}) {
                        try { engine.recognizer(spec.name, lang); } catch (Throwable ignored) { }
                    }
                }, "stt-warm-" + spec.name);
                warm.setDaemon(true);
                warm.start();
            }
        } catch (Throwable t) {
            download.phase = "error";
            download.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            System.out.println("[stt] download failed: " + download.error);
        }
    }

    /** GitHub path: one tar.bz2 -&gt; extract -&gt; rename the top directory to &lt;name&gt;. */
    private void runDownloadGithub(ModelSpec spec) throws Exception {
        File root = new File(properties.getModelsDir());
        root.mkdirs();
        File part = new File(root, spec.tarAsset + ".part");
        File finalDir = new File(root, spec.name);

        fetch(GITHUB_BASE + spec.tarAsset, part, spec.archiveBytes);

        download.phase = "extracting";
        File tmp = new File(root, ".extract-" + spec.name);
        deleteRecursively(tmp);
        tmp.mkdirs();
        List<String> cmd = List.of("tar", "-xjf", part.getAbsolutePath(),
                "-C", tmp.getAbsolutePath(), spec.tarRoot);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String tarOut = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("tar failed (" + rc + "): " + tarOut.trim());

        File extracted = new File(tmp, spec.tarRoot);
        if (!extracted.isDirectory()) {
            throw new IOException("archive did not contain " + spec.tarRoot);
        }
        // The tar ships <prefix>-encoder.int8.onnx (and a large fp32 copy we do not need).
        // Stage only the three files the recogniser reads, under their unprefixed names.
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
                Files.copy(src.toPath(), new File(staging, target).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
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
    private void runDownloadHuggingFace(ModelSpec spec) throws Exception {
        File root = new File(properties.getModelsDir());
        root.mkdirs();
        File finalDir = new File(root, spec.name);
        File staging = new File(root, ".hf-" + spec.name);
        deleteRecursively(staging);
        staging.mkdirs();

        String base = "https://huggingface.co/" + spec.hfRepo + "/resolve/main/";
        Map<String, String> files = new LinkedHashMap<>();
        files.put(spec.hfPrefix + "-encoder.int8.onnx", "encoder.int8.onnx");
        files.put(spec.hfPrefix + "-decoder.int8.onnx", "decoder.int8.onnx");
        files.put(spec.hfPrefix + "-tokens.txt", "tokens.txt");

        // Ask for the sizes first, so the progress bar is exact rather than an estimate.
        long total = 0;
        for (String remote : files.keySet()) {
            long size = contentLength(base + remote);
            if (size > 0) total += size;
        }
        download.total = total > 0 ? total : spec.installedBytes;
        download.received = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            File dest = new File(staging, e.getValue());
            if (dest.isFile() && dest.length() > 0) {
                download.received += dest.length();
                continue;   // already fetched on an earlier attempt
            }
            File part = new File(staging, e.getValue() + ".part");
            fetch(base + e.getKey(), part, download.total);
            if (!part.renameTo(dest)) {
                throw new IOException("could not finalise " + e.getValue());
            }
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
     * The size of a remote file, or -1 when the server does not say.
     */
    private static long contentLength(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
            java.net.http.HttpResponse<Void> resp = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return resp.headers().firstValueAsLong("content-length").orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * GET {@code url} into {@code dest}, resuming from the bytes already present when the server
     * honours a Range request.
     *
     * <p>{@code expectedBytes} is the total size of the whole job when known (-1 when it is
     * not); it seeds the progress bar. The bytes read here are added to
     * {@link DownloadState#received}, which the caller resets before the job starts.</p>
     */
    private void fetch(String url, File dest, long expectedBytes) throws Exception {
        long already = dest.isFile() ? dest.length() : 0;
        if (already > 0) {
            System.out.println("[stt] resuming " + dest.getName() + " at " + already + " bytes");
        }
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        java.net.http.HttpRequest.Builder rb =
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(Duration.ofHours(2))
                        .header("User-Agent", "pawwork-opencode-spring/1.0");
        if (already > 0) rb.header("Range", "bytes=" + already + "-");

        java.net.http.HttpResponse<InputStream> resp = client.send(rb.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code != 200 && code != 206) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        boolean append = code == 206;
        if (!append) already = 0;

        long contentLength = resp.headers().firstValueAsLong("content-length").orElse(-1);
        if (expectedBytes > 0) {
            // The job's total is known up front — the bar shows one file's worth of progress.
            download.total = expectedBytes;
            download.received += already;
        } else if (contentLength > 0) {
            // Unknown total: count what we have and grow the bar with the stream.
            download.total = download.received + already + contentLength;
            download.received += already;
        }

        try (InputStream in = resp.body(); OutputStream out = new java.io.FileOutputStream(dest, append)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                download.received += n;
            }
        }
    }

    /** Remove an installed model and free its disk. */
    public Map<String, Object> delete(String model) throws SttException {
        String name = model == null ? "" : model.trim();
        if (name.isEmpty()) {
            throw new SttException(400, "'model' is required");
        }
        synchronized (downloadLock) {
            if ("downloading".equals(download.phase) || "extracting".equals(download.phase)) {
                throw new SttException(409, "a download is in progress");
            }
        }
        String root = engine.modelsDir();
        Map<String, String> installed = engine.installedModels();
        String dir = installed.get(name);
        if (dir == null) {
            // Allow deleting a half-present directory too, as long as it is inside the models root.
            File candidate = new File(root, name);
            if (!candidate.isDirectory()) {
                throw new SttException(404, "model '" + name + "' is not installed");
            }
            dir = candidate.getAbsolutePath();
        }
        // Never delete outside the models root.
        File target;
        File rootC;
        try {
            target = new File(dir).getCanonicalFile();
            rootC = new File(root).getCanonicalFile();
        } catch (IOException e) {
            throw new SttException(500, "could not resolve the model path: " + e.getMessage(), e);
        }
        if (!target.toPath().startsWith(rootC.toPath())) {
            throw new SttException(403, "refusing to delete outside the models directory");
        }
        long freed = dirSize(target);
        deleteRecursively(target);
        engine.reloadModels();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deleted", name);
        out.put("freedBytes", freed);
        return out;
    }

    // ------------------------------------------------------------------ plumbing

    private static Map<String, Object> source(String id, String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        return map;
    }

    /**
     * Free space on the volume the models live on. The directory itself may not exist yet — in
     * that case the nearest existing ancestor answers, because that is where the models will
     * land.
     */
    static long usableSpace(File dir) {
        File probe = dir.getAbsoluteFile();
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        return probe == null ? 0 : probe.getUsableSpace();
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

    static void copyRecursively(Path from, Path to) throws IOException {
        Files.walk(from).forEach(src -> {
            try {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
