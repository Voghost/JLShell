package com.jlshell.ui.support;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Centralised restart helper.
 *
 * <p>Provides two operations:
 * <ul>
 *   <li>{@link #scheduleRestart()} — launch a new process then exit the current one</li>
 *   <li>{@link #scheduleRestartAndThen(Runnable)} — same, but runs a callback before exit</li>
 * </ul>
 *
 * <p>The actual app shutdown (closing AppContext, removing tray icon, etc.)
 * is the caller's responsibility. This class only handles process relaunch
 * and JVM termination.</p>
 */
public final class RestartHelper {

    private RestartHelper() {}

    /**
     * Launch a new JLShell process, then terminate the current JVM.
     * Used when the caller has already performed its own cleanup.
     */
    public static void scheduleRestart() {
        scheduleRestartAndThen(() -> {});
    }

    /**
     * Launch a new JLShell process, run the given cleanup callback,
     * then terminate the current JVM.
     *
     * @param cleanup runs after the new process is started but before
     *                the current JVM halts (e.g. close AppContext,
     *                remove tray icon)
     */
    public static void scheduleRestartAndThen(Runnable cleanup) {
        launchNewProcess();
        try {
            cleanup.run();
        } finally {
            javafx.application.Platform.exit();
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * Start a new JVM process that re-launches JLShell.
     */
    private static void launchNewProcess() {
        try {
            List<String> command = buildRestartCommand();
            if (command == null || command.isEmpty()) {
                System.err.println("JLShell: cannot determine restart command, skipping relaunch");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(System.getProperty("user.dir", ".")));
            // Inherit both stdout and stderr so the new process output is visible
            pb.inheritIO();
            Process child = pb.start();
            // Brief wait to confirm the process started (don't block shutdown)
            boolean started = child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!started) {
                System.err.println("JLShell: new process did not start within 2 seconds");
            }
        } catch (Exception e) {
            System.err.println("JLShell: failed to launch restart process: " + e.getMessage());
        }
    }

    /**
     * Build the command list to restart the application.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try {@link ProcessHandle} to get the exact command and arguments</li>
     *   <li>Fall back to constructing from {@code java.home} + {@code jlshell.active.jar}</li>
     * </ol>
     */
    private static List<String> buildRestartCommand() {
        // Strategy 1: Use ProcessHandle
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> commandOpt = info.command();
        Optional<String[]> argsOpt = info.arguments();

        if (commandOpt.isPresent()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(commandOpt.get());
            argsOpt.ifPresent(args -> cmd.addAll(List.of(args)));
            // Validate: the command should be a java binary or a JLShell native launcher
            if (!cmd.isEmpty()) {
                return cmd;
            }
        }

        // Strategy 2: Construct from system properties
        String activeJar = System.getProperty("jlshell.active.jar", "");
        String javaHome = System.getProperty("java.home", "");

        if (activeJar.isBlank() || javaHome.isBlank()) {
            return null;
        }

        // Determine java executable path
        Path javaBin = Path.of(javaHome, "bin", "java");
        if (!javaBin.toFile().exists()) {
            javaBin = Path.of(javaHome, "bin", "java.exe");
        }
        if (!javaBin.toFile().exists()) {
            return null;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toAbsolutePath().toString());
        // Required JVM flags
        cmd.add("--add-opens");
        cmd.add("java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens");
        cmd.add("java.desktop/sun.awt=ALL-UNNAMED");
        cmd.add("-Xms64m");
        cmd.add("-Xmx512m");
        cmd.add("-XX:+ExplicitGCInvokesConcurrent");
        // Main class from the launcher
        cmd.add("-jar");
        cmd.add(Path.of(activeJar).toAbsolutePath().toString());

        return cmd;
    }
}
