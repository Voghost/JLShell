package com.jlshell.plugin.loader.store;

import com.jlshell.plugin.api.PluginScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 在执行插件代码之前验证 JAR 的静态包契约。 */
public final class PluginPackageValidator {
    private PluginPackageValidator() {
    }

    /** 验证从本地目录发现的插件包。 */
    public static void validateForLoading(Path jar, PluginScope expectedScope) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), true)) {
            ValidatedPackage validated = validateStaticPackage(file, expectedScope);
            Attributes attributes = file.getManifest() == null ? null : file.getManifest().getMainAttributes();
            if (attributes == null
                    || !validated.descriptor().id().equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_ID))
                    || !validated.descriptor().version().equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_VERSION))
                    || !validated.descriptor().scope().name().equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_SCOPE))) {
                throw new IOException("插件 JAR 主清单与 JSON 静态清单不一致");
            }
        }
    }

    /** 验证商店下载包，并额外与服务端声明比对。 */
    static void validateForInstall(Path jar, String pluginId, PluginStoreVersion version,
                                   PluginScope expectedScope) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), true)) {
            ValidatedPackage validated = validateStaticPackage(file, expectedScope);
            Attributes attributes = file.getManifest() == null ? null : file.getManifest().getMainAttributes();
            if (attributes == null
                    || !pluginId.equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_ID))
                    || !version.version().equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_VERSION))
                    || !expectedScope.name().equals(attributes.getValue(PluginInstaller.MANIFEST_PLUGIN_SCOPE))) {
                throw new IOException("插件 JAR 主清单与商店声明不一致");
            }
            validated.descriptor().validateStoreIdentity(pluginId, version.version(), expectedScope,
                    version.entrypoint(), version.minHostVersion(), version.maxHostVersion());
        }
    }

    private static ValidatedPackage validateStaticPackage(JarFile file, PluginScope expectedScope) throws IOException {
        JarEntry descriptorEntry = file.getJarEntry(PluginPackageDescriptor.PATH);
        if (descriptorEntry == null || descriptorEntry.isDirectory()) {
            throw new IOException("插件 JAR 缺少 " + PluginPackageDescriptor.PATH);
        }
        PluginPackageDescriptor descriptor;
        try (InputStream input = file.getInputStream(descriptorEntry)) {
            descriptor = PluginPackageDescriptor.parse(input.readAllBytes());
        }
        if (descriptor.scope() != expectedScope) {
            throw new IOException("插件 JSON 静态清单作用域与加载目录不一致");
        }

        String expectedSpi = expectedScope == PluginScope.PROGRAM
                ? "META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin"
                : "META-INF/services/com.jlshell.plugin.api.JlShellPlugin";
        String forbiddenSpi = expectedScope == PluginScope.PROGRAM
                ? "META-INF/services/com.jlshell.plugin.api.JlShellPlugin"
                : "META-INF/services/com.jlshell.plugin.api.JlShellProgramPlugin";
        if (file.getJarEntry(forbiddenSpi) != null) {
            throw new IOException("插件 JAR 不能同时包含两种 JLShell SPI 文件");
        }
        JarEntry spiEntry = file.getJarEntry(expectedSpi);
        if (spiEntry == null || spiEntry.isDirectory()) {
            throw new IOException("插件 JAR 缺少作用域对应的 ServiceLoader 文件");
        }
        String spiEntrypoint;
        try (InputStream input = file.getInputStream(spiEntry)) {
            var declarations = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(line -> line.contains("#") ? line.substring(0, line.indexOf('#')) : line)
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .distinct()
                    .toList();
            if (declarations.size() != 1) {
                throw new IOException("插件 ServiceLoader 文件必须且只能声明一个入口类");
            }
            spiEntrypoint = declarations.getFirst();
        }
        if (!descriptor.entrypoint().equals(spiEntrypoint)) {
            throw new IOException("插件 JSON 静态清单 entrypoint 与 ServiceLoader 文件不一致");
        }
        if (file.getJarEntry(spiEntrypoint.replace('.', '/') + ".class") == null) {
            throw new IOException("插件入口类不存在于 JAR 中");
        }
        return new ValidatedPackage(descriptor);
    }

    private record ValidatedPackage(PluginPackageDescriptor descriptor) {
    }
}
