package com.pawwork.opencode.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Finds a shell the {@code bash} tool can actually run commands with.
 *
 * <p>The tool used to hard-code {@code /bin/bash}, which does not exist on Windows — every shell
 * call there failed with "Cannot run program". This class probes the machine instead and reports
 * which shell it found, so the tool's description can tell the model what syntax to write.</p>
 *
 * <p>A shell is only accepted when it <em>runs</em>: every candidate is executed with a trivial
 * command and has to answer correctly. That matters on Windows, where {@code bash.exe} is often
 * the WSL launcher — a file that exists, prints "no distribution is installed" and exits with an
 * error. Accepting it would break every command instead of falling back to {@code cmd.exe}.</p>
 *
 * <p>Order of preference: a POSIX shell first (Git Bash or WSL on Windows), because models write
 * POSIX commands and the OpenCode CLI's {@code bash} contract assumes them; then {@code cmd.exe};
 * then PowerShell.</p>
 */
public final class ShellLauncher {

    /** The shell flavours this launcher knows how to drive. */
    public enum Kind {
        /** A POSIX shell — bash, or sh as a fallback. */
        BASH("bash"),
        /** Windows' {@code cmd.exe}. */
        CMD("cmd"),
        /** Windows PowerShell. */
        POWERSHELL("powershell");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** The name shown to the model and in the tool description. */
        public String label() {
            return label;
        }
    }

    /** Which shell answered the probe — POSIX shells on Windows are not interchangeable. */
    public enum Flavour {
        /** A POSIX shell on Linux or macOS. */
        POSIX,
        /** Git for Windows' bash (MSYS2) — Windows paths are {@code /c/...}. */
        GIT_BASH,
        /** The Windows Subsystem for Linux — Windows paths are {@code /mnt/c/...}. */
        WSL,
        /** A Windows shell that is not POSIX. */
        WINDOWS
    }

    /** How long a candidate has to answer the probe before it is rejected. */
    static final long PROBE_TIMEOUT_MS = 5_000L;

    /** Written by the probe command; its absence means the candidate did not really run. */
    static final String PROBE_MARKER = "opencode-probe";

    private final String executable;
    private final Kind kind;
    private final Flavour flavour;
    private final List<String> prefix;
    private final String unusableReason;

    ShellLauncher(String executable, Kind kind, Flavour flavour, List<String> prefix, String unusableReason) {
        this.executable = executable == null ? "" : executable;
        this.kind = kind;
        this.flavour = flavour;
        this.prefix = List.copyOf(prefix);
        this.unusableReason = unusableReason;
    }

    /**
     * Finds a shell to run commands with.
     *
     * <p>An explicit executable is used as given and is never silently replaced: if it cannot be
     * run, the returned launcher says why, so the tool can tell the operator instead of quietly
     * using a different shell than the one they asked for.</p>
     *
     * @param override an explicit executable path; blank means "probe the machine"
     * @return the shell to use, or {@code null} when the machine has none that works
     */
    public static ShellLauncher discover(String override) {
        if (override != null && !override.isBlank()) {
            String trimmed = override.trim();
            Kind kind = kindOf(trimmed);
            // The operator named this shell; say it does not work rather than using another one.
            return probe(trimmed, kind);
        }
        return discoverFirst(candidates());
    }

    /**
     * Tries the given shells in order and keeps the first one that works.
     *
     * <p>A candidate that exists but does not run — the WSL launcher on a machine with no
     * distribution, a broken symlink — is skipped, so the search carries on to the next one.</p>
     */
    static ShellLauncher discoverFirst(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            ShellLauncher launcher = probe(candidate.path, candidate.kind);
            if (launcher.isUsable()) {
                return launcher;
            }
        }
        return null;
    }

    /** Finds a shell to run commands with, or {@code null} when the machine has none that works. */
    public static ShellLauncher discover() {
        return discover(null);
    }

    /** The shell flavour an executable name implies. */
    public static Kind kindOf(String executable) {
        String name = executable == null ? "" : executable.toLowerCase(Locale.ROOT);
        if (name.contains("powershell") || name.contains("pwsh")) {
            return Kind.POWERSHELL;
        }
        if (name.endsWith(".cmd") || name.endsWith("cmd.exe") || name.contains("\\cmd")) {
            return Kind.CMD;
        }
        return Kind.BASH;
    }

    /** The arguments that precede the command for each shell flavour. */
    private static List<String> argumentsFor(Kind kind) {
        switch (kind) {
            case CMD:
                return List.of("/c");
            case POWERSHELL:
                return List.of("-NoProfile", "-Command");
            default:
                return List.of("-c");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ------------------------------------------------------------------ the candidates

    /** A shell worth trying: where it is and how it has to be driven. */
    record Candidate(String path, Kind kind) { }

    /**
     * The shells to try, best first.
     *
     * <p>On Windows the Git for Windows locations are tried before {@code bash.exe} on
     * {@code PATH}, because the one on {@code PATH} is often the WSL launcher: it runs a Linux
     * distribution where the workspace lives at {@code /mnt/c/...} rather than {@code C:\...}, and
     * a model that has been told the Windows path would then be working in the wrong place.</p>
     */
    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        if (isWindows()) {
            for (String path : List.of(
                    "C:\\Program Files\\Git\\bin\\bash.exe",
                    "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                    "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                    "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe")) {
                out.add(new Candidate(path, Kind.BASH));
            }
            addOnPath(out, "bash.exe", Kind.BASH);
            addOnPath(out, "bash", Kind.BASH);
            addOnPath(out, "cmd.exe", Kind.CMD);
            addInSystem32(out, "cmd.exe", Kind.CMD);
            addOnPath(out, "powershell.exe", Kind.POWERSHELL);
            addOnPath(out, "pwsh.exe", Kind.POWERSHELL);
            addInSystem32(out, "WindowsPowerShell\\v1.0\\powershell.exe", Kind.POWERSHELL);
            return out;
        }
        for (String path : List.of("/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash")) {
            out.add(new Candidate(path, Kind.BASH));
        }
        for (String path : List.of("/bin/sh", "/usr/bin/sh")) {
            out.add(new Candidate(path, Kind.BASH));
        }
        return out;
    }

    private static void addOnPath(List<Candidate> out, String name, Kind kind) {
        Path onPath = findOnPath(name);
        if (onPath != null) {
            out.add(new Candidate(onPath.toString(), kind));
        }
    }

    private static void addInSystem32(List<Candidate> out, String name, Kind kind) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            return;
        }
        Path candidate = Paths.get(systemRoot, "System32", name);
        if (Files.isRegularFile(candidate)) {
            out.add(new Candidate(candidate.toString(), kind));
        }
    }

    /** Searches {@code PATH} for an executable — the Windows equivalent of {@code which}. */
    private static Path findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(dir).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            } catch (RuntimeException ignored) {
                // an unusable PATH entry — skip it
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the probe

    /** The command a candidate has to run for it to count as a working shell. */
    static String probeScript(Kind kind) {
        switch (kind) {
            case CMD:
                return "echo " + PROBE_MARKER;
            case POWERSHELL:
                return "Write-Output " + PROBE_MARKER;
            default:
                // the second line tells Git Bash and WSL apart
                return "echo " + PROBE_MARKER + "; uname -s 2>/dev/null";
        }
    }

    /**
     * Runs a candidate and keeps it only if it really works.
     *
     * <p>Existence is not enough: on Windows {@code C:\\Windows\\System32\\bash.exe} is the WSL
     * launcher, which exists on every machine and fails on every machine without a distribution.
     * Accepting it would turn a working {@code cmd.exe} into a broken shell tool.</p>
     */
    static ShellLauncher probe(String executable, Kind kind) {
        return probe(executable, kind, PROBE_TIMEOUT_MS);
    }

    /**
     * Runs a candidate and keeps it only if it really works.
     *
     * <p>Existence is not enough: on Windows {@code C:\\Windows\\System32\\bash.exe} is the WSL
     * launcher, which exists on every machine and fails on every machine without a distribution.
     * Accepting it would turn a working {@code cmd.exe} into a broken shell tool.</p>
     */
    static ShellLauncher probe(String executable, Kind kind, long timeoutMs) {
        List<String> prefix = argumentsFor(kind);
        List<String> command = new ArrayList<>(prefix.size() + 2);
        command.add(executable);
        command.addAll(prefix);
        command.add(probeScript(kind));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            process.getOutputStream().close();   // no input: a shell that waits for it must not hang
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> drain(process.getInputStream(), output), "shell-probe-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return unusable(executable, kind, "it did not answer within "
                        + (timeoutMs / 1000) + " seconds");
            }
            reader.join(1_000);
            int exit = process.exitValue();
            if (exit != 0) {
                return unusable(executable, kind, "it exited with code " + exit);
            }
            if (!output.toString().contains(PROBE_MARKER)) {
                return unusable(executable, kind, "it did not run the probe command");
            }
            return new ShellLauncher(executable, kind, flavourOf(kind, output.toString()), prefix, null);
        } catch (IOException e) {
            return unusable(executable, kind, message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unusable(executable, kind, "the probe was interrupted");
        }
    }

    private static void drain(InputStream in, StringBuilder into) {
        try (InputStream stream = in) {
            byte[] buffer = new byte[4096];
            int read;
            while (into.length() < 8192 && (read = stream.read(buffer)) > 0) {
                into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the process ended with the stream
        }
    }

    private static String message(IOException e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? "it could not be started" : text;
    }

    private static ShellLauncher unusable(String executable, Kind kind, String reason) {
        return new ShellLauncher(executable, kind, Flavour.WINDOWS, argumentsFor(kind), reason);
    }

    /** Works out which shell answered the probe. */
    static Flavour flavourOf(Kind kind, String output) {
        return flavourOf(kind, output, isWindows());
    }

    /**
     * Works out which shell answered the probe.
     *
     * <p>Git Bash and WSL both answer {@code uname -s}, but with different words, and the two
     * disagree about what a Windows path looks like from the inside — which is exactly what the
     * model has to be told.</p>
     */
    static Flavour flavourOf(Kind kind, String output, boolean windows) {
        if (kind != Kind.BASH) {
            return Flavour.WINDOWS;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("msys") || lower.contains("mingw") || lower.contains("cygwin")) {
            return Flavour.GIT_BASH;
        }
        if (windows && lower.contains("linux")) {
            return Flavour.WSL;
        }
        return windows ? Flavour.GIT_BASH : Flavour.POSIX;
    }

    // ------------------------------------------------------------------ what the caller sees

    /** The executable that will be run. */
    public String executable() {
        return executable;
    }

    /** The shell flavour. */
    public Kind kind() {
        return kind;
    }

    /** Which shell answered the probe — a Git Bash and a WSL are not the same shell. */
    public Flavour flavour() {
        return flavour;
    }

    /** Whether commands can actually be run with this shell. */
    public boolean isUsable() {
        return unusableReason == null;
    }

    /** Why the shell cannot be used, or {@code null} when it can. */
    public String unusableReason() {
        return unusableReason;
    }

    /** The name shown to the model: {@code bash}, {@code bash (Git Bash)}, {@code cmd}, … */
    public String label() {
        switch (flavour) {
            case GIT_BASH:
                return Kind.BASH.label() + " (Git Bash)";
            case WSL:
                return Kind.BASH.label() + " (WSL)";
            default:
                return kind.label();
        }
    }

    /** The full command line for one script. */
    public List<String> commandLine(String script) {
        List<String> line = new ArrayList<>(prefix.size() + 2);
        line.add(executable);
        line.addAll(prefix);
        line.add(script);
        return line;
    }
}
