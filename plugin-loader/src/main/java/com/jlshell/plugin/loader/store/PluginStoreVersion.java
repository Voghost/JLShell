package com.jlshell.plugin.loader.store;

import java.time.Instant;

/** 商店返回的一个已发布插件版本。 */
public record PluginStoreVersion(
        String version,
        String entrypoint,
        String minHostVersion,
        String maxHostVersion,
        String releaseNotes,
        String sha256,
        long size,
        String status,
        long downloads,
        Instant publishedAt
) {
    public boolean approved() {
        return "APPROVED".equals(status);
    }
}
