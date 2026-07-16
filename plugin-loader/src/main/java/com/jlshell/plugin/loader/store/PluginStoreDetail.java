package com.jlshell.plugin.loader.store;

import java.util.List;

public record PluginStoreDetail(PluginStoreListing plugin, List<PluginStoreVersion> versions) {
    public PluginStoreDetail {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
