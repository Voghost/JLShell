package com.jlshell.ui.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class JlshellDefaults {

    private static final String RESOURCE = "/jlshell.properties";
    private static final String FALLBACK_BASE_URL = "https://jlshell.oomn.net";
    private static final Properties PROPERTIES = loadProperties();

    private JlshellDefaults() {}

    public static String updateBaseUrl() {
        return property("jlshell.updates.base-url", FALLBACK_BASE_URL);
    }

    public static String accountBaseUrl() {
        return property("jlshell.account.base-url", updateBaseUrl());
    }

    public static int statusWarningPercent() {
        return intProperty("jlshell.status.warning-percent", 70);
    }

    public static int statusDangerPercent() {
        return intProperty("jlshell.status.danger-percent", 90);
    }

    private static String property(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static int intProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(property(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (var input = JlshellDefaults.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Built-in fallbacks keep the app usable if the resource is missing.
        }
        Path external = externalConfigPath();
        if (Files.isRegularFile(external)) {
            try (var input = Files.newInputStream(external)) {
                properties.load(input);
            } catch (IOException ignored) {
                // Keep classpath defaults when the optional user configuration is unreadable.
            }
        }
        return properties;
    }

    private static Path externalConfigPath() {
        String explicit = System.getProperty("jlshell.config.file", "").strip();
        if (!explicit.isEmpty()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".jlshell", "jlshell.properties")
                .toAbsolutePath().normalize();
    }
}
