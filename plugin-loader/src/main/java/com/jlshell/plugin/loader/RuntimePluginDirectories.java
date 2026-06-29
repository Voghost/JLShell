package com.jlshell.plugin.loader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves plugin directories across development and packaged application layouts.
 */
public final class RuntimePluginDirectories {

    private RuntimePluginDirectories() {
    }

    public static List<Path> resolve(String userPluginsDir, String bundledDirectoryName) {
        List<Path> dirs = new ArrayList<>();
        if (userPluginsDir != null && !userPluginsDir.isBlank()) {
            addDistinct(dirs, Path.of(userPluginsDir));
        }
        for (Path applicationDir : resolveApplicationDirs()) {
            addBundledDirs(dirs, applicationDir, bundledDirectoryName);
        }
        return List.copyOf(dirs);
    }

    static List<Path> fromApplicationDir(String userPluginsDir, String bundledDirectoryName, Path applicationDir) {
        List<Path> dirs = new ArrayList<>();
        if (userPluginsDir != null && !userPluginsDir.isBlank()) {
            addDistinct(dirs, Path.of(userPluginsDir));
        }
        addBundledDirs(dirs, applicationDir, bundledDirectoryName);
        return List.copyOf(dirs);
    }

    private static void addBundledDirs(List<Path> dirs, Path applicationDir, String bundledDirectoryName) {
        if (applicationDir == null || bundledDirectoryName == null || bundledDirectoryName.isBlank()) return;
        addDistinct(dirs, applicationDir.resolve(bundledDirectoryName));

        Path name = applicationDir.getFileName();
        Path parent = applicationDir.getParent();
        if (parent != null && name != null && "app".equalsIgnoreCase(name.toString())) {
            addDistinct(dirs, parent.resolve(bundledDirectoryName));
        }
    }

    private static List<Path> resolveApplicationDirs() {
        List<Path> dirs = new ArrayList<>();

        String appDir = System.getProperty("jlshell.app.dir");
        if (appDir != null && !appDir.isBlank()) {
            addDistinct(dirs, Path.of(appDir));
        }

        try {
            Path codeSourcePath = Path.of(RuntimePluginDirectories.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSourcePath)) {
                addDistinct(dirs, codeSourcePath.getParent());
            } else if (Files.isDirectory(codeSourcePath)) {
                addDistinct(dirs, codeSourcePath);
            }
        } catch (Exception ignored) {
        }

        try {
            String cmd = java.lang.ProcessHandle.current().info().command().orElse("");
            if (!cmd.isEmpty()) {
                Path exePath = Path.of(cmd);
                if (Files.isRegularFile(exePath)) {
                    addDistinct(dirs, exePath.getParent());
                }
            }
        } catch (Exception ignored) {
        }

        return dirs;
    }

    private static void addDistinct(List<Path> dirs, Path dir) {
        if (dir == null) return;
        Path normalized = dir.toAbsolutePath().normalize();
        if (!dirs.contains(normalized)) {
            dirs.add(normalized);
        }
    }
}
