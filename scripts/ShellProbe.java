import com.pawwork.opencode.tools.ShellLauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A one-file probe for the shell the {@code bash} tool will use.
 *
 * <p>The shell used to be hard-coded to {@code /bin/bash}, which does not exist on Windows, so
 * every command there died before it started. The tool now finds a shell by itself — a POSIX one
 * (Git Bash or WSL) if the machine has one, otherwise {@code cmd.exe} or PowerShell — and this
 * probe reports what it found, using the very same code the app uses.</p>
 *
 * <p>{@code doctor.bat} runs it, so a Windows operator can see at a glance whether the shell
 * works. Run it by hand:</p>
 * <pre>
 * java -cp target\opencode-spring-boot-1.0.0.jar.original scripts\ShellProbe.java
 * java -cp target\opencode-spring-boot-1.0.0.jar.original scripts\ShellProbe.java C:\Program Files\Git\bin\bash.exe
 * </pre>
 *
 * <p>The classpath has to be the plain jar ({@code .jar.original}): the repackaged Spring Boot jar
 * keeps the application's own classes under {@code BOOT-INF\classes}, where a classpath cannot
 * see them.</p>
 *
 * <p>Single-file source mode, so it needs a JDK 11 or later and nothing has to be compiled
 * first.</p>
 */
public class ShellProbe {

    public static void main(String[] args) {
        String override = args.length > 0 && !args[0].isBlank() ? args[0] : null;

        ShellLauncher launcher = ShellLauncher.discover(override);
        if (launcher == null) {
            System.out.println("  [X] no shell found on this machine - the bash tool cannot run anything.");
            System.out.println("      Install Git for Windows (it brings bash), or set");
            System.out.println("      opencode.tools.shell.executable to the shell to use.");
            System.exit(1);
        }
        if (!launcher.isUsable()) {
            System.out.println("  [X] " + launcher.executable() + " cannot be used: "
                    + launcher.unusableReason());
            System.exit(1);
        }

        System.out.println("  [ok] " + launcher.label() + " -> " + launcher.executable());
        System.out.println("       flavour: " + launcher.flavour());

        String output = run(launcher);
        if (output == null) {
            System.out.println("  [X] the shell was found but a command did not run through it.");
            System.exit(1);
        }
        System.out.println("  [ok] a command ran: " + output.trim());
    }

    /** Runs a trivial command through the launcher and returns its output. */
    private static String run(ShellLauncher launcher) {
        String command = switch (launcher.kind()) {
            case CMD -> "echo shell-ok";
            case POWERSHELL -> "Write-Output shell-ok";
            default -> "echo shell-ok";
        };
        try {
            Process process = new ProcessBuilder(launcher.commandLine(command))
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
