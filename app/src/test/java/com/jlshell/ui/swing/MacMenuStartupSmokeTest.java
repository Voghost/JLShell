package com.jlshell.ui.swing;

import com.jlshell.app.Launcher;
import com.jlshell.data.config.DatabaseFactory;
import com.jlshell.data.service.JdbiAppSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verify saved UI language wins over the JVM default during the real Launcher startup. */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class MacMenuStartupSmokeTest {
    @TempDir Path temporary;

    @Test void startupUsesSavedChineseAndEnglishForTheNativeApplicationMenu() throws Exception {
        for (String language : new String[]{"zh_CN", "en"}) {
            Path log = temporary.resolve(language + ".log");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                    "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                    "-Duser.home=" + temporary.resolve(language), "-Djlshell.ui.preview=true",
                    "-Djlshell.ui.renderer=swing", "-cp", System.getProperty("java.class.path"),
                    Probe.class.getName(), language).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(child.waitFor(40, TimeUnit.SECONDS), () -> read(log));
                assertEquals(0, child.exitValue(), () -> read(log));
                assertTrue(read(log).contains("NATIVE_MENU_LANGUAGE_READY " + language), () -> read(log));
            } finally { if (child.isAlive()) child.destroyForcibly(); }
        }
    }

    private static String read(Path log) {
        try { return Files.readString(log); } catch (Exception error) { return error.toString(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            String language = args[0];
            Path home = Path.of(System.getProperty("user.home"), ".jlshell");
            Files.createDirectories(home);
            try (var source = DatabaseFactory.createDataSource("jdbc:sqlite:" + home.resolve("jlshell.db"))) {
                var jdbi = DatabaseFactory.createJdbi(source);
                DatabaseFactory.initSchema(jdbi);
                new JdbiAppSettingsService(jdbi).set("ui.language", language);
            }
            Locale.setDefault(language.equals("en") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
            Thread observer = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (System.nanoTime() < deadline) {
                        CompletableFuture<Boolean> showing = new CompletableFuture<>();
                        SwingUtilities.invokeLater(() -> showing.complete(Arrays.stream(Window.getWindows())
                                .anyMatch(window -> window instanceof JFrame frame && frame.isShowing() && frame.getTitle().startsWith("JLShell"))));
                        if (showing.get(3, TimeUnit.SECONDS)) {
                            var menu = MacApplicationMenu.snapshot().get(3, TimeUnit.SECONDS);
                            String expected = language.equals("en") ? "About JLShell" : "关于 JLShell";
                            if (menu.stream().anyMatch(item -> item.title().equals(expected))) {
                                System.out.println("NATIVE_MENU_LANGUAGE_READY " + language + " " + menu);
                                System.exit(0);
                            }
                        }
                        Thread.sleep(50);
                    }
                    throw new AssertionError("Native menu did not follow the saved language");
                } catch (Throwable error) { error.printStackTrace(); System.exit(2); }
            }, "native-menu-startup-observer");
            observer.setDaemon(true);
            observer.start();
            Launcher.main(new String[0]);
        }
    }
}
