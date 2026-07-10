package com.jlshell.launcher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable bootstrap entry point used by packaged distributions.
 *
 * <p>The launcher is intentionally dependency-free. It chooses a verified
 * update jar from the user update directory only when it is newer than the
 * bundled application shipped inside the installer.</p>
 */
public final class BootstrapLauncher {

    private static final String APP_MAIN_CLASS = "com.jlshell.app.Launcher";
    private static final String BUNDLED_APP_JAR = "app/jlshell-app-bundled.jar";
    private static final Pattern JSON_STRING_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STARTUP_CONFIRMED_PATTERN =
            Pattern.compile("\"startupConfirmed\"\\s*:\\s*(true|false)");
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile(
            "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private BootstrapLauncher() {}

    public static void main(String[] args) {
        configureMacApplicationName();

        try {
            Path selectedJar = selectApplicationJar();
            System.setProperty("jlshell.launcher.version", launcherVersion());
            System.setProperty("jlshell.launcher.jar", launcherJar().toAbsolutePath().toString());
            System.setProperty("jlshell.bundled.jar", bundledApplicationJar().toAbsolutePath().toString());
            System.setProperty("jlshell.update.dir", updatesDir().toAbsolutePath().toString());
            System.setProperty("jlshell.updates.dir", updatesDir().toAbsolutePath().toString());
            System.setProperty("jlshell.active.jar", selectedJar.toAbsolutePath().toString());
            invokeApplication(selectedJar, args);
        } catch (Throwable error) {
            System.err.println("JLShell failed to launch: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path selectApplicationJar() throws Exception {
        return selectApplicationJar(updatesDir(), bundledApplicationJar(), launcherVersion());
    }

    static Path selectApplicationJar(Path updatesDir, Path bundled) throws Exception {
        return selectApplicationJar(updatesDir, bundled, "0.0.0");
    }

    static Path selectApplicationJar(Path updatesDir, Path bundled, String installedVersion) throws Exception {
        Path current = updatesDir.resolve("current.json");
        Path pending = updatesDir.resolve("pending.json");
        Path previous = updatesDir.resolve("previous.json");

        Optional<UpdateEntry> currentEntry = discardCachedEntryIfNotNewer(
                current, readEntry(current), installedVersion, bundled, true);
        if (currentEntry.isPresent() && isUnconfirmed(current)) {
            rollbackUnconfirmedCurrent(current, previous, bundled);
            currentEntry = discardCachedEntryIfNotNewer(
                    current, readEntry(current), installedVersion, bundled, true);
        }

        if (Files.isRegularFile(pending)) {
            Optional<UpdateEntry> pendingEntry = readEntry(pending);
            pendingEntry = discardCachedEntryIfNotNewer(
                    pending, pendingEntry, installedVersion, bundled, false);
            if (pendingEntry.isPresent() && verifyEntry(pendingEntry.get())) {
                // Prefer promoting the app jar into the installation directory.
                // If the install dir is read-only, fall back to loading the
                // staged jar from the per-user updates directory.
                boolean promoted = promotePendingByCopy(pendingEntry.get(), bundled);
                promotePending(pending, current, previous, pendingEntry.get(),
                        promoted ? bundled : pendingEntry.get().jarPath());
                return promoted ? bundled : pendingEntry.get().jarPath();
            }
        }

        if (currentEntry.isPresent() && verifyEntry(currentEntry.get())) {
            return currentEntry.get().jarPath();
        }

        if (!Files.isRegularFile(bundled)) {
            throw new IOException("Bundled application jar not found: " + bundled);
        }
        return bundled;
    }

    /**
     * MSI-installed application files are authoritative unless a cached update
     * is strictly newer. This prevents an older per-user update cache from
     * overriding an application that was upgraded through its installer.
     */
    private static Optional<UpdateEntry> discardCachedEntryIfNotNewer(
            Path entryFile,
            Optional<UpdateEntry> entry,
            String installedVersion,
            Path bundled,
            boolean discardBundledBackup
    ) throws IOException {
        if (entry.isEmpty() || isStrictlyNewer(entry.get().version(), installedVersion)) {
            return entry;
        }

        System.err.println("JLShell: ignoring cached update " + entry.get().version()
                + " because installed version " + installedVersion + " is newer or equal.");
        Files.deleteIfExists(entryFile);
        if (discardBundledBackup) {
            Files.deleteIfExists(bundled.resolveSibling(bundled.getFileName() + ".previous"));
        }
        return Optional.empty();
    }

    private static boolean isStrictlyNewer(String cachedVersion, String installedVersion) {
        Matcher cached = RELEASE_VERSION_PATTERN.matcher(cachedVersion == null ? "" : cachedVersion.strip());
        Matcher installed = RELEASE_VERSION_PATTERN.matcher(installedVersion == null ? "" : installedVersion.strip());
        if (!cached.matches()) {
            return false;
        }
        if (!installed.matches()) {
            return true;
        }
        return compareReleaseVersions(cached, installed) > 0;
    }

    private static int compareReleaseVersions(Matcher left, Matcher right) {
        for (int group = 1; group <= 3; group++) {
            int comparison = Integer.compare(
                    Integer.parseInt(left.group(group)),
                    Integer.parseInt(right.group(group))
            );
            if (comparison != 0) {
                return comparison;
            }
        }
        return comparePreRelease(left.group(4), right.group(4));
    }

    private static int comparePreRelease(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null || right.isBlank() ? 0 : 1;
        }
        if (right == null || right.isBlank()) {
            return -1;
        }

        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.min(leftParts.length, rightParts.length); index++) {
            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = leftPart.chars().allMatch(Character::isDigit);
            boolean rightNumeric = rightPart.chars().allMatch(Character::isDigit);
            int comparison;
            if (leftNumeric && rightNumeric) {
                comparison = Integer.compare(Integer.parseInt(leftPart), Integer.parseInt(rightPart));
            } else if (leftNumeric) {
                comparison = -1;
            } else if (rightNumeric) {
                comparison = 1;
            } else {
                comparison = leftPart.compareTo(rightPart);
            }
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static void promotePending(Path pending, Path current, Path previous, UpdateEntry entry, Path resolvedJarPath) throws IOException {
        Files.createDirectories(current.getParent());
        if (Files.isRegularFile(current)) {
            Files.copy(current, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // Write current.json pointing to the resolved jar path (may be the
        // bundled jar after an in-place copy, or the staging-area jar).
        UpdateEntry resolved = new UpdateEntry(entry.version(), resolvedJarPath, entry.sha256());
        writeEntry(current, resolved, false);
        Files.deleteIfExists(pending);
    }

    /**
     * Try to copy the staged update jar into the installation directory,
     * replacing the bundled application jar. Returns true on success.
     */
    private static boolean promotePendingByCopy(UpdateEntry entry, Path bundled) {
        if (!Files.isRegularFile(entry.jarPath())) {
            return false;
        }
        Path backup = bundled.resolveSibling(bundled.getFileName() + ".previous");
        try {
            if (Files.isRegularFile(bundled)) {
                Files.copy(bundled, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(entry.jarPath(), bundled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(bundled) != Files.size(entry.jarPath())
                    || !sha256(bundled).equalsIgnoreCase(entry.sha256())) {
                restoreBundledBackup(backup, bundled);
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("JLShell: cannot copy update to install dir (falling back to user updates): " + e.getMessage());
            restoreBundledBackup(backup, bundled);
            return false;
        }
    }

    private static void restoreBundledBackup(Path backup, Path bundled) {
        try {
            if (Files.isRegularFile(backup)) {
                Files.copy(backup, bundled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(backup);
            }
        } catch (Exception e) {
            System.err.println("JLShell: cannot restore bundled jar backup: " + e.getMessage());
        }
    }

    private static void rollbackUnconfirmedCurrent(Path current, Path previous, Path bundled) throws IOException {
        restoreBundledBackup(bundled.resolveSibling(bundled.getFileName() + ".previous"), bundled);
        if (Files.isRegularFile(previous)) {
            Files.copy(previous, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(previous);
        } else {
            Files.deleteIfExists(current);
        }
    }

    private static boolean isUnconfirmed(Path entryFile) {
        try {
            String json = Files.readString(entryFile, StandardCharsets.UTF_8);
            Matcher matcher = STARTUP_CONFIRMED_PATTERN.matcher(json);
            return matcher.find() && "false".equals(matcher.group(1));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Optional<UpdateEntry> readEntry(Path entryFile) {
        if (!Files.isRegularFile(entryFile)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(entryFile, StandardCharsets.UTF_8);
            String version = jsonValue(json, "version").orElse("");
            String jarPath = jsonValue(json, "jarPath").orElse("");
            String sha256 = jsonValue(json, "sha256").orElse("");
            if (version.isBlank() || jarPath.isBlank() || sha256.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new UpdateEntry(version, Path.of(jarPath), sha256));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void writeEntry(Path entryFile, UpdateEntry entry, boolean startupConfirmed) throws IOException {
        String json = "{\n"
                + "  \"version\": \"" + escapeJson(entry.version()) + "\",\n"
                + "  \"jarPath\": \"" + escapeJson(entry.jarPath().toAbsolutePath().toString()) + "\",\n"
                + "  \"sha256\": \"" + escapeJson(entry.sha256()) + "\",\n"
                + "  \"startupConfirmed\": " + startupConfirmed + "\n"
                + "}\n";
        Files.writeString(entryFile, json, StandardCharsets.UTF_8);
    }

    private static Optional<String> jsonValue(String json, String key) {
        Pattern pattern = Pattern.compile(JSON_STRING_PATTERN_TEMPLATE.pattern().formatted(Pattern.quote(key)));
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Optional.of(unescapeJson(matcher.group(1))) : Optional.empty();
    }

    private static boolean verifyEntry(UpdateEntry entry) {
        try {
            if (!Files.isRegularFile(entry.jarPath())) {
                return false;
            }
            return sha256(entry.jarPath()).equalsIgnoreCase(entry.sha256());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void invokeApplication(Path appJar, String[] args) throws Exception {
        URL[] urls = { appJar.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = Class.forName(APP_MAIN_CLASS, true, loader);
            Method main = mainClass.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        }
    }

    private static Path bundledApplicationJar() throws Exception {
        Path baseDir = launcherBaseDir();
        return baseDir.resolve(BUNDLED_APP_JAR).normalize();
    }

    private static Path launcherJar() throws Exception {
        return Path.of(BootstrapLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).normalize();
    }

    private static Path launcherBaseDir() throws Exception {
        Path codeSource = Path.of(BootstrapLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
    }

    private static Path updatesDir() {
        return Path.of(System.getProperty("user.home"), ".jlshell", "updates");
    }

    private static String launcherVersion() {
        String version = BootstrapLauncher.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "0.1.0" : version.replace(".RELEASE", "");
    }

    private static void configureMacApplicationName() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("apple.awt.application.name", "JLShell");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JLShell");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private record UpdateEntry(String version, Path jarPath, String sha256) {}
}
