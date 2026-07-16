package com.jlshell.plugin.loader.store;

import com.jlshell.plugin.api.PluginScope;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginInstallerTest {

    @Test
    void installsVerifiedJarAndKeepsPreviousVersion() throws Exception {
        AtomicReference<byte[]> packageBytes = new AtomicReference<>(jar("1.0.0", PluginScope.SESSION));
        Path root = Files.createTempDirectory("plugin-store-test");
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(packageBytes.get()),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);

        PluginInstaller.InstallResult first = installer.install(plugin, version("1.0.0", packageBytes.get()));
        assertThat(first.currentJar()).exists();
        assertThat(first.currentJar().getFileName().toString()).isEqualTo("com.example.tools-1.0.0.jar");
        assertThat(first.previousJar()).doesNotExist();

        packageBytes.set(jar("1.1.0", PluginScope.SESSION));
        PluginStoreListing upgraded = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.1.0", "0.1.0", null, 0, null);
        PluginInstaller.InstallResult second = installer.install(upgraded, version("1.1.0", packageBytes.get()));

        assertThat(manifestVersion(second.currentJar())).isEqualTo("1.1.0");
        assertThat(manifestVersion(second.previousJar())).isEqualTo("1.0.0");
        assertThat(second.currentJar().getFileName().toString()).isEqualTo("com.example.tools-1.1.0.jar");
        assertThat(second.previousJar().getFileName().toString()).isEqualTo("com.example.tools-1.0.0.jar");
        assertThat(second.currentJar().getParent().resolve("install.json")).exists();

        assertThat(installer.rollback("com.example.tools", PluginScope.SESSION)).isTrue();
        assertThat(manifestVersion(root.resolve("session/com.example.tools/com.example.tools-1.0.0.jar")))
                .isEqualTo("1.0.0");
        assertThat(second.currentJar()).doesNotExist();
    }

    @Test
    void rejectsJarWithMismatchedManifestBeforeReplacingCurrentVersion() throws Exception {
        AtomicReference<byte[]> packageBytes = new AtomicReference<>(jar("2.0.0", PluginScope.SESSION));
        Path root = Files.createTempDirectory("plugin-store-test");
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(packageBytes.get()),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "2.0.0", "0.1.0", null, 0, null);
        installer.install(plugin, version("2.0.0", packageBytes.get()));

        packageBytes.set(jar("2.1.0", PluginScope.SESSION));
        PluginStoreListing invalid = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "2.2.0", "0.1.0", null, 0, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> installer.install(invalid, version("2.2.0", packageBytes.get())))
                .isInstanceOf(PluginStoreException.class);
        assertThat(manifestVersion(root.resolve("session/com.example.tools/com.example.tools-2.0.0.jar")))
                .isEqualTo("2.0.0");
    }

    @Test
    void rejectsJarWithoutStaticDescriptor() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION, false);
        Path root = Files.createTempDirectory("plugin-store-test");
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> installer.install(plugin, version("1.0.0", bytes)))
                .isInstanceOf(PluginStoreException.class)
                .hasMessageContaining(PluginPackageDescriptor.PATH);
        assertThat(root.resolve("session/com.example.tools/com.example.tools-1.0.0.jar")).doesNotExist();
    }

    @Test
    void rejectsDescriptorEntrypointThatDiffersFromStore() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);
        PluginStoreVersion mismatched = new PluginStoreVersion("1.0.0", "example.OtherPlugin", "0.1.0", null,
                "notes", sha256(bytes), bytes.length, "APPROVED", 0, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> installer.install(plugin, mismatched))
                .isInstanceOf(PluginStoreException.class)
                .hasMessageContaining("入口类与商店声明不一致");
    }

    @Test
    void validatesStaticContractBeforeLoadingLocalJar() throws Exception {
        Path jar = Files.createTempFile("local-plugin", ".jar");
        Files.write(jar, jar("1.0.0", PluginScope.SESSION));

        PluginPackageValidator.validateForLoading(jar, PluginScope.SESSION);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PluginPackageValidator.validateForLoading(jar, PluginScope.PROGRAM))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("作用域与加载目录不一致");
    }

    @Test
    void migratesLegacyPluginJarToDescriptiveBackupName() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        Path pluginDir = Files.createDirectories(root.resolve("session/com.example.tools"));
        Files.write(pluginDir.resolve("plugin.jar"), jar("0.9.0", PluginScope.SESSION));
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);

        PluginInstaller.InstallResult result = installer.install(plugin, version("1.0.0", bytes));

        assertThat(result.currentJar().getFileName().toString()).isEqualTo("com.example.tools-1.0.0.jar");
        assertThat(result.previousJar().getFileName().toString()).isEqualTo("com.example.tools-0.9.0.jar");
        assertThat(pluginDir.resolve("plugin.jar")).doesNotExist();
    }

    @Test
    void detectsAndReplacesLegacyFlatJarWithSamePluginId() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        Path sessionRoot = Files.createDirectories(root.resolve("session"));
        Path legacy = sessionRoot.resolve("plugin-demo-0.9.0.jar");
        Files.write(legacy, jar("0.9.0", PluginScope.SESSION));
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), sessionRoot);
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);

        assertThat(installer.findInstalled("com.example.tools"))
                .extracting(PluginInstaller.InstalledArtifact::jar)
                .containsExactly(legacy.toAbsolutePath().normalize());

        PluginInstaller.InstallResult result = installer.install(plugin, version("1.0.0", bytes));

        assertThat(legacy).doesNotExist();
        assertThat(result.currentJar()).exists();
        assertThat(result.previousJar()).exists();
        assertThat(manifestVersion(result.previousJar())).isEqualTo("0.9.0");
    }

    @Test
    void reportsDownloadAndInstallationProgress() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), root.resolve("session"));
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);
        List<Double> progress = new CopyOnWriteArrayList<>();

        installer.install(plugin, version("1.0.0", bytes), () -> { }, progress::add);

        assertThat(progress).isNotEmpty();
        assertThat(progress.getFirst()).isZero();
        assertThat(progress).anyMatch(value -> value > 0 && value < 1);
        assertThat(progress.getLast()).isEqualTo(1);
    }

    @Test
    void uninstallsManagedDirectoryBackupsAndLegacyFlatJar() throws Exception {
        byte[] bytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        Path sessionRoot = Files.createDirectories(root.resolve("session"));
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(bytes),
                root.resolve("program"), sessionRoot);
        PluginStoreListing plugin = new PluginStoreListing("com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);
        installer.install(plugin, version("1.0.0", bytes));
        Path legacy = sessionRoot.resolve("legacy-tools.jar");
        Files.write(legacy, jar("0.9.0", PluginScope.SESSION));
        AtomicReference<Boolean> stopped = new AtomicReference<>(false);

        PluginInstaller.UninstallResult result = installer.uninstall(
                "com.example.tools", PluginScope.SESSION, () -> stopped.set(true));

        assertThat(stopped.get()).isTrue();
        assertThat(result.removed()).isTrue();
        assertThat(result.removedJars()).hasSize(2);
        assertThat(sessionRoot.resolve("com.example.tools")).doesNotExist();
        assertThat(legacy).doesNotExist();
        assertThat(installer.findInstalled("com.example.tools")).isEmpty();
    }

    @Test
    void rejectsSamePluginIdInstalledUnderDifferentScope() throws Exception {
        byte[] sessionBytes = jar("1.0.0", PluginScope.SESSION);
        Path root = Files.createTempDirectory("plugin-store-test");
        Path programRoot = Files.createDirectories(root.resolve("program"));
        Path programJar = programRoot.resolve("program-tools.jar");
        Files.write(programJar, jar("0.9.0", PluginScope.PROGRAM));
        PluginInstaller installer = new PluginInstaller((id, version) -> new ByteArrayInputStream(sessionBytes),
                programRoot, root.resolve("session"));
        PluginStoreListing sessionPlugin = new PluginStoreListing(
                "com.example.tools", PluginScope.SESSION, "LISTED",
                "Tools", "desc", "example", null, "1.0.0", "0.1.0", null, 0, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> installer.install(sessionPlugin, version("1.0.0", sessionBytes)))
                .isInstanceOf(PluginStoreException.class)
                .hasMessageContaining("作用域");
        assertThat(programJar).exists();
        assertThat(root.resolve("session/com.example.tools")).doesNotExist();
    }

    @Test
    void comparesSemanticVersionsIncludingPreRelease() {
        assertThat(PluginStoreClient.isNewer("1.10.0", "1.9.0")).isTrue();
        assertThat(PluginStoreClient.isNewer("0.1.1", "0.1.0")).isTrue();
        assertThat(PluginStoreClient.isNewer("1.0.0", "1.0.0-rc.1")).isTrue();
        assertThat(PluginStoreClient.isNewer("1.0.0-rc.1", "1.0.0")).isFalse();
    }

    @Test
    void normalizesLegacyMavenVersionBeforeCallingTheStore() {
        assertThat(PluginStoreClient.normalizeHostVersion("0.1.0.RELEASE")).isEqualTo("0.1.0");
        assertThat(PluginStoreClient.normalizeHostVersion("0.1.0-SNAPSHOT")).isEqualTo("0.1.0");
        assertThat(PluginStoreClient.normalizeHostVersion("v1.2.3")).isEqualTo("1.2.3");
        assertThat(PluginStoreClient.normalizeHostVersion("1.2.3-rc.1")).isEqualTo("1.2.3-rc.1");
    }

    private static PluginStoreVersion version(String version, byte[] bytes) throws Exception {
        return new PluginStoreVersion(version, "example.Plugin", "0.1.0", null, "notes",
                sha256(bytes), bytes.length,
                "APPROVED", 0, null);
    }

    private static byte[] jar(String version, PluginScope scope) throws Exception {
        return jar(version, scope, true);
    }

    private static byte[] jar(String version, PluginScope scope, boolean includeDescriptor) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(PluginInstaller.MANIFEST_PLUGIN_ID, "com.example.tools");
        attributes.putValue(PluginInstaller.MANIFEST_PLUGIN_VERSION, version);
        attributes.putValue(PluginInstaller.MANIFEST_PLUGIN_SCOPE, scope.name());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
            if (includeDescriptor) {
                jar.putNextEntry(new JarEntry(PluginPackageDescriptor.PATH));
                jar.write(descriptor(version, scope).getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            String spi = scope == PluginScope.PROGRAM
                    ? "META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin"
                    : "META-INF/services/com.jlshell.plugin.api.JlShellPlugin";
            jar.putNextEntry(new JarEntry(spi));
            jar.write("example.Plugin\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("example/Plugin.class"));
            jar.write(new byte[] {0});
            jar.closeEntry();
        }
        return out.toByteArray();
    }

    private static String descriptor(String version, PluginScope scope) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "com.example.tools",
                  "version": "%s",
                  "scope": "%s",
                  "entrypoint": "example.Plugin",
                  "displayName": "Tools",
                  "description": "Example tools",
                  "author": "Example",
                  "minHostVersion": "0.1.0"
                }
                """.formatted(version, scope.name());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String manifestVersion(Path path) throws Exception {
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            return jar.getManifest().getMainAttributes().getValue(PluginInstaller.MANIFEST_PLUGIN_VERSION);
        }
    }
}
