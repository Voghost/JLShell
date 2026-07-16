package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;

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

    @Test
    void discoversLegacyFlatAndNamedJarsInsidePluginDirectories() throws Exception {
        Path root = tempDir.resolve("plugins");
        Path flat = Files.createDirectories(root).resolve("legacy-flat.jar");
        Files.createFile(flat);
        Path namedDir = Files.createDirectories(root.resolve("com.example.named"));
        Path named = namedDir.resolve("com.example.named-1.2.0.jar");
        Files.createFile(named);
        Path legacyDir = Files.createDirectories(root.resolve("com.example.legacy"));
        Path legacyNested = legacyDir.resolve("plugin.jar");
        Files.createFile(legacyNested);
        Files.createFile(namedDir.resolve("previous-old.jar"));
        Path backupDir = Files.createDirectories(namedDir.resolve(".previous"));
        Files.createFile(backupDir.resolve("com.example.named-1.1.0.jar"));

        assertThat(RuntimePluginDirectories.pluginJars(root))
                .containsExactlyInAnyOrder(flat, named, legacyNested);
    }
}
