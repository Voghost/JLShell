package com.jlshell.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅由 Preview Actions 的包标记启用，正式包没有该资源。 */
final class PreviewRuntime {
    private PreviewRuntime() {}

    static boolean enabled() {
        return PreviewRuntime.class.getResource("/META-INF/jlshell-preview.properties") != null;
    }

    static void configure() throws IOException {
        String requested = System.getProperty("jlshell.preview.home");
        Path home = requested == null || requested.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jlshell-preview") : Path.of(requested);
        configure(home);
    }

    static void configure(Path home) throws IOException {
        Path isolated = home.toAbsolutePath().normalize();
        Files.createDirectories(isolated);
        System.setProperty("jlshell.preview.home", isolated.toString());
        System.setProperty("user.home", isolated.toString());
        System.setProperty("jlshell.ui.preview", "true");
        System.setProperty("jlshell.ui.renderer", "swing");
    }
}
