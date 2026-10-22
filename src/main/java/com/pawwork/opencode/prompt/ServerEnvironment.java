package com.pawwork.opencode.prompt;

import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.tools.ShellLauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    private final String shell;

    public ServerEnvironment(OpenCodeProperties properties) {
        this.workingDirectory = Paths.get("").toAbsolutePath().normalize();
        this.workspaceRoot = resolveRoot(properties).toAbsolutePath().normalize();
        this.tempDirectory = Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize();
        this.os = normaliseOs(System.getProperty("os.name", "unknown"));
        this.gitRepository = Files.exists(workspaceRoot.resolve(".git"));
        this.shell = shellName(properties);
    }

    private static Path resolveRoot(OpenCodeProperties properties) {
        String configured = properties.getTools() == null ? null : properties.getTools().getWorkspace();
        if (configured == null || configured.isBlank()) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private static String shellName(OpenCodeProperties properties) {
        String configured = properties.getTools() == null ? null : properties.getTools().getShell().getExecutable();
        if (configured != null && !configured.isBlank()) {
            return ShellLauncher.kindOf(configured).label();
        }
        ShellLauncher launcher = ShellLauncher.discover();
        return launcher == null ? "none" : launcher.kind().label();
    }

    private static String normaliseOs(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
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

    /** The shell the {@code bash} tool runs commands with: {@code bash}, {@code sh}, {@code cmd}, {@code powershell}. */
    public String shell() {
        return shell;
    }

    /** The system prompt's environment block — the CLI's {@code <env>} section. */
    public String environmentBlock() {
        return "<env>\n"
                + "  Working directory: " + workingDirectory + "\n"
                + "  Workspace root folder: " + workspaceRoot + "\n"
                + "  Is directory a git repo: " + (gitRepository ? "yes" : "no") + "\n"
                + "  Platform: " + os + "\n"
                + "  Shell: " + shell + "\n"
                + "  Prefer " + tempDirectory + " over generic system temporary directories such as "
                + genericTempDirectory() + "; it is pre-created and approved for external access.\n"
                + "</env>";
    }

    /** The platform's conventional temporary directory, named only so the model does not use it by mistake. */
    private String genericTempDirectory() {
        return "windows".equals(os) ? "C:\\Temp" : "/tmp";
    }
}
