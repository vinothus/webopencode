package com.pawwork.opencode.prompt;

/**
 * The per-tool notes appended to the OpenCode CLI's tool descriptions.
 *
 * <p>The CLI's descriptions are the reason its agent behaves: they say when to use the shell, that
 * a file must be read before it is edited, that a plan is worth writing down for anything with
 * three steps. That guidance is only correct for a particular machine, so each note states the
 * facts that differ here — the workspace root, the shell, the shape of the JSON this server
 * returns.</p>
 *
 * <p>Without the notes the model would follow the CLI's description literally and expect the CLI's
 * raw-text results; the notes keep the description useful and the results predictable.</p>
 */
public final class ToolNotes {

    private final ServerEnvironment environment;

    public ToolNotes(ServerEnvironment environment) {
        this.environment = environment;
    }

    /**
     * The description a tool is advertised with: the CLI's own text, corrected for this machine,
     * plus the note for the tool.
     *
     * <p>The CLI's descriptions are written for the CLI's machine. Two statements in them are simply
     * wrong here — the line that names the operating system and the shell, and the line that names
     * the temporary directory. Left alone they would have the model writing POSIX commands for
     * {@code cmd.exe} and putting scratch files in a directory that does not exist.</p>
     *
     * @param toolName           the tool's name
     * @param officialDescription the CLI's description
     * @return the description to advertise
     */
    public String describe(String toolName, String officialDescription) {
        String tuned = tune(toolName, officialDescription == null ? "" : officialDescription);
        String note = note(toolName);
        if (note.isBlank()) {
            return tuned;
        }
        if (tuned.isBlank()) {
            return note;
        }
        return tuned + "\n\n" + note;
    }

    /** Corrects the machine-specific statements in the CLI's description. */
    private String tune(String toolName, String description) {
        if (!"bash".equals(toolName)) {
            return description;
        }
        String tuned = description.replace("Be aware: OS: linux, Shell: bash",
                "Be aware: OS: " + environment.os() + ", Shell: " + environment.shell());
        return tuned.replaceAll("Use `/tmp/opencode` for temporary work outside the workspace\\.\\s*"
                        + "This directory has already been created, already exists, and is pre-approved "
                        + "for external directory access\\.",
                "Use `" + environment.tempDirectory() + "` for temporary work outside the workspace.");
    }

    /**
     * The note for one tool.
     *
     * @param toolName the tool's name
     * @return the note, or an empty string when the tool needs none
     */
    public String note(String toolName) {
        if (toolName == null) {
            return "";
        }
        switch (toolName) {
            case "read":
                return "This server reads inside its workspace root (" + root() + "). A path is "
                        + "resolved against that root; anything outside it is refused. The result is "
                        + "a JSON object: {\"path\", \"lines\", \"from\", \"to\", \"truncated\", "
                        + "\"content\"}. The \"content\" field holds exactly the numbered "
                        + "\"<line>: <content>\" lines described above. A directory returns "
                        + "{\"path\", \"directory\":true, \"entries\":[...]} instead.";
            case "write":
                return "This server writes inside its workspace root (" + root() + "); a relative "
                        + "path is taken from that root. The result is a JSON object: {\"path\", "
                        + "\"created\", \"bytes\", \"lines\"}. Writing over a file that was not read "
                        + "first is allowed here.";
            case "edit":
                return "This server edits inside its workspace root (" + root() + "). The text to "
                        + "replace is \"oldString\" and its replacement \"newString\"; \"filePath\" "
                        + "names the file. The result is a JSON object: {\"path\", \"replacements\", "
                        + "\"bytes\"}.";
            case "glob":
                return "The pattern is matched against paths relative to the search root. The "
                        + "result is a JSON object: {\"pattern\", \"root\", \"count\", \"truncated\", "
                        + "\"files\":[...]}. At most 300 matches are returned.";
            case "grep":
                return "The pattern is a Java regular expression, searched under the workspace "
                        + "root (" + root() + "). The result is a JSON object: {\"pattern\", "
                        + "\"count\", \"truncated\", \"matches\":[{\"path\", \"line\", \"text\"}]}.";
            case "bash":
                return "This server runs commands with the " + environment.shell() + " shell on "
                        + environment.os() + ", in the workspace root (" + root() + ") unless "
                        + "\"workdir\" says otherwise. " + environment.shellSyntaxHint()
                        + " The result is a JSON object: {\"command\", "
                        + "\"shell\", \"workdir\", \"exitCode\", \"timedOut\", \"durationMs\", "
                        + "\"truncated\", \"output\"}; the combined stdout and stderr is in "
                        + "\"output\", capped at 2000 lines and 51200 bytes. Use "
                        + scratchDirectory() + " for scratch work outside the workspace.";
            case "webfetch":
                return "The result is a JSON object: {\"url\", \"status\", \"contentType\", "
                        + "\"format\", \"truncated\", \"content\"}.";
            case "skill":
                return "The result is a JSON object: {\"name\", \"description\", \"baseDirectory\", "
                        + "\"instructions\"}. The \"instructions\" field is the skill's text — follow "
                        + "it.";
            case "task":
                return "The sub-agent runs in the same workspace with the same tools. The result is "
                        + "a JSON object: {\"description\", \"answer\", \"durationMs\"}; the "
                        + "\"answer\" field is its final reply.";
            case "todowrite":
                return "The result is a JSON object: {\"updated\", \"inProgress\", \"pending\", "
                        + "\"completed\", \"cancelled\", \"todos\":[...]}. The list is replaced whole "
                        + "on every call, so send every item each time.";
            default:
                return "";
        }
    }

    private String root() {
        return environment.workspaceRoot().toString();
    }

    /**
     * The scratch directory, named the way the shell will read it.
     *
     * <p>On Windows the scratch directory is a Windows path, which a POSIX shell does not
     * understand — so both forms are given.</p>
     */
    private String scratchDirectory() {
        String java = environment.tempDirectory().toString();
        String shell = environment.shellPath(environment.tempDirectory());
        return java.equals(shell) ? java : java + " (in the shell: " + shell + ")";
    }
}
