package com.pawwork.opencode.prompt;

/**
 * Everything a chat turn needs to know about the deployment it runs in.
 *
 * <p>Two things, both of them the difference between an agent that works and one that guesses:</p>
 * <ul>
 *   <li>the default system message — the agent's role, the date, the {@code <env>} block and the
 *       repository's own instructions;</li>
 *   <li>the note appended to each tool description, saying how this deployment differs from the
 *       machine the OpenCode CLI's descriptions were written for.</li>
 * </ul>
 *
 * <p>Both are gathered here so the chat service has one small collaborator to hold rather than
 * three, and so a host can substitute its own profile without touching the loop.</p>
 */
public final class AgentProfile {

    private final ServerEnvironment environment;
    private final AgentPrompt prompt;
    private final ToolNotes notes;

    public AgentProfile(ServerEnvironment environment, AgentPrompt prompt, ToolNotes notes) {
        this.environment = environment;
        this.prompt = prompt;
        this.notes = notes;
    }

    /** The machine facts this profile describes the deployment with. */
    public ServerEnvironment environment() {
        return environment;
    }

    /**
     * The default system message.
     *
     * @return the prompt text, or an empty string when the feature is switched off
     */
    public String systemPrompt() {
        if (prompt == null || environment == null) {
            return "";
        }
        return prompt.systemText(environment);
    }

    /**
     * The description a tool is advertised with.
     *
     * @param toolName            the tool's name
     * @param officialDescription the CLI's description of it
     * @return the description to put on the wire
     */
    public String toolDescription(String toolName, String officialDescription) {
        return notes == null ? officialDescription : notes.describe(toolName, officialDescription);
    }
}
