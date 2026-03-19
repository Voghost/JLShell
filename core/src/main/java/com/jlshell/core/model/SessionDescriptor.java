package com.jlshell.core.model;

import java.time.Instant;

/**
 * 面向 UI 和管理界面的会话只读视图。
 */
public record SessionDescriptor(
        SessionId sessionId,
        String displayName,
        ConnectionTarget target,
        SessionState state,
        Instant connectedAt
) {
}
