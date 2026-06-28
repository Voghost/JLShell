package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginCompatibilityStatus;
import com.jlshell.plugin.api.PluginMetadata;
import com.jlshell.plugin.api.PluginScope;

/**
 * Runtime metadata for a discovered plugin.
 */
public record PluginDescriptor(
        String id,
        String displayName,
        String version,
        String author,
        String description,
        boolean requiresSshSession,
        String minHostVersionInclusive,
        String maxHostVersionInclusive,
        PluginCompatibilityStatus compatibilityStatus,
        String compatibilityWarning,
        JlShellPlugin instance
) {
    public PluginMetadata metadata() {
        return new PluginMetadata(
                id,
                displayName,
                version,
                author,
                PluginScope.SESSION,
                minHostVersionInclusive,
                maxHostVersionInclusive,
                compatibilityStatus,
                compatibilityWarning,
                description
        );
    }
}
