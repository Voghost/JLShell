package com.jlshell.app;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.SystemTray;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 JVM 从真实 Launcher 冷启动，覆盖 splash、系统菜单、托盘和窗口关闭路径。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class DesktopStartupSmokeTest {
    @TempDir Path temporary;

    @Test
    void coldStartsWithResponsiveFxAndSwingThreadsAndClosesNormally() throws Exception {
        Path log = temporary.resolve("startup.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                "-Duser.home=" + temporary.resolve("home"),
                "-Djlshell.ui.preview=true", "-Djlshell.ui.renderer=swing",
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(40, TimeUnit.SECONDS);
            assertTrue(exited, () -> "Startup did not complete:\n" + read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("DESKTOP_STARTUP_READY"), () -> read(log));
            assertTrue(read(log).contains("operation=startup subject=desktop stage=total event=end outcome=workbench-first-paint"),
                    () -> "Startup must measure actual Swing painting:\n" + read(log));
            assertTrue(read(log).contains("stage=javafx-toolkit-bootstrap#"), () -> read(log));
            assertTrue(read(log).contains("stage=database-schema#"), () -> read(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String read(Path log) {
        try { return Files.readString(log); }
        catch (java.io.IOException error) { return error.toString(); }
    }

    public static class Probe {
        public static void main(String[] args) {
            Thread monitor = new Thread(Probe::verifyStartup, "desktop-startup-probe");
            monitor.setDaemon(true);
            monitor.start();
            Launcher.main(args);
        }

        private static void verifyStartup() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int healthyPulses = 0;
            while (System.nanoTime() < deadline) {
                try {
                    var fx = new CompletableFuture<Boolean>();
                    Platform.runLater(() -> fx.complete(javafx.stage.Window.getWindows().stream()
                            .noneMatch(javafx.stage.Window::isShowing)));
                    var awt = new CompletableFuture<JFrame>();
                    SwingUtilities.invokeLater(() -> {
                        boolean trayReady = !SystemTray.isSupported()
                                || SystemTray.getSystemTray().getTrayIcons().length > 0;
                        JFrame frame = trayReady ? Arrays.stream(Window.getWindows())
                                .filter(JFrame.class::isInstance).map(JFrame.class::cast)
                                .filter(window -> window.isShowing() && window.getTitle().startsWith("JLShell"))
                                .findFirst().orElse(null) : null;
                        awt.complete(frame);
                    });
                    boolean splashClosed = fx.get(1, TimeUnit.SECONDS);
                    JFrame frame = awt.get(1, TimeUnit.SECONDS);
                    if (splashClosed && frame != null && ++healthyPulses >= 3) {
                        System.out.println("DESKTOP_STARTUP_READY: splash closed, FX/EDT responsive, tray ready");
                        SwingUtilities.invokeLater(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
                        return;
                    }
                    Thread.sleep(100);
                } catch (Exception pendingStartup) {
                    healthyPulses = 0;
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            System.err.println("Desktop startup timed out");
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                if (thread.getName().startsWith("AWT-EventQueue") || thread.getName().equals("JavaFX Application Thread")) {
                    System.err.println(thread.getName() + " " + thread.getState());
                    for (StackTraceElement entry : stack) System.err.println("  " + entry);
                }
            });
            Runtime.getRuntime().halt(2);
        }
    }
}
