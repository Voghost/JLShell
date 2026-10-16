package com.jlshell.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立进程冷启动：检查真实屏幕首帧，而不是手动 window.paint 的离屏图。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class LauncherUiSmokeTest {
    @TempDir Path temporary;

    @Test void paintsSplashBeforeJavaFxAndHandsOffToWorkbench() throws Exception {
        Path log = temporary.resolve("launch.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                "-Duser.home=" + temporary.resolve("home"), "-Djlshell.ui.preview=true",
                "-Dapple.awt.application.name=JLShell", "-Djlshell.ui.renderer=swing",
                "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("NATIVE_STARTUP_READY"), () -> read(log));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }

    public static class Probe {
        private static <T> T edt(Callable<T> action) throws Exception {
            FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task);
            return task.get(10, TimeUnit.SECONDS);
        }
        public static void main(String[] args) {
            try {
                // 与 Launcher 正式路径相同，先等待本机窗口首次绘制，再允许 FX 初始化。
                Launcher.showStartupBeforeJavaFx();
                Rectangle bounds = edt(() -> {
                    Window splash = java.util.Arrays.stream(Window.getWindows())
                            .filter(w -> "startup.splash".equals(w.getName()) && w.isShowing()).findFirst().orElseThrow();
                    return new Rectangle(splash.getLocationOnScreen(), splash.getSize());
                });
                Robot robot = new Robot(); robot.delay(180);
                BufferedImage screen = robot.createScreenCapture(bounds);
                int dark = 0;
                for (int y = 15; y < screen.getHeight() - 15; y++) for (int x = 15; x < screen.getWidth() - 15; x++) {
                    Color pixel = new Color(screen.getRGB(x, y));
                    if (pixel.getRed() < 80 && pixel.getGreen() < 100 && pixel.getBlue() < 130 && pixel.getBlue() > pixel.getGreen() + 8 && pixel.getGreen() > pixel.getRed() + 5) dark++;
                }
                assertTrue(dark > (screen.getWidth() - 30) * (screen.getHeight() - 30) * .8, "Native splash must show the blue artwork, not a white backing surface or an obscuring editor");
                Path output = Path.of("..", "ui", "target", "review", "startup-native-first-frame.png");
                Files.createDirectories(output.getParent()); ImageIO.write(screen, "png", output.toFile());
                Thread observer = new Thread(() -> {
                    try {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
                        while (System.nanoTime() < deadline) {
                            boolean ready = edt(() -> {
                                boolean main = java.util.Arrays.stream(Window.getWindows()).anyMatch(w -> w instanceof JFrame && w.isShowing() && ((JFrame) w).getTitle().contains("JLShell"));
                                boolean splash = java.util.Arrays.stream(Window.getWindows()).anyMatch(w -> "startup.splash".equals(w.getName()) && w.isDisplayable());
                                return main && !splash;
                            });
                            if (ready) { System.out.println("NATIVE_STARTUP_READY"); System.exit(0); }
                            Thread.sleep(50);
                        }
                        throw new AssertionError("Workbench did not replace splash");
                    } catch (Throwable failure) { failure.printStackTrace(); System.exit(2); }
                }, "startup-observer");
                observer.setDaemon(true); observer.start();
                Launcher.main(args);
            } catch (Throwable failure) { failure.printStackTrace(); System.exit(2); }
        }
    }
}
