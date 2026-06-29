package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePluginDirectoriesTest {

    @TempDir
    Path tempDir;

    @Test
    void includesDistributionRootWhenCodeSourceLivesInAppDirectory() {
        Path root = tempDir.resolve("JLShell");
        Path appDir = root.resolve("app");
        Path userDir = tempDir.resolve("user-plugins");

        assertThat(RuntimePluginDirectories.fromApplicationDir(userDir.toString(), "plugins", appDir))
                .containsExactly(
                        userDir.toAbsolutePath().normalize(),
                        appDir.resolve("plugins").toAbsolutePath().normalize(),
                        root.resolve("plugins").toAbsolutePath().normalize()
                );
    }

    @Test
    void doesNotAddParentDirectoryForNonAppCodeSourceDirectory() {
        Path root = tempDir.resolve("JLShell");
        Path userDir = tempDir.resolve("user-plugins");

        assertThat(RuntimePluginDirectories.fromApplicationDir(userDir.toString(), "plugins", root))
                .containsExactly(
                        userDir.toAbsolutePath().normalize(),
                        root.resolve("plugins").toAbsolutePath().normalize()
                );
    }

    @Test
    void removesDuplicateDirectories() {
        Path root = tempDir.resolve("JLShell");
        Path userDir = root.resolve("plugins");

        assertThat(RuntimePluginDirectories.fromApplicationDir(userDir.toString(), "plugins", root))
                .containsExactly(userDir.toAbsolutePath().normalize());
    }
}
