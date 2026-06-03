package com.jlshell.plugin.api;

import java.util.Locale;

/**
 * Main SPI interface for JLShell plugins.
 * Discovered via {@link java.util.ServiceLoader}.
 */
public interface JlShellPlugin {

    /** Unique reverse-domain identifier, e.g. {@code com.example.my-plugin}. */
    String id();

    String displayName();

    /** Locale-aware display name. Override to return a translated name from the plugin's own ResourceBundle. */
    default String displayName(Locale locale) { return displayName(); }

    String version();

    String description();

    /** Locale-aware description. Override to return a translated description from the plugin's own ResourceBundle. */
    default String description(Locale locale) { return description(); }

    /** Whether this plugin requires an active SSH session to function. */
    boolean requiresSshSession();

    void activate(PluginContext context);

    void deactivate();

    /**
     * Returns the plugin's UI view, or {@code null} if the plugin has no workspace tab.
     */
    default PluginView view() {
        return null;
    }
}
