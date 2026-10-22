package com.pawwork.opencode.prompt;

import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.tools.ShellLauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The facts about the machine the agent is running on.
 *
 * <p>The OpenCode CLI tells the model where it is before it does anything else — the working
 * directory, the workspace root, whether the tree is a git repository, the platform and the
 * temporary directory. Without that, a model asked to "list the files" has no idea which files it
 * means, and it guesses.</p>
 *
 * <p>This class collects those facts once, so the system prompt and the per-tool notes are built
 * from the same source of truth.</p>
 */
public final class ServerEnvironment {

    private final Path workspaceRoot;
    private final Path workingDirectory;
    private final Path tempDirectory;
    private final String os;
    private final boolean gitRepository;
    private final ShellLauncher launcher;

    public ServerEnvironment(OpenCodeProperties properties) {
        this(properties, ShellLauncher.discover(properties.getTools().getShell().getExecutable()));
    }

    /**
     * @param launcher the shell the {@code bash} tool runs commands with; may be {@code null}
     */
    public ServerEnvironment(OpenCodeProperties properties, ShellLauncher launcher) {
        this.workingDirectory = Paths.get("").toAbsolutePath().normalize();
        this.workspaceRoot = resolveRoot(properties).toAbsolutePath().normalize();
        this.tempDirectory = Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize();
        this.os = normaliseOs(System.getProperty("os.name", "unknown"));
        this.gitRepository = Files.exists(workspaceRoot.resolve(".git"));
        this.launcher = launcher;
    }

    private static Path resolveRoot(OpenCodeProperties properties) {
        String configured = properties.getTools() == null ? null : properties.getTools().getWorkspace();
        if (configured == null || configured.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private static String normaliseOs(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return "windows";
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return "macos";
        }
        if (lower.contains("nux") || lower.contains("nix") || lower.contains("aix")) {
            return "linux";
        }
        return lower.isBlank() ? "unknown" : lower;
    }

    /** The directory the agent may read and write. */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** The process's own working directory — where the agent starts when no {@code workdir} is given. */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /** The temporary directory the agent may use for scratch files. */
    public Path tempDirectory() {
        return tempDirectory;
    }

    /** A short platform name: {@code linux}, {@code windows}, {@code macos}. */
    public String os() {
        return os;
    }

    /** Whether the workspace root is a git repository. */
    public boolean gitRepository() {
        return gitRepository;
    }

    /** The shell the {@code bash} tool runs commands with, or {@code null} when there is none. */
    public ShellLauncher launcher() {
        return launcher;
    }

    /**
     * The shell the {@code bash} tool runs commands with: {@code bash}, {@code bash (Git Bash)},
     * {@code bash (WSL)}, {@code cmd}, {@code powershell} — or {@code none}.
     */
    public String shell() {
        if (launcher == null || !launcher.isUsable()) {
            return "none";
        }
        return launcher.label();
    }

    /**
     * What the shell expects to be written.
     *
     * <p>The OpenCode CLI's {@code bash} description assumes a POSIX shell, and models write POSIX
     * commands. That is right on Linux and on a machine with Git Bash, but on a machine with
     * neither, the shell is {@code cmd.exe} or PowerShell and {@code ls} does not exist. Saying so
     * is the difference between a tool that works and one that fails on every call.</p>
     */
    public String shellSyntaxHint() {
        if (launcher == null) {
            return "No shell is available on this machine, so the bash tool cannot run commands.";
        }
        if (!launcher.isUsable()) {
            return "The shell this server was pointed at (" + launcher.executable()
                    + ") cannot be run — " + launcher.unusableReason() + ".";
        }
        switch (launcher.kind()) {
            case CMD:
                return "This is the Windows command prompt: write Windows commands (dir, type, "
                        + "findstr, copy, move, del) — POSIX commands such as ls, cat or grep do "
                        + "not exist here. Chain commands with &&.";
            case POWERSHELL:
                return "This is PowerShell: write PowerShell commands (Get-ChildItem, Get-Content, "
                        + "Select-String). On Windows PowerShell 5.1 chain commands with ';' — '&&' "
                        + "is not supported there.";
            default:
                break;
        }
        switch (launcher.flavour()) {
            case GIT_BASH:
                return "Write POSIX shell commands (ls, cat, grep, find, git) — but a Windows path "
                        + "is not valid inside a command: use the MSYS form, so C:\\work\\app is "
                        + "/c/work/app.";
            case WSL:
                return "Write POSIX shell commands (ls, cat, grep, find, git) — but a Windows path "
                        + "is not valid inside a command: use the WSL form, so C:\\work\\app is "
                        + "/mnt/c/work/app.";
            default:
                return "Write POSIX shell commands (ls, cat, grep, find, git).";
        }
    }

    /**
     * A path as the shell itself will read it.
     *
     * <p>A Windows path handed to a POSIX shell on Windows is not a path: Git Bash wants
     * {@code /c/work/app} and WSL wants {@code /mnt/c/work/app}. Everywhere else the path is
     * already right.</p>
     */
    public String shellPath(Path path) {
        String value = path == null ? "" : path.toString();
        if (launcher == null || launcher.kind() != ShellLauncher.Kind.BASH
                || !"windows".equals(os) || value.length() < 3 || value.charAt(1) != ':') {
            // On Windows, Java may normalize forward slashes to backslashes; keep POSIX paths as-is
            return value;
        }
        String drive = value.substring(0, 1).toLowerCase(Locale.ROOT);
        String rest = value.substring(2).replace('\\', '/');
        return launcher.flavour() == ShellLauncher.Flavour.WSL
                ? "/mnt/" + drive + rest
                : "/" + drive + rest;
    }

    /** The system prompt's environment block — the CLI's {@code <env>} section. */
    public String environmentBlock() {
        return "<env>\n"
                + "  Working directory: " + workingDirectory + "\n"
                + "  Workspace root folder: " + workspaceRoot + "\n"
                + "  Is directory a git repo: " + (gitRepository ? "yes" : "no") + "\n"
                + "  Platform: " + os + "\n"
                + "  Shell: " + shell() + "\n"
                + "  Prefer " + tempDirectory + " over generic system temporary directories such as "
                + genericTempDirectory() + "; it is pre-created and approved for external access.\n"
                + "</env>";
    }

    /** The platform's conventional temporary directory, named only so the model does not use it by mistake. */
    private String genericTempDirectory() {
        return "windows".equals(os) ? "C:\\Temp" : "/tmp";
    }
}
