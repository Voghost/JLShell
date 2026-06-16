package com.jlshell.ui.model;

import java.time.Instant;

/**
 * 最近会话列表项模型。
 */
public record RecentSessionProfile(
        String connectionId,
        String displayName,
        String host,
        int port,
        String username,
        String connectionType,
        Instant openedAt,
        Instant closedAt,
        String state
) {

    public String summary() {
        if ("LOCAL_SHELL".equals(connectionType)) return "Local Shell";
        return username + "@" + host + ":" + port;
    }
}
