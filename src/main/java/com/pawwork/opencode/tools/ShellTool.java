package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@code bash} tool: runs a shell command in the workspace, capturing stdout and stderr.
 *
 * <p>This is the tool that can do anything the application's user can, so it is marked as
 * always-requiring-confirmation — the UI prompts for it even in {@code auto} mode unless the host
 * explicitly opts out. Output is bounded in both lines and bytes, and the command is killed on
 * timeout.</p>
 *
 * <p>The shell is discovered rather than assumed. The tool used to run {@code /bin/bash}, which
 * does not exist on Windows — every command there died with "Cannot run program". Now a POSIX
 * shell is preferred where one exists (Git Bash or WSL), then {@code cmd.exe}, then PowerShell, and
 * the description tells the model which shell it is talking to.</p>
 */
public final class ShellTool {

    static final int MAX_OUTPUT_LINES = 2000;
    static final int MAX_OUTPUT_BYTES = 51_200;
    static final long DEFAULT_TIMEOUT_MS = 120_000L;
    static final long MAX_TIMEOUT_MS = 600_000L;

    private final Workspace workspace;
    private final ObjectMapper mapper;
    private final ShellLauncher launcher;

    public ShellTool(Workspace workspace, ObjectMapper mapper) {
        this(workspace, mapper, ShellLauncher.discover());
    }

    /**
     * @param launcher the shell to run commands with; {@code null} when the machine has none
     */
    public ShellTool(Workspace workspace, ObjectMapper mapper, ShellLauncher launcher) {
        this.workspace = workspace;
        this.mapper = mapper;
        this.launcher = launcher;
    }

    /** The shell this tool runs commands with, or {@code null} when there is none. */
    public ShellLauncher launcher() {
        return launcher;
    }

    public ToolHandler handler() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "bash";
            }

            @Override
            public String description() {
                String shell = launcher == null ? "shell" : launcher.label();
                return "Run a shell command and return its combined output. Working directory is the "
                        + "workspace root unless 'workdir' is given. Use for builds, tests, git and other "
                        + "terminal work — not for reading or editing files. Commands run with the "
                        + shell + ".";
            }

            @Override
            public JsonNode parameters() {
                ObjectNode properties = mapper.createObjectNode();
                ObjectNode command = mapper.createObjectNode();
                command.put("type", "string");
                command.put("description", "Shell command to execute.");
                properties.set("command", command);
                ObjectNode workdir = mapper.createObjectNode();
                workdir.put("type", "string");
                workdir.put("description", "Directory to run in, inside the workspace (default: root).");
                properties.set("workdir", workdir);
                ObjectNode timeout = mapper.createObjectNode();
                timeout.put("type", "integer");
                timeout.put("description", "Timeout in milliseconds (default " + DEFAULT_TIMEOUT_MS
                        + ", max " + MAX_TIMEOUT_MS + ").");
                properties.set("timeout", timeout);

                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", properties);
                schema.putArray("required").add("command");
                return schema;
            }

            @Override
            public String execute(JsonNode arguments) throws Exception {
                String command = arguments.path("command").asText("").trim();
                if (command.isEmpty()) {
                    return error("'command' is required");
                }
                if (launcher == null) {
                    return error("no shell found on this machine — install bash (or cmd/PowerShell), "
                            + "or set opencode.tools.shell.executable to the shell to use");
                }
                if (!launcher.isUsable()) {
                    return error("the shell " + launcher.executable() + " cannot be used: "
                            + launcher.unusableReason());
                }
                Path workdir;
                try {
                    workdir = workspace.resolve(arguments.path("workdir").asText(""));
                } catch (SecurityException e) {
                    return error(e.getMessage());
                }
                boolean workdirExists = java.nio.file.Files.isDirectory(workdir);

                long timeout = arguments.path("timeout").asLong(DEFAULT_TIMEOUT_MS);
                if (timeout <= 0) {
                    timeout = DEFAULT_TIMEOUT_MS;
                }
                timeout = Math.min(timeout, MAX_TIMEOUT_MS);

                long started = System.currentTimeMillis();

                // cmd.exe and PowerShell cannot take a quoted command inline — the shell gets a
                // generated script file instead. See ShellLauncher.invocation.
                ShellLauncher.Invocation invocation;
                try {
                    invocation = launcher.invocation(command);
                } catch (Exception e) {
                    return error("could not prepare the command for " + launcher.kind().label()
                            + ": " + e.getMessage());
                }

                int exit;
                boolean timedOut;
                boolean truncated;
                String output;
                try {
                    ProcessBuilder builder = new ProcessBuilder(invocation.command());
                    builder.directory(workdirExists ? workdir.toFile() : workspace.root().toFile());
                    builder.redirectErrorStream(true);

                    Process process = builder.start();
                    // stdin is ignored, as the CLI does: a command that reads it gets end-of-file
                    // instead of hanging until the timeout.
                    process.getOutputStream().close();

                    List<String> lines = new ArrayList<>();
                    int[] bytes = {0};
                    boolean[] cut = {false};
                    Thread reader = new Thread(() -> {
                        try (BufferedReader in = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (cut[0]) {
                                    continue;   // keep draining so the process never blocks on a full pipe
                                }
                                int separator = lines.isEmpty() ? 0 : 1;
                                if (lines.size() >= MAX_OUTPUT_LINES
                                        || bytes[0] + separator + utf8Length(line) > MAX_OUTPUT_BYTES) {
                                    // Keep the part of this line that still fits, then stop. Dropping
                                    // the line whole is what made long quoted strings disappear.
                                    int room = MAX_OUTPUT_BYTES - bytes[0] - separator;
                                    String head = cutToBytes(line, room);
                                    if (!head.isEmpty()) {
                                        lines.add(head);
                                        bytes[0] += separator + utf8Length(head);
                                    }
                                    cut[0] = true;
                                    continue;
                                }
                                bytes[0] += separator + utf8Length(line);
                                lines.add(line);
                            }
                        } catch (Exception ignored) {
                            // stream closed with the process
                        }
                    }, "tool-bash-reader");
                    reader.setDaemon(true);
                    reader.start();

                    boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                    reader.join(2_000);

                    exit = finished ? process.exitValue() : -1;
                    timedOut = !finished;
                    output = String.join("\n", lines);
                    truncated = cut[0];
                } catch (Exception e) {
                    return error("could not run " + launcher.executable() + ": " + e.getMessage());
                } finally {
                    invocation.close();
                }

                ObjectNode result = mapper.createObjectNode();
                result.put("command", command);
                result.put("shell", launcher.executable());
                result.put("workdir", workspace.display(workdirExists ? workdir : workspace.root()));
                result.put("exitCode", exit);
                result.put("timedOut", timedOut);
                result.put("durationMs", System.currentTimeMillis() - started);
                result.put("truncated", truncated);
                result.put("output", output);
                return result.toString();
            }
        };
    }

    /** UTF-8 size of a string, without allocating the byte array when it is plain ASCII. */
    static int utf8Length(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }
        return length;
    }

    /** The longest prefix of {@code text} that is at most {@code maxBytes} UTF-8 bytes. */
    static String cutToBytes(String text, int maxBytes) {
        if (text == null || maxBytes <= 0) {
            return "";
        }
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text.substring(0, i);
    }

    private String error(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", message);
        return node.toString();
    }
}
