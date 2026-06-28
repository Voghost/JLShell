package com.jlshell.plugin.api;

public record PluginMetadata(
        String id,
        String displayName,
        String version,
        String author,
        PluginScope scope,
        String minHostVersionInclusive,
        String maxHostVersionInclusive,
        PluginCompatibilityStatus compatibilityStatus,
        String compatibilityWarning,
        String description
) {}
