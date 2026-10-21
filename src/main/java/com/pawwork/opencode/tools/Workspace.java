package com.pawwork.opencode.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The directory the file tools are allowed to touch.
 *
 * <p>Resolved once at startup from {@code opencode.tools.workspace}, or — when that is blank — from
 * the process's own working directory, so the application is portable: drop the jar anywhere and the
 * agent's workspace follows it. The root is canonicalised at construction time, and every requested
 * path is resolved against it and re-checked, so {@code ..} segments and symlinks cannot escape.</p>
 */
public final class Workspace {

    private final Path root;

    public Workspace(String configuredRoot) {
        Path base = configuredRoot == null || configuredRoot.isBlank()
                ? Paths.get("").toAbsolutePath()
                : Paths.get(configuredRoot);
        this.root = canonicalise(base);
    }

    /** The canonical workspace root. */
    public Path root() {
        return root;
    }

    /**
     * Resolves a model-supplied path inside the workspace.
     *
     * @param requested absolute, or relative to the workspace root; blank means "the root"
     * @return the canonical path, guaranteed to be the root itself or a descendant of it
     * @throws SecurityException when the path escapes the workspace
     */
    public Path resolve(String requested) throws IOException {
        String raw = requested == null ? "" : requested.trim();
        if (raw.isEmpty() || ".".equals(raw) || "./".equals(raw)) {
            return root;
        }
        // Strip a leading slash so "/src" and "src" both mean "<root>/src"; absolute host paths
        // are never honoured.
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        Path candidate = root.resolve(raw).normalize();
        Path canonical = canonicalise(candidate);
        if (!canonical.equals(root) && !canonical.startsWith(root)) {
            throw new SecurityException("path '" + requested + "' is outside the workspace (" + root + ")");
        }
        return canonical;
    }

    /**
     * Canonicalises a path, tolerating one that does not exist yet (so {@code write} can create it):
     * the deepest existing ancestor is canonicalised and the missing tail re-appended.
     */
    private static Path canonicalise(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            try {
                return absolute.toRealPath();
            } catch (IOException ignored) {
                return absolute;
            }
        }
        Path parent = absolute.getParent();
        if (parent == null) {
            return absolute;
        }
        return canonicalise(parent).resolve(absolute.getFileName());
    }

    /** Display form: workspace-relative when inside, absolute otherwise. */
    public String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }
}
