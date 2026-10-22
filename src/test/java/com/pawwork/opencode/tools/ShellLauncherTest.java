package com.pawwork.opencode.tools;

import com.pawwork.opencode.config.OpenCodeProperties;
import com.pawwork.opencode.prompt.ServerEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a shell to run commands with.
 *
 * <p>The tool used to hard-code {@code /bin/bash}, which does not exist on Windows — every shell
 * call there died before it started. Now the machine is probed, and the kind of shell found decides
 * how the command is passed to it.</p>
 *
 * <p>Probing has to mean <em>running</em> the candidate, not finding the file. On Windows
 * {@code bash.exe} is usually the WSL launcher: it exists everywhere and fails everywhere there is
 * no distribution installed, and picking it would break every command instead of falling back to
 * {@code cmd.exe}.</p>
 */
class ShellLauncherTest {

    @Test
    void aShellIsFoundOnThisMachine() {
        ShellLauncher launcher = ShellLauncher.discover();

        // A machine without any shell is possible (a scratch container), but then the tool has to
        // say so rather than pretend.
        if (launcher != null) {
            assertThat(Path.of(launcher.executable())).exists();
            assertThat(launcher.kind()).isNotNull();
            assertThat(launcher.isUsable())
                    .as("a shell that was found has to be one that works")
                    .isTrue();
            assertThat(launcher.unusableReason()).isNull();
        }
    }

    @Test
    void anExplicitExecutableIsUsedAsGiven() {
        ShellLauncher launcher = ShellLauncher.discover("/opt/custom/shell");

        assertThat(launcher.executable()).isEqualTo("/opt/custom/shell");
        assertThat(launcher.commandLine("echo hi"))
                .containsExactly("/opt/custom/shell", "-c", "echo hi");
    }

    @Test
    void eachShellKindGetsItsOwnArguments() {
        assertThat(ShellLauncher.kindOf("C:\\Windows\\System32\\cmd.exe"))
                .isEqualTo(ShellLauncher.Kind.CMD);
        assertThat(ShellLauncher.kindOf("powershell.exe")).isEqualTo(ShellLauncher.Kind.POWERSHELL);
        assertThat(ShellLauncher.kindOf("pwsh")).isEqualTo(ShellLauncher.Kind.POWERSHELL);
        assertThat(ShellLauncher.kindOf("/bin/bash")).isEqualTo(ShellLauncher.Kind.BASH);
        assertThat(ShellLauncher.kindOf("/bin/sh")).isEqualTo(ShellLauncher.Kind.BASH);
    }

    @Test
    void commandLineIsTheExecutableThePrefixAndTheCommand() {
        ShellLauncher bash = ShellLauncher.discover("/bin/bash");
        ShellLauncher cmd = ShellLauncher.discover("cmd.exe");
        ShellLauncher powershell = ShellLauncher.discover("powershell.exe");

        assertThat(bash.commandLine("ls -la")).containsExactly("/bin/bash", "-c", "ls -la");
        assertThat(cmd.commandLine("dir")).containsExactly("cmd.exe", "/c", "dir");
        assertThat(powershell.commandLine("Get-ChildItem"))
                .containsExactly("powershell.exe", "-NoProfile", "-Command", "Get-ChildItem");
    }

    @Test
    void theKindIsNamedTheWayTheModelNeedsIt() {
        assertThat(ShellLauncher.Kind.BASH.label()).isEqualTo("bash");
        assertThat(ShellLauncher.Kind.CMD.label()).isEqualTo("cmd");
        assertThat(ShellLauncher.Kind.POWERSHELL.label()).isEqualTo("powershell");
    }

    @Test
    void aCommandRunsInTheShellThatWasFound() throws Exception {
        ShellLauncher launcher = ShellLauncher.discover();
        if (launcher == null || launcher.kind() != ShellLauncher.Kind.BASH) {
            return;   // nothing to prove on a machine without a POSIX shell
        }
        Process process = new ProcessBuilder(launcher.commandLine("echo shell-ok"))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).trim();

        assertThat(process.waitFor() == 0).isTrue();
        assertThat(output).isEqualTo("shell-ok");
    }

    @Test
    void theCommandIsNeverSplitIntoSeparateArguments() {
        ShellLauncher launcher = ShellLauncher.discover("/bin/bash");

        List<String> line = launcher.commandLine("echo \"one two\" && echo three");

        assertThat(line).hasSize(3);
        assertThat(line.get(2)).isEqualTo("echo \"one two\" && echo three");
    }

    // ------------------------------------------------------------------ the probe

    @Test
    void aFileThatExistsButDoesNotRunIsRejected(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("not-a-shell.sh");
        Files.writeString(fake, "#!/bin/sh\nexit 3\n");
        assertThat(fake.toFile().setExecutable(true)).isTrue();

        ShellLauncher launcher = ShellLauncher.probe(fake.toString(), ShellLauncher.Kind.BASH);

        assertThat(launcher.isUsable()).isFalse();
        assertThat(launcher.unusableReason()).isNotBlank();
        // On Unix the shell runs and exits with code 3; on Windows a non-executable script may fail to start
        String reason = launcher.unusableReason();
        assertThat(reason.contains("exited with code 3") || reason.contains("CreateProcess") || reason.contains("Cannot run"))
                .as("reason was: " + reason)
                .isTrue();
    }

    @Test
    void aShellThatNeverAnswersIsRejectedAndDoesNotHang(@TempDir Path dir) throws Exception {
        Path slow = dir.resolve("slow.sh");
        Files.writeString(slow, "#!/bin/sh\nsleep 30\n");
        assertThat(slow.toFile().setExecutable(true)).isTrue();

        long started = System.currentTimeMillis();
        ShellLauncher launcher = ShellLauncher.probe(slow.toString(), ShellLauncher.Kind.BASH, 1_000L);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(launcher.isUsable()).isFalse();
        String reason = launcher.unusableReason();
        assertThat(reason.contains("did not answer") || reason.contains("CreateProcess") || reason.contains("Cannot run"))
                .as("reason was: " + reason)
                .isTrue();
        assertThat(elapsed).isLessThan(15_000L);
    }

    @Test
    void aShellThatAnswersIsAccepted() {
        ShellLauncher launcher = ShellLauncher.probe("/bin/sh", ShellLauncher.Kind.BASH);

        // A machine without a POSIX shell proves nothing; a machine with one has to accept it.
        if (Files.isRegularFile(Path.of("/bin/sh"))) {
            assertThat(launcher.isUsable()).isTrue();
            assertThat(launcher.unusableReason()).isNull();
        }
    }

    @Test
    void aShellThatExistsButDoesNotRunIsSkippedAndTheNextOneIsUsed(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("bash.exe");
        Files.writeString(broken, "#!/bin/sh\necho 'no distribution is installed' >&2\nexit 1\n");
        assertThat(broken.toFile().setExecutable(true)).isTrue();
        if (!Files.isRegularFile(Path.of("/bin/sh"))) {
            return;   // nothing to fall back to on this machine
        }

        ShellLauncher launcher = ShellLauncher.discoverFirst(List.of(
                new ShellLauncher.Candidate(broken.toString(), ShellLauncher.Kind.BASH),
                new ShellLauncher.Candidate("/bin/sh", ShellLauncher.Kind.BASH)));

        assertThat(launcher).isNotNull();
        assertThat(launcher.executable()).isEqualTo("/bin/sh");
        assertThat(launcher.isUsable()).isTrue();
    }

    @Test
    void noShellAtAllIsReportedAsNoneRatherThanAsAFailure() {
        ShellLauncher launcher = ShellLauncher.discoverFirst(List.of(
                new ShellLauncher.Candidate("/definitely/not/a/shell", ShellLauncher.Kind.BASH)));

        assertThat(launcher).isNull();
    }

    @Test
    void eachShellIsProbedWithACommandItUnderstands() {
        assertThat(ShellLauncher.probeScript(ShellLauncher.Kind.BASH)).contains("echo " + ShellLauncher.PROBE_MARKER);
        assertThat(ShellLauncher.probeScript(ShellLauncher.Kind.CMD)).contains("echo " + ShellLauncher.PROBE_MARKER);
        assertThat(ShellLauncher.probeScript(ShellLauncher.Kind.POWERSHELL))
                .contains("Write-Output " + ShellLauncher.PROBE_MARKER);
    }

    @Test
    void gitBashAndWslAreToldApart() {
        assertThat(ShellLauncher.flavourOf(ShellLauncher.Kind.BASH, "opencode-probe\nMINGW64_NT-10.0", true))
                .isEqualTo(ShellLauncher.Flavour.GIT_BASH);
        assertThat(ShellLauncher.flavourOf(ShellLauncher.Kind.BASH, "opencode-probe\nLinux", true))
                .isEqualTo(ShellLauncher.Flavour.WSL);
        assertThat(ShellLauncher.flavourOf(ShellLauncher.Kind.BASH, "opencode-probe\nLinux", false))
                .isEqualTo(ShellLauncher.Flavour.POSIX);
        assertThat(ShellLauncher.flavourOf(ShellLauncher.Kind.CMD, "opencode-probe", true))
                .isEqualTo(ShellLauncher.Flavour.WINDOWS);
    }

    // ------------------------------------------------------------------ what the model is told

    @Test
    void aPosixShellIsDescribedAsOne() {
        ServerEnvironment environment = new ServerEnvironment(new OpenCodeProperties(),
                new ShellLauncher("/bin/bash", ShellLauncher.Kind.BASH, ShellLauncher.Flavour.POSIX,
                        List.of("-c"), null));

        assertThat(environment.shell()).isEqualTo("bash");
        assertThat(environment.shellSyntaxHint()).contains("POSIX shell commands");
    }

    @Test
    void theWindowsCommandPromptIsNotDescribedAsPosix() {
        ServerEnvironment environment = new ServerEnvironment(new OpenCodeProperties(),
                new ShellLauncher("cmd.exe", ShellLauncher.Kind.CMD, ShellLauncher.Flavour.WINDOWS,
                        List.of("/c"), null));

        assertThat(environment.shell()).isEqualTo("cmd");
        assertThat(environment.shellSyntaxHint())
                .contains("Windows command prompt")
                .contains("dir")
                .doesNotContain("POSIX shell commands");
    }

    @Test
    void powershellIsNotDescribedAsPosix() {
        ServerEnvironment environment = new ServerEnvironment(new OpenCodeProperties(),
                new ShellLauncher("powershell.exe", ShellLauncher.Kind.POWERSHELL,
                        ShellLauncher.Flavour.WINDOWS, List.of("-NoProfile", "-Command"), null));

        assertThat(environment.shell()).isEqualTo("powershell");
        assertThat(environment.shellSyntaxHint()).contains("PowerShell");
    }

    @Test
    void aShellThatCannotRunIsReportedRatherThanHidden() {
        ServerEnvironment environment = new ServerEnvironment(new OpenCodeProperties(),
                new ShellLauncher("C:\\Windows\\System32\\bash.exe", ShellLauncher.Kind.BASH,
                        ShellLauncher.Flavour.WINDOWS, List.of("-c"), "it exited with code 1"));

        assertThat(environment.shell()).isEqualTo("none");
        assertThat(environment.shellSyntaxHint()).contains("cannot be run");
    }

    @Test
    void aWindowsPathIsGivenInTheFormTheShellUnderstands() {
        String osName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            ServerEnvironment gitBash = new ServerEnvironment(new OpenCodeProperties(),
                    new ShellLauncher("C:\\Program Files\\Git\\bin\\bash.exe", ShellLauncher.Kind.BASH,
                            ShellLauncher.Flavour.GIT_BASH, List.of("-c"), null));
            ServerEnvironment wsl = new ServerEnvironment(new OpenCodeProperties(),
                    new ShellLauncher("C:\\Windows\\System32\\bash.exe", ShellLauncher.Kind.BASH,
                            ShellLauncher.Flavour.WSL, List.of("-c"), null));

            assertThat(gitBash.shellPath(Path.of("C:\\work\\app"))).isEqualTo("/c/work/app");
            assertThat(wsl.shellPath(Path.of("C:\\work\\app"))).isEqualTo("/mnt/c/work/app");
        } finally {
            System.setProperty("os.name", osName);
        }
    }

    @Test
    void aPosixPathIsLeftAlone() {
        ServerEnvironment environment = new ServerEnvironment(new OpenCodeProperties(),
                new ShellLauncher("/bin/bash", ShellLauncher.Kind.BASH, ShellLauncher.Flavour.POSIX,
                        List.of("-c"), null));

        assertThat(environment.shellPath(Path.of("/tmp/opencode")).replace('\\', '/')).isEqualTo("/tmp/opencode");
    }
}
