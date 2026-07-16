package com.jlshell.plugin.loader.store;

import com.jlshell.plugin.api.PluginScope;
import java.time.Instant;

/** 公开插件列表中的一个条目。 */
public record PluginStoreListing(
        String pluginId,
        PluginScope scope,
        String listingStatus,
        String displayName,
        String description,
        String author,
        String iconUrl,
        String latestVersion,
        String minHostVersion,
        String maxHostVersion,
        long downloads,
        Instant updatedAt
) {
}
