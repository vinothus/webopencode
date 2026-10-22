package com.pawwork.opencode.prompt;

import com.pawwork.opencode.config.OpenCodeProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the system prompt every conversation starts with.
 *
 * <p>The OpenCode CLI never sends a bare user message: it opens every session with an agent
 * preamble, the current date and an {@code <env>} block describing the machine, then layers the
 * repository's own instructions ({@code AGENTS.md}) on top. A model that only sees the user's
 * question has no idea what it is, where it is, or what the conventions of the tree are — which is
 * exactly the difference between an agent that explores and one that guesses.</p>
 *
 * <p>Everything here is optional: the prompt can be switched off, replaced by a file, or trimmed
 * down to just the environment block.</p>
 */
public final class AgentPrompt {

    /** The agent's role and house rules — the CLI's own preamble. */
    public static final String PREAMBLE = """
            You are an AI agent running in OpenCode, a coding agent harness. Help the user accomplish their goals using the tools you have available.
            - Responses are rendered as GitHub-flavored Markdown.
            - `<system-reminder>` blocks are harness instructions, not user-authored content. Read and follow them.
            - Prefer parallelizing independent tool calls.
            - Use clear file paths when referring to files.
            - Keep responses clear and concise, and avoid unnecessary technical jargon.
            - Keep changes consistent with the structure, naming, style, and patterns of the surrounding code.
            - Treat unfamiliar files or changes as potential user work and investigate before deleting or overwriting them.""";

    /** The instruction file the CLI reads from the project root. */
    public static final String AGENTS_FILE = "AGENTS.md";

    private final OpenCodeProperties properties;

    public AgentPrompt(OpenCodeProperties properties) {
        this.properties = properties;
    }

    /**
     * The default system message for a session.
     *
     * @param environment the machine facts, never {@code null}
     * @return the prompt text, or an empty string when the feature is switched off
     */
    public String systemText(ServerEnvironment environment) {
        if (!properties.getSystemPrompt().isEnabled()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        String custom = customPrompt();
        sb.append(custom == null || custom.isBlank() ? PREAMBLE : custom);

        sb.append("\n\nToday's date: ").append(today());

        if (properties.getSystemPrompt().isIncludeEnvironment()) {
            sb.append("\n\n").append(environment.environmentBlock());
        }

        String agents = agentsFile(environment);
        if (agents != null) {
            sb.append("\n\n").append(agents);
        }
        return sb.toString();
    }

    /** The prompt read from {@code opencode.system-prompt.file}, or {@code null} when unset. */
    private String customPrompt() {
        String file = properties.getSystemPrompt().getFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the configured system prompt file '" + file + "'", e);
        }
    }

    /**
     * The repository's own instructions, read from {@code AGENTS.md} in the workspace root.
     *
     * @return the file's text, or {@code null} when there is none
     */
    private String agentsFile(ServerEnvironment environment) {
        if (!properties.getSystemPrompt().isReadAgentsFile()) {
            return null;
        }
        Path file = environment.workspaceRoot().resolve(AGENTS_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (body.isEmpty()) {
                return null;
            }
            return "The following instructions come from " + AGENTS_FILE
                    + " in the project root and apply to every task in this repository:\n\n" + body;
        } catch (IOException e) {
            return null;
        }
    }

    /** Today's date, in the CLI's format. */
    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM d yyyy", Locale.ENGLISH));
    }
}
