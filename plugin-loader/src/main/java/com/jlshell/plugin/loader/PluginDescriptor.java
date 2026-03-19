package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.JlShellPlugin;

/**
 * Runtime metadata for a discovered plugin.
 */
public record PluginDescriptor(
        String id,
        String displayName,
        String version,
        String description,
        boolean requiresSshSession,
        JlShellPlugin instance
) {}
