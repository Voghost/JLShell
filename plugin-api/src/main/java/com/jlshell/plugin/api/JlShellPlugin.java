package com.jlshell.plugin.api;

/**
 * Main SPI interface for JLShell plugins.
 * Discovered via {@link java.util.ServiceLoader}.
 */
public interface JlShellPlugin {

    /** Unique reverse-domain identifier, e.g. {@code com.example.my-plugin}. */
    String id();

    String displayName();

    String version();

    String description();

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
