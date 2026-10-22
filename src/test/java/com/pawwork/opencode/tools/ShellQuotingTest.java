package com.pawwork.opencode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quoted shell command has to reach the shell exactly as the model wrote it.
 *
 * <p>This is the regression suite for the report that “double-quoted shell strings get
 * truncated”. Two independent causes were found and are covered here:</p>
 * <ul>
 *   <li>the JVM escapes an embedded {@code "} as {@code \"}, which POSIX shells understand but
 *       {@code cmd.exe} and PowerShell do not — those two now run a generated script file;</li>
 *   <li>output accounting dropped a whole line when it did not fit the byte budget, so a long
 *       quoted value vanished instead of being shortened.</li>
 * </ul>
 */
class ShellQuotingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode run(ShellTool tool, String command) throws Exception {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("command", command);
        return mapper.readTree(tool.handler().execute(arguments));
    }

    /** The POSIX cases only. On a Windows-only machine they are skipped, not failed. */
    private ShellTool posixTool(Path dir) {
        ShellLauncher launcher = ShellLauncher.discover();
        Assumptions.assumeTrue(launcher != null && launcher.kind() == ShellLauncher.Kind.BASH,
                "these cases need a POSIX shell");
        return new ShellTool(new Workspace(dir.toString()), mapper, launcher);
    }

    // ------------------------------------------------------------------ inline quoting

    @Test
    void aDoubleQuotedArgumentSurvivesIntact(@TempDir Path dir) throws Exception {
        JsonNode result = run(posixTool(dir), "printf '%s\\n' \"hello world with spaces\"");

        assertThat(result.path("exitCode").asInt()).isZero();
        assertThat(result.path("output").asText()).isEqualTo("hello world with spaces");
        assertThat(result.path("truncated").asBoolean()).isFalse();
    }

    @Test
    void nestedQuotesAndApostrophesSurviveIntact(@TempDir Path dir) throws Exception {
        JsonNode result = run(posixTool(dir), "printf '%s\\n' \"it's a \\\"test\\\" of quoting\"");

        assertThat(result.path("exitCode").asInt()).isZero();
        assertThat(result.path("output").asText()).isEqualTo("it's a \"test\" of quoting");
    }

    @Test
    void theCommandIsEchoedBackByteForByte(@TempDir Path dir) throws Exception {
        String command = "printf '%s\\n' \"a \\\"b\\\" c\" | tr a-z A-Z";
        JsonNode result = run(posixTool(dir), command);

        assertThat(result.path("command").asText()).isEqualTo(command);
        assertThat(result.path("output").asText()).isEqualTo("A \"B\" C");
    }

    @Test
    void manyQuotedWordsAllReachTheCommand(@TempDir Path dir) throws Exception {
        JsonNode result = run(posixTool(dir),
                "printf '%s\\n' \"one two\" \"three four\" \"five six\"");

        assertThat(result.path("output").asText())
                .isEqualTo("one two\nthree four\nfive six");
    }

    // ------------------------------------------------------------------ output budget

    @Test
    void anOverlongLineIsShortenedNotDropped(@TempDir Path dir) throws Exception {
        // 60 KB on a single line: over MAX_OUTPUT_BYTES, so it used to be discarded entirely.
        JsonNode result = run(posixTool(dir), "head -c 60000 /dev/zero | tr '\\0' 'x'");

        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(result.path("output").asText())
                .hasSize(ShellTool.MAX_OUTPUT_BYTES)
                .startsWith("xxxx");
    }

    @Test
    void cutToBytesNeverSplitsACharacter(@TempDir Path dir) {
        String text = "aa\u20AC\uD83D\uDE00";   // 2 ASCII, one 3-byte, one surrogate pair (4 bytes)

        assertThat(ShellTool.cutToBytes(text, 4)).isEqualTo("aa");
        assertThat(ShellTool.cutToBytes(text, 5)).isEqualTo("aa\u20AC");
        assertThat(ShellTool.cutToBytes(text, 8)).isEqualTo("aa\u20AC");
        assertThat(ShellTool.cutToBytes(text, 9)).isEqualTo(text);
        assertThat(ShellTool.cutToBytes(text, 0)).isEmpty();
    }

    // ------------------------------------------------------- how each shell is invoked

    @Test
    void bashGetsTheCommandInline() {
        ShellLauncher launcher = ShellLauncher.discover();
        Assumptions.assumeTrue(launcher != null && launcher.kind() == ShellLauncher.Kind.BASH);

        try (ShellLauncher.Invocation invocation = launcher.invocation("echo \"hello world\"")) {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                // On Windows, Git Bash needs script files for reliable quoting
                assertThat(invocation.script()).isNotNull();
                assertThat(invocation.command()).contains(invocation.script().toString());
            } else {
                assertThat(invocation.script()).isNull();
                assertThat(invocation.command()).contains("-c", "echo \"hello world\"");
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void cmdRunsFromAGeneratedScriptFile() throws Exception {
        ShellLauncher launcher = new ShellLauncher("cmd.exe", ShellLauncher.Kind.CMD,
                ShellLauncher.Flavour.WINDOWS, List.of("/c"), null);

        try (ShellLauncher.Invocation invocation = launcher.invocation("echo \"hello world\"")) {
            assertThat(invocation.script()).isNotNull();
            assertThat(invocation.script().getFileName().toString()).endsWith(".bat");
            assertThat(invocation.command())
                    .startsWith("cmd.exe", "/d", "/s", "/c")
                    .doesNotContain("echo \"hello world\"")
                    .endsWith(invocation.script().toString());

            String body = Files.readString(invocation.script());
            assertThat(body).contains("@echo off").contains("echo \"hello world\"");
        }
    }

    @Test
    void powershellRunsFromAGeneratedScriptFile() throws Exception {
        ShellLauncher launcher = new ShellLauncher("powershell.exe", ShellLauncher.Kind.POWERSHELL,
                ShellLauncher.Flavour.WINDOWS, List.of("-NoProfile"), null);

        try (ShellLauncher.Invocation invocation = launcher.invocation("Write-Output \"hello world\"")) {
            assertThat(invocation.script()).isNotNull();
            assertThat(invocation.script().getFileName().toString()).endsWith(".ps1");
            assertThat(invocation.command())
                    .contains("-NoProfile", "-NonInteractive", "-File")
                    .doesNotContain("Write-Output \"hello world\"")
                    .endsWith(invocation.script().toString());

            byte[] body = Files.readAllBytes(invocation.script());
            // PowerShell 5.1 reads a script as ANSI unless it starts with a UTF-8 BOM.
            assertThat(body[0] & 0xFF).isEqualTo(0xEF);
            assertThat(body[1] & 0xFF).isEqualTo(0xBB);
            assertThat(body[2] & 0xFF).isEqualTo(0xBF);
            assertThat(new String(body, 3, body.length - 3, StandardCharsets.UTF_8))
                    .isEqualTo("Write-Output \"hello world\"");
        }
    }

    @Test
    void theGeneratedScriptIsRemovedAfterwards() throws Exception {
        ShellLauncher launcher = new ShellLauncher("cmd.exe", ShellLauncher.Kind.CMD,
                ShellLauncher.Flavour.WINDOWS, List.of("/c"), null);

        Path script;
        try (ShellLauncher.Invocation invocation = launcher.invocation("echo hi")) {
            script = invocation.script();
            assertThat(script).exists();
        }
        assertThat(script).doesNotExist();
    }
}
