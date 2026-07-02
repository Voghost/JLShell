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
 * update jar from the user update directory when available, otherwise falls
 * back to the bundled application jar shipped inside the installer.</p>
 */
public final class BootstrapLauncher {

    private static final String APP_MAIN_CLASS = "com.jlshell.app.Launcher";
    private static final String BUNDLED_APP_JAR = "app/jlshell-app-bundled.jar";
    private static final Pattern JSON_STRING_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STARTUP_CONFIRMED_PATTERN =
            Pattern.compile("\"startupConfirmed\"\\s*:\\s*(true|false)");

    private BootstrapLauncher() {}

    public static void main(String[] args) {
        configureMacApplicationName();

        try {
            Path selectedJar = selectApplicationJar();
            System.setProperty("jlshell.launcher.version", launcherVersion());
            System.setProperty("jlshell.active.jar", selectedJar.toAbsolutePath().toString());
            invokeApplication(selectedJar, args);
        } catch (Throwable error) {
            System.err.println("JLShell failed to launch: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path selectApplicationJar() throws Exception {
        return selectApplicationJar(updatesDir(), bundledApplicationJar());
    }

    static Path selectApplicationJar(Path updatesDir, Path bundled) throws Exception {
        Path current = updatesDir.resolve("current.json");
        Path pending = updatesDir.resolve("pending.json");
        Path previous = updatesDir.resolve("previous.json");

        if (Files.isRegularFile(current) && isUnconfirmed(current)) {
            rollbackUnconfirmedCurrent(current, previous);
        }

        if (Files.isRegularFile(pending)) {
            Optional<UpdateEntry> pendingEntry = readEntry(pending);
            if (pendingEntry.isPresent() && verifyEntry(pendingEntry.get())) {
                promotePending(pending, current, previous, pendingEntry.get());
                return pendingEntry.get().jarPath();
            }
        }

        Optional<UpdateEntry> currentEntry = readEntry(current);
        if (currentEntry.isPresent() && verifyEntry(currentEntry.get())) {
            return currentEntry.get().jarPath();
        }

        if (!Files.isRegularFile(bundled)) {
            throw new IOException("Bundled application jar not found: " + bundled);
        }
        return bundled;
    }

    private static void promotePending(Path pending, Path current, Path previous, UpdateEntry entry) throws IOException {
        Files.createDirectories(current.getParent());
        if (Files.isRegularFile(current)) {
            Files.copy(current, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        writeEntry(current, entry, false);
        Files.deleteIfExists(pending);
    }

    private static void rollbackUnconfirmedCurrent(Path current, Path previous) throws IOException {
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
        Path codeSource = Path.of(BootstrapLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path baseDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
        return baseDir.resolve(BUNDLED_APP_JAR).normalize();
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
