package com.pawwork.opencode.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Finds a shell the {@code bash} tool can actually run commands with.
 *
 * <p>The tool used to hard-code {@code /bin/bash}, which does not exist on Windows — every shell
 * call there failed with "Cannot run program". This class probes the machine instead and reports
 * which shell it found, so the tool's description can tell the model what syntax to write.</p>
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

    private final String executable;
    private final Kind kind;
    private final List<String> prefix;

    ShellLauncher(String executable, Kind kind, List<String> prefix) {
        this.executable = executable;
        this.kind = kind;
        this.prefix = List.copyOf(prefix);
    }

    /**
     * Finds a usable shell, or {@code null} when the machine has none.
     *
     * @param override an explicit executable path; blank means "probe"
     */
    public static ShellLauncher discover(String override) {
        if (override != null && !override.isBlank()) {
            String trimmed = override.trim();
            return new ShellLauncher(trimmed, kindOf(trimmed), argumentsFor(kindOf(trimmed)));
        }
        if (isWindows()) {
            ShellLauncher bash = probeWindowsBash();
            if (bash != null) {
                return bash;
            }
            ShellLauncher cmd = probeWindows("cmd.exe", Kind.CMD);
            if (cmd != null) {
                return cmd;
            }
            ShellLauncher powershell = probeWindows("powershell.exe", Kind.POWERSHELL);
            if (powershell != null) {
                return powershell;
            }
            return null;
        }
        for (String candidate : List.of("/bin/bash", "/usr/bin/bash")) {
            if (executableAt(candidate)) {
                return new ShellLauncher(candidate, Kind.BASH, List.of("-c"));
            }
        }
        for (String candidate : List.of("/bin/sh", "/usr/bin/sh")) {
            if (executableAt(candidate)) {
                return new ShellLauncher(candidate, Kind.BASH, List.of("-c"));
            }
        }
        return null;
    }

    /** Finds a usable shell, or {@code null} when the machine has none. */
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

    private static boolean executableAt(String path) {
        try {
            return Files.isRegularFile(Paths.get(path)) && Files.isExecutable(Paths.get(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ShellLauncher probeWindowsBash() {
        for (String candidate : List.of("bash.exe", "bash")) {
            ShellLauncher found = probeWindows(candidate, Kind.BASH);
            if (found != null) {
                return found;
            }
        }
        for (String candidate : List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe")) {
            if (executableAt(candidate)) {
                return new ShellLauncher(candidate, Kind.BASH, List.of("-c"));
            }
        }
        return null;
    }

    private static ShellLauncher probeWindows(String name, Kind kind) {
        Path onPath = findOnPath(name);
        if (onPath != null) {
            return new ShellLauncher(onPath.toString(), kind, argumentsFor(kind));
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            Path candidate = Paths.get(systemRoot, "System32", name);
            if (executableAt(candidate.toString())) {
                return new ShellLauncher(candidate.toString(), kind, argumentsFor(kind));
            }
        }
        return null;
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

    /** The executable that will be run. */
    public String executable() {
        return executable;
    }

    /** The shell flavour. */
    public Kind kind() {
        return kind;
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
