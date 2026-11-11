package com.jlshell.app;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);
    private StartupDiagnostics() {}

    static void logBeforeLaunch() {
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
            log.info("Startup diagnostics: launcherJar={}, activeJar={}, runtimeJar={}, bundledJar={}, updateDir={}",
                    property("jlshell.launcher.jar"),
                    property("jlshell.active.jar"),
                    property("jlshell.runtime.jar"),
                    property("jlshell.bundled.jar"),
                    firstNonBlank(property("jlshell.update.dir"), property("jlshell.updates.dir")));
        } catch (Exception e) {
            log.warn("Startup diagnostics failed", e);
        }
    }

    private static String appVersion() {
        String version = JlShellDesktopApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
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
