package com.jlshell.plugin.loader.store;

import com.jlshell.plugin.api.PluginScope;

public record PluginStoreUpdate(boolean updateAvailable, String pluginId, PluginScope scope,
                                String currentVersion, PluginStoreVersion latest, String downloadUrl) {
}
