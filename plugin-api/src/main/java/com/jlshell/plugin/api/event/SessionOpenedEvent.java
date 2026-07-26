package com.jlshell.plugin.api.event;

import java.time.Instant;

public record SessionOpenedEvent(
        String sessionId,
        String connectionId,
        String projectId,
        Instant occurredAt
) implements HostEvent {
}
