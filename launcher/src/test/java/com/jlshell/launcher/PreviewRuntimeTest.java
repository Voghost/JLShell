package com.jlshell.launcher;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewRuntimeTest {
    @TempDir Path temporary;

    @Test void isolatesPreviewFilesAndPreservesWindowsRelaunchOptions() throws Exception {
        String[] names = {"user.home", "jlshell.preview.home", "jlshell.ui.preview", "jlshell.ui.renderer"};
        var previous = new HashMap<String, String>();
        for (String name : names) previous.put(name, System.getProperty(name));
        Path stable = temporary.resolve(".jlshell/jlshell.db");
        Files.createDirectories(stable.getParent());
        Files.writeString(stable, "stable fixture");
        try {
            System.setProperty("user.home", temporary.toString());
            System.clearProperty("jlshell.preview.home");
            assertFalse(PreviewRuntime.enabled(), "Normal builds must not enable preview mode");
            PreviewRuntime.configure();
            Path isolated = temporary.resolve(".jlshell-preview");
            assertEquals(isolated.toString(), System.getProperty("user.home"));
            assertEquals("true", System.getProperty("jlshell.ui.preview"));
            assertEquals("swing", System.getProperty("jlshell.ui.renderer"));
            assertEquals("stable fixture", Files.readString(stable));
            var command = BootstrapLauncher.windowsRelaunchCommand(Path.of("javaw.exe"),
                    Path.of("launcher.jar"), new String[]{"--open", "fixture"}, true);
            assertTrue(command.contains("-Djlshell.preview.home=" + isolated));
            assertTrue(command.contains("--add-exports=java.desktop/sun.swing=ALL-UNNAMED"));
            assertEquals(java.util.List.of("--open", "fixture"), command.subList(command.size() - 2, command.size()));
        } finally {
            for (String name : names) {
                String value = previous.get(name);
                if (value == null) System.clearProperty(name); else System.setProperty(name, value);
            }
        }
    }
}
