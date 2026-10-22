package com.pawwork.opencode.prompt;

import com.pawwork.opencode.config.OpenCodeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system prompt every conversation opens with.
 *
 * <p>The CLI never sends a bare user message: it opens with the agent's role, the date, an
 * {@code <env>} block and the repository's own instructions. That preamble is what tells the model
 * what it is, where it is and what the conventions of the tree are — without it, it guesses.</p>
 */
class AgentPromptTest {

    @Test
    void thePromptCarriesTheRoleTheDateAndTheEnvironment(@TempDir Path root) {
        String prompt = prompt(root);

        assertThat(prompt).contains("You are an AI agent running in OpenCode");
        assertThat(prompt).contains("Today's date: ");
        assertThat(prompt).contains("<env>");
        assertThat(prompt).contains("Working directory: ");
        assertThat(prompt).contains("Workspace root folder: " + root);
        assertThat(prompt).contains("Is directory a git repo: no");
        assertThat(prompt).contains("Platform: ");
        assertThat(prompt).contains("</env>");
    }

    @Test
    void theRepositorysOwnInstructionsAreAppended(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"),
                "Always use tabs.\nNever touch the generated folder.\n");

        String prompt = prompt(root);

        assertThat(prompt).contains("AGENTS.md");
        assertThat(prompt).contains("Always use tabs.");
        assertThat(prompt).contains("Never touch the generated folder.");
    }

    @Test
    void theEnvironmentCanBeLeftOut(@TempDir Path root) {
        OpenCodeProperties properties = properties(root);
        properties.getSystemPrompt().setIncludeEnvironment(false);

        String prompt = new AgentPrompt(properties).systemText(new ServerEnvironment(properties));

        assertThat(prompt).doesNotContain("<env>");
        assertThat(prompt).contains("You are an AI agent running in OpenCode");
    }

    @Test
    void theRepositoryInstructionsCanBeLeftOut(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "Always use tabs.");
        OpenCodeProperties properties = properties(root);
        properties.getSystemPrompt().setReadAgentsFile(false);

        String prompt = new AgentPrompt(properties).systemText(new ServerEnvironment(properties));

        assertThat(prompt).doesNotContain("Always use tabs.");
    }

    @Test
    void theWholePromptCanBeSwitchedOff(@TempDir Path root) {
        OpenCodeProperties properties = properties(root);
        properties.getSystemPrompt().setEnabled(false);

        assertThat(new AgentPrompt(properties).systemText(new ServerEnvironment(properties))).isEmpty();
    }

    @Test
    void aFileCanReplaceTheBuiltInPrompt(@TempDir Path root) throws Exception {
        Path file = root.resolve("prompt.md");
        Files.writeString(file, "You are a terse assistant.");
        OpenCodeProperties properties = properties(root);
        properties.getSystemPrompt().setFile(file.toString());

        String prompt = new AgentPrompt(properties).systemText(new ServerEnvironment(properties));

        assertThat(prompt).startsWith("You are a terse assistant.");
        assertThat(prompt).doesNotContain("You are an AI agent running in OpenCode");
        assertThat(prompt).contains("<env>");
    }

    private String prompt(Path root) {
        OpenCodeProperties properties = properties(root);
        return new AgentPrompt(properties).systemText(new ServerEnvironment(properties));
    }

    private OpenCodeProperties properties(Path root) {
        OpenCodeProperties properties = new OpenCodeProperties();
        properties.getTools().setWorkspace(root.toString());
        return properties;
    }
}
