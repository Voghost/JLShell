package com.jlshell.program.plugin.loader;

import com.jlshell.plugin.api.JlShellProgramPlugin;
import com.jlshell.plugin.api.PluginCompatibilityStatus;
import com.jlshell.plugin.api.PluginMetadata;
import com.jlshell.plugin.api.PluginScope;
import com.jlshell.plugin.api.ProgramPluginContext;

public record ProgramPluginDescriptor(
        String id,
        String displayName,
        String version,
        String author,
        String description,
        String minHostVersionInclusive,
        String maxHostVersionInclusive,
        PluginCompatibilityStatus compatibilityStatus,
        String compatibilityWarning,
        JlShellProgramPlugin instance,
        ProgramPluginContext context
) {
    public PluginMetadata metadata() {
        return new PluginMetadata(
                id,
                displayName,
                version,
                author,
                PluginScope.PROGRAM,
                minHostVersionInclusive,
                maxHostVersionInclusive,
                compatibilityStatus,
                compatibilityWarning,
                description
        );
    }
}
