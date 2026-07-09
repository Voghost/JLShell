package com.jlshell.app;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);
    private static final String[] WINDOWS_JAVAFX_DLLS = {
            "glass.dll",
            "prism_d3d.dll",
            "javafx_font.dll"
    };

    private StartupDiagnostics() {}

    static void logBeforeJavaFxLaunch() {
        try {
            log.info("Startup diagnostics: appVersion={}, launcherVersion={}, os={} {}, arch={}, java={} {}, javaHome={}",
                    appVersion(),
                    property("jlshell.launcher.version"),
                    property("os.name"),
                    property("os.version"),
                    property("os.arch"),
                    property("java.vendor"),
                    property("java.runtime.version"),
                    property("java.home"));
            log.info("Startup diagnostics: launcherJar={}, activeJar={}, bundledJar={}, updateDir={}",
                    property("jlshell.launcher.jar"),
                    property("jlshell.active.jar"),
                    property("jlshell.bundled.jar"),
                    firstNonBlank(property("jlshell.update.dir"), property("jlshell.updates.dir")));
            log.info("Startup diagnostics: javafx.runtime.version={}, javafx.version={}, javafx.graphics.module={}, glassScreenResource={}, nativeLibLoaderResource={}",
                    property("javafx.runtime.version"),
                    property("javafx.version"),
                    moduleVersion("javafx.graphics"),
                    classResource("com.sun.glass.ui.Screen"),
                    classResource("com.sun.glass.utils.NativeLibLoader"));
            log.info("Startup diagnostics: env JAVA_HOME={}, JAVAFX_HOME={}, JAVA_TOOL_OPTIONS={}, _JAVA_OPTIONS={}, java.library.path.relevant={}",
                    envValue("JAVA_HOME"),
                    envValue("JAVAFX_HOME"),
                    sensitiveEnvValue("JAVA_TOOL_OPTIONS"),
                    sensitiveEnvValue("_JAVA_OPTIONS"),
                    relevantLibraryPath());
            logWindowsNativeCandidates();
        } catch (Exception e) {
            log.warn("Startup diagnostics failed", e);
        }
    }

    private static void logWindowsNativeCandidates() {
        if (!property("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Path javafxBin = Path.of(property("java.home"), "bin", "javafx");
        for (String dll : WINDOWS_JAVAFX_DLLS) {
            log.info("Startup diagnostics: nativeCandidate {}", describeFile(javafxBin.resolve(dll)));
        }
    }

    private static String appVersion() {
        String version = JlShellDesktopApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private static String moduleVersion(String moduleName) {
        return ModuleLayer.boot()
                .findModule(moduleName)
                .map(module -> module.getDescriptor().rawVersion()
                        .map(version -> module.getName() + "/" + version)
                        .orElse(module.getName() + "/unknown"))
                .orElse("not-found");
    }

    private static String classResource(String className) {
        try {
            Class<?> type = loadClassWithoutInit(className);
            URL resource = type.getResource(type.getSimpleName() + ".class");
            return resource == null ? "not-found" : resource.toString();
        } catch (ClassNotFoundException e) {
            return "not-found";
        }
    }

    private static Class<?> loadClassWithoutInit(String className) throws ClassNotFoundException {
        try {
            return Class.forName(className, false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException ignored) {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            return Class.forName(className, false, contextLoader);
        }
    }

    private static String relevantLibraryPath() {
        String libraryPath = property("java.library.path");
        String separator = property("path.separator");
        if (libraryPath.isBlank() || separator.isBlank()) {
            return "empty";
        }
        String[] entries = libraryPath.split(java.util.regex.Pattern.quote(separator));
        String joined = Stream.of(entries)
                .filter(entry -> {
                    String lower = entry.toLowerCase(Locale.ROOT);
                    return lower.contains("jlshell")
                            || lower.contains("java")
                            || lower.contains("jfx")
                            || lower.contains("openjfx")
                            || lower.contains("liberica");
                })
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + separator + right)
                .orElse("none");
        return abbreviate(joined, 800);
    }

    private static String envValue(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return "unset";
        }
        return abbreviate(value, 300);
    }

    private static String sensitiveEnvValue(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return "unset";
        }
        return abbreviate(maskSensitive(value), 300);
    }

    private static String maskSensitive(String value) {
        return value.replaceAll("(?i)(password|passwd|pwd|token|secret|key)=([^\\s;]+)", "$1=<masked>");
    }

    private static String describeFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return path + " exists=false";
            }
            return path + " exists=true size=" + Files.size(path) + " sha256=" + sha256Prefix(path);
        } catch (Exception e) {
            return path + " status=error:" + e.getClass().getSimpleName();
        }
    }

    private static String sha256Prefix(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private static String property(String name) {
        return System.getProperty(name, "");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
