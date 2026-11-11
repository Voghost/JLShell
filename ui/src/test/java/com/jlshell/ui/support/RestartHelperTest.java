package com.jlshell.ui.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestartHelperTest {
    @TempDir Path temporary;

    @Test
    void restartsThroughNativeLauncherWithParentMarker() throws Exception {
        Path launcher = Files.createFile(temporary.resolve("JLShell.exe"));
        String previous = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", launcher.toString());
            var method = RestartHelper.class.getDeclaredMethod("buildRestartCommand");
            method.setAccessible(true);
            assertEquals(List.of(launcher.toString(),
                    "--jlshell-restart-parent=" + ProcessHandle.current().pid()), method.invoke(null));
        } finally {
            if (previous == null) System.clearProperty("jpackage.app-path");
            else System.setProperty("jpackage.app-path", previous);
        }
    }
}
