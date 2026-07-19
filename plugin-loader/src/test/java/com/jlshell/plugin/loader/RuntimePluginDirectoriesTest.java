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

    @Test
    void keepsInstallationPluginDirectoriesWhenRunningFromUpdatedJar() throws Exception {
        Path installRoot = Files.createDirectories(tempDir.resolve("JLShell"));
        Path appDir = Files.createDirectories(installRoot.resolve("app"));
        Path launcherJar = Files.createFile(appDir.resolve("jlshell-launcher.jar"));
        Path bundledJar = Files.createFile(appDir.resolve("jlshell-app-bundled.jar"));
        Path updatedJar = Files.createDirectories(tempDir.resolve("home/.jlshell/updates"))
                .resolve("jlshell-app-0.1.61.jar");
        Files.createFile(updatedJar);

        String oldAppDir = System.getProperty("jlshell.app.dir");
        String oldLauncher = System.getProperty("jlshell.launcher.jar");
        String oldBundled = System.getProperty("jlshell.bundled.jar");
        try {
            System.clearProperty("jlshell.app.dir");
            System.setProperty("jlshell.launcher.jar", launcherJar.toString());
            System.setProperty("jlshell.bundled.jar", bundledJar.toString());

            assertThat(RuntimePluginDirectories.resolve(
                    tempDir.resolve("user-plugins").toString(), "plugins"))
                    .contains(
                            appDir.resolve("plugins").toAbsolutePath().normalize(),
                            installRoot.resolve("plugins").toAbsolutePath().normalize())
                    .doesNotContain(updatedJar.getParent().resolve("plugins").toAbsolutePath().normalize());
        } finally {
            restoreProperty("jlshell.app.dir", oldAppDir);
            restoreProperty("jlshell.launcher.jar", oldLauncher);
            restoreProperty("jlshell.bundled.jar", oldBundled);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
