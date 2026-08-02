package com.jlshell.plugin.api.event;

import java.time.Instant;

public record ConnectionCreatedEvent(
        String connectionId,
        String projectId,
        String displayName,
        String host,
        int port,
        Instant occurredAt
) implements HostEvent {
}
