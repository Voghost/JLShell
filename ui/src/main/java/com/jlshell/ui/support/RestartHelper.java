package com.jlshell.ui.support;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                throw new IllegalStateException("Cannot determine the JLShell restart command");
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(System.getProperty("user.dir", ".")));
            // Inherit both stdout and stderr so the new process output is visible
            pb.inheritIO();
            Process child = pb.start();
            System.err.println("JLShell: restart process started, pid=" + child.pid());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the JLShell launcher", e);
        }
    }

    /**
     * Build the command list to restart the application.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Relaunch the bootstrap JAR and wait for this process to exit</li>
     *   <li>Use the original process command for direct development launches</li>
     *   <li>Fall back to {@code java.home} and {@code jlshell.active.jar}</li>
     * </ol>
     */
    private static List<String> buildRestartCommand() {
        String launcherJar = System.getProperty("jlshell.launcher.jar", "");
        if (!launcherJar.isBlank()) {
            String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "javaw.exe" : "java";
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(argument -> !argument.startsWith("-Djlshell.restart.parentPid="))
                    .forEach(command::add);
            command.add("-Djlshell.restart.parentPid=" + ProcessHandle.current().pid());
            command.add("-jar");
            command.add(launcherJar);
            return command;
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (info.command().isPresent() && info.arguments().isPresent()) {
            List<String> command = new ArrayList<>();
            command.add(info.command().get());
            command.addAll(List.of(info.arguments().get()));
            return command;
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
        cmd.add("-Xms32m");
        cmd.add("-Xmx512m");
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+ExplicitGCInvokesConcurrent");
        cmd.add("-XX:G1PeriodicGCInterval=30000");
        cmd.add("-XX:MinHeapFreeRatio=20");
        cmd.add("-XX:MaxHeapFreeRatio=40");
        // Main class from the launcher
        cmd.add("-jar");
        cmd.add(Path.of(activeJar).toAbsolutePath().toString());

        return cmd;
    }
}
