package com.jlshell.ui.config;

import java.io.IOException;
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

    private static String property(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.strip();
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
        return properties;
    }
}
