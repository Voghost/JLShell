package com.jlshell.plugin.loader.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.loader.RuntimePluginDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 将商店 JAR 安装为 {@code <scope-root>/<plugin-id>/<plugin-id>-<version>.jar}。
 * 旧版本按原文件名保留在 {@code .previous/}，并仅在完整校验后原子替换当前版本。
 */
public final class PluginInstaller {
    public static final String MANIFEST_PLUGIN_ID = "JLShell-Plugin-Id";
    public static final String MANIFEST_PLUGIN_VERSION = "JLShell-Plugin-Version";
    public static final String MANIFEST_PLUGIN_SCOPE = "JLShell-Plugin-Scope";

    private final PluginStoreClient client;
    private final PluginPackageDownloader downloader;
    private final Path programRoot;
    private final Path sessionRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public PluginInstaller(PluginStoreClient client, Path programRoot, Path sessionRoot) {
        this.client = Objects.requireNonNull(client);
        this.downloader = (pluginId, version) -> this.client.download(pluginId, version).join().body();
        this.programRoot = Objects.requireNonNull(programRoot);
        this.sessionRoot = Objects.requireNonNull(sessionRoot);
    }

    PluginInstaller(PluginPackageDownloader downloader, Path programRoot, Path sessionRoot) {
        this.client = null;
        this.downloader = Objects.requireNonNull(downloader);
        this.programRoot = Objects.requireNonNull(programRoot);
        this.sessionRoot = Objects.requireNonNull(sessionRoot);
    }

    public InstallResult install(PluginStoreListing plugin, PluginStoreVersion version) {
        return install(plugin, version, () -> { }, progress -> { });
    }

    /**
     * {@code beforeReplace} 由调用方用于安全停用 Session 插件实例；只有下载及
     * 全部验证完成后才会调用，因此下载失败不会影响当前运行版本。
     */
    public InstallResult install(PluginStoreListing plugin, PluginStoreVersion version, Runnable beforeReplace) {
        return install(plugin, version, beforeReplace, progress -> { });
    }

    /**
     * 安装或覆盖插件。进度值范围为 0~1；下载阶段占 0~0.85，之后依次为静态校验和原子替换。
     */
    public InstallResult install(PluginStoreListing plugin, PluginStoreVersion version,
                                 Runnable beforeReplace, DoubleConsumer progressListener) {
        Objects.requireNonNull(plugin);
        Objects.requireNonNull(version);
        Objects.requireNonNull(beforeReplace);
        Objects.requireNonNull(progressListener);
        if (plugin.scope() == null || plugin.pluginId() == null
                || !plugin.pluginId().matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("插件缺少 ID 或作用域");
        }
        if (!version.approved()) {
            throw new IllegalArgumentException("只允许安装 APPROVED 插件版本");
        }
        validateExpectedDigest(version);

        Path pluginDir = rootFor(plugin.scope()).resolve(plugin.pluginId()).normalize();
        if (!pluginDir.startsWith(rootFor(plugin.scope()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("非法插件 ID");
        }
        Path downloaded = null;
        try {
            List<InstalledArtifact> installedArtifacts = findInstalled(plugin.pluginId());
            if (installedArtifacts.stream().anyMatch(artifact -> artifact.scope() != plugin.scope())) {
                throw new IOException("本地同 ID 插件的作用域与商店插件不一致，请先卸载旧插件");
            }
            notifyProgress(progressListener, 0);
            Files.createDirectories(pluginDir);
            downloaded = pluginDir.resolve(".plugin-" + UUID.randomUUID() + ".part");
            downloadAndVerify(plugin.pluginId(), version, downloaded, progressListener);
            notifyProgress(progressListener, 0.88);
            PluginPackageValidator.validateForInstall(downloaded, plugin.pluginId(), version, plugin.scope());

            notifyProgress(progressListener, 0.93);
            beforeReplace.run();
            Path current = findCurrentJar(pluginDir);
            List<InstalledArtifact> legacyArtifacts = installedArtifacts.stream()
                    .filter(artifact -> artifact.scope() == plugin.scope())
                    .filter(artifact -> !artifact.jar().startsWith(pluginDir))
                    .toList();
            Path backupSource = current != null ? current
                    : legacyArtifacts.stream().map(InstalledArtifact::jar).findFirst().orElse(null);
            Path previousDir = pluginDir.resolve(".previous");
            Path previous = previousDir.resolve(backupSource == null
                    ? plugin.pluginId() + "-previous.jar"
                    : backupFileName(backupSource, plugin.pluginId()));
            if (backupSource != null) {
                Files.createDirectories(previousDir);
                clearJarFiles(previousDir);
                Path previousTemp = pluginDir.resolve(".previous-" + UUID.randomUUID() + ".part");
                Files.copy(backupSource, previousTemp, StandardCopyOption.REPLACE_EXISTING);
                moveAtomically(previousTemp, previous);
            }
            Path installedJar = pluginDir.resolve(packageFileName(plugin.pluginId(), version.version()));
            moveAtomically(downloaded, installedJar);
            if (current != null && !current.equals(installedJar)) {
                Files.deleteIfExists(current);
            }
            for (InstalledArtifact artifact : legacyArtifacts) {
                Files.deleteIfExists(artifact.jar());
                cleanupManagedDirectory(artifact.jar().getParent(), rootFor(plugin.scope()), false);
            }
            writeMetadata(pluginDir, plugin, version);
            notifyProgress(progressListener, 1);
            return new InstallResult(plugin.pluginId(), plugin.scope(), version.version(), installedJar,
                    previous, plugin.scope() == PluginScope.PROGRAM);
        } catch (Exception e) {
            if (downloaded != null) deleteQuietly(downloaded);
            if (e instanceof PluginStoreException pluginStoreException) throw pluginStoreException;
            throw new PluginStoreException("安装插件失败：" + e.getMessage(), e);
        }
    }

    /** 查找用户插件目录中同 ID 的当前 JAR，同时兼容平铺和独立目录。 */
    public List<InstalledArtifact> findInstalled(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return List.of();
        java.util.ArrayList<InstalledArtifact> matches = new java.util.ArrayList<>();
        collectInstalled(matches, programRoot, PluginScope.PROGRAM, pluginId);
        collectInstalled(matches, sessionRoot, PluginScope.SESSION, pluginId);
        return List.copyOf(matches);
    }

    /**
     * 从用户插件目录卸载指定插件。Session 插件调用方应通过 beforeDelete 先停用实例；
     * Program 插件文件删除后仍需重启才能从当前进程卸载。
     */
    public UninstallResult uninstall(String pluginId, PluginScope scope, Runnable beforeDelete) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(beforeDelete);
        List<InstalledArtifact> artifacts = findInstalled(pluginId).stream()
                .filter(artifact -> artifact.scope() == scope)
                .toList();
        if (artifacts.isEmpty()) {
            return new UninstallResult(pluginId, scope, List.of(), scope == PluginScope.PROGRAM, false);
        }
        try {
            beforeDelete.run();
            Path root = rootFor(scope);
            java.util.ArrayList<Path> removed = new java.util.ArrayList<>();
            for (InstalledArtifact artifact : artifacts) {
                if (Files.deleteIfExists(artifact.jar())) removed.add(artifact.jar());
                cleanupManagedDirectory(artifact.jar().getParent(), root, true);
            }
            return new UninstallResult(pluginId, scope, List.copyOf(removed),
                    scope == PluginScope.PROGRAM, !removed.isEmpty());
        } catch (Exception e) {
            throw new PluginStoreException("卸载插件失败：" + e.getMessage(), e);
        }
    }

    public boolean rollback(String pluginId, PluginScope scope) {
        Path dir = rootFor(scope).resolve(pluginId);
        try {
            Path previous = findSingleJar(dir.resolve(".previous"));
            boolean legacyBackup = false;
            if (previous == null) {
                Path legacyPrevious = dir.resolve("previous-plugin.jar");
                if (!Files.isRegularFile(legacyPrevious)) return false;
                previous = legacyPrevious;
                legacyBackup = true;
            }
            Path current = findCurrentJar(dir);
            Path restored = legacyBackup ? dir.resolve("plugin.jar") : dir.resolve(previous.getFileName());
            moveAtomically(previous, restored);
            if (current != null && !current.equals(restored)) {
                Files.deleteIfExists(current);
            }
            return true;
        } catch (IOException e) {
            throw new PluginStoreException("回滚插件失败", e);
        }
    }

    private void downloadAndVerify(String pluginId, PluginStoreVersion version, Path destination,
                                   DoubleConsumer progressListener) throws Exception {
        long size = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = downloader.download(pluginId, version.version()); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
                if (version.size() > 0) {
                    notifyProgress(progressListener, Math.min(0.85, size * 0.85 / version.size()));
                }
            }
        }
        if (size != version.size()) {
            throw new IOException("插件大小校验失败，期望 " + version.size() + "，实际 " + size);
        }
        String actualDigest = HexFormat.of().formatHex(digest.digest());
        if (!actualDigest.equals(version.sha256())) {
            throw new IOException("插件 SHA-256 校验失败");
        }
    }

    private static void notifyProgress(DoubleConsumer listener, double value) {
        try {
            listener.accept(Math.clamp(value, 0, 1));
        } catch (RuntimeException ignored) {
            // UI 进度展示失败不能中断下载、校验或原子替换。
        }
    }

    private static void collectInstalled(List<InstalledArtifact> matches, Path root, PluginScope directoryScope,
                                         String expectedId) {
        for (Path jar : RuntimePluginDirectories.pluginJars(root.toAbsolutePath().normalize())) {
            readIdentity(jar, directoryScope)
                    .filter(identity -> expectedId.equals(identity.pluginId()))
                    .ifPresent(identity -> matches.add(new InstalledArtifact(
                            identity.pluginId(), identity.scope(), identity.version(), jar.toAbsolutePath().normalize())));
        }
    }

    private static Optional<PluginIdentity> readIdentity(Path jarPath, PluginScope directoryScope) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry descriptorEntry = jar.getJarEntry(PluginPackageDescriptor.PATH);
            if (descriptorEntry != null) {
                try (InputStream input = jar.getInputStream(descriptorEntry)) {
                    PluginPackageDescriptor descriptor = PluginPackageDescriptor.parse(input.readAllBytes());
                    return Optional.of(new PluginIdentity(
                            descriptor.id(), descriptor.scope(), descriptor.version()));
                }
            }
            if (jar.getManifest() == null) return Optional.empty();
            var attributes = jar.getManifest().getMainAttributes();
            String id = attributes.getValue(MANIFEST_PLUGIN_ID);
            String version = attributes.getValue(MANIFEST_PLUGIN_VERSION);
            String scopeValue = attributes.getValue(MANIFEST_PLUGIN_SCOPE);
            if (id == null || id.isBlank()) return Optional.empty();
            PluginScope scope = scopeValue == null || scopeValue.isBlank()
                    ? directoryScope : PluginScope.valueOf(scopeValue.strip());
            return Optional.of(new PluginIdentity(id.strip(), scope,
                    version == null ? "" : version.strip()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void cleanupManagedDirectory(Path directory, Path root, boolean removeMetadataAndBackups)
            throws IOException {
        if (directory == null || directory.equals(root) || !directory.startsWith(root)
                || !Files.isDirectory(directory)) {
            return;
        }
        if (removeMetadataAndBackups) {
            Files.deleteIfExists(directory.resolve("install.json"));
            deleteTree(directory.resolve(".previous"));
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith(".plugin-")
                                || file.getFileName().toString().startsWith(".previous-")).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        try (var files = Files.list(directory)) {
            if (files.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void validateExpectedDigest(PluginStoreVersion version) {
        if (version.size() < 0 || version.sha256() == null || !version.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("插件商店返回了无效的大小或 SHA-256");
        }
    }

    private void writeMetadata(Path pluginDir, PluginStoreListing plugin, PluginStoreVersion version) throws IOException {
        Path temp = pluginDir.resolve(".install.json.part");
        Path metadata = pluginDir.resolve("install.json");
        Files.writeString(temp, gson.toJson(new InstallationMetadata(plugin.pluginId(), plugin.scope(), version.version(),
                version.sha256(), version.size(), Instant.now().toString())));
        moveAtomically(temp, metadata);
    }

    private static String packageFileName(String pluginId, String version) {
        return pluginId + "-" + version + ".jar";
    }

    private static String backupFileName(Path current, String pluginId) {
        String fileName = current.getFileName().toString();
        if (!"plugin.jar".equals(fileName)) return fileName;
        try (JarFile jar = new JarFile(current.toFile())) {
            String version = jar.getManifest() == null ? null
                    : jar.getManifest().getMainAttributes().getValue(MANIFEST_PLUGIN_VERSION);
            if (version != null && !version.isBlank()) {
                return packageFileName(pluginId, version.strip());
            }
        } catch (IOException ignored) {
            // 旧包无法读取版本时仍使用包含插件 ID 的可识别备份名。
        }
        return pluginId + "-previous.jar";
    }

    private static Path findCurrentJar(Path pluginDir) throws IOException {
        if (!Files.isDirectory(pluginDir)) return null;
        try (var files = Files.list(pluginDir)) {
            var jars = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("previous-"))
                    .toList();
            if (jars.size() > 1) {
                throw new IOException("插件目录存在多个当前版本 JAR");
            }
            return jars.isEmpty() ? null : jars.getFirst();
        }
    }

    private static Path findSingleJar(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            var jars = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (jars.size() > 1) throw new IOException("插件回滚目录存在多个 JAR");
            return jars.isEmpty() ? null : jars.getFirst();
        }
    }

    private static void clearJarFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".jar")).toList()) {
                Files.delete(path);
            }
        }
    }

    private Path rootFor(PluginScope scope) {
        return (scope == PluginScope.PROGRAM ? programRoot : sessionRoot).toAbsolutePath().normalize();
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    public record InstallResult(String pluginId, PluginScope scope, String version, Path currentJar,
                                Path previousJar, boolean restartRequired) { }
    public record InstalledArtifact(String pluginId, PluginScope scope, String version, Path jar) { }
    public record UninstallResult(String pluginId, PluginScope scope, List<Path> removedJars,
                                  boolean restartRequired, boolean removed) { }
    private record PluginIdentity(String pluginId, PluginScope scope, String version) { }
    private record InstallationMetadata(String pluginId, PluginScope scope, String version,
                                        String sha256, long size, String installedAt) { }
}
