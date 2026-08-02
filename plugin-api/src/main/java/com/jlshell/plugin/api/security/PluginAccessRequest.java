package com.jlshell.plugin.api.security;

import com.jlshell.plugin.api.PluginScope;

public record PluginAccessRequest(
        PluginOperation operation,
        PluginScope scope,
        String sessionId,
        String pluginId,
        String capability
) {
}
