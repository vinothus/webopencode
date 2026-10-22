package com.pawwork.opencode.tools;

import org.junit.jupiter.api.Test;

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
        Path script = Files.createTempFile("shell-launcher", ".sh");
        Process process = new ProcessBuilder(launcher.commandLine("echo shell-ok"))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).trim();

        assertThat(process.waitFor() == 0).isTrue();
        assertThat(output).isEqualTo("shell-ok");
        Files.deleteIfExists(script);
    }

    @Test
    void theCommandIsNeverSplitIntoSeparateArguments() {
        ShellLauncher launcher = ShellLauncher.discover("/bin/bash");

        List<String> line = launcher.commandLine("echo \"one two\" && echo three");

        assertThat(line).hasSize(3);
        assertThat(line.get(2)).isEqualTo("echo \"one two\" && echo three");
    }
}
