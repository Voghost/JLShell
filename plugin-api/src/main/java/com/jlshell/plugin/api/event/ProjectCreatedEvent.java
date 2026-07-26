package com.jlshell.plugin.api.event;

import java.time.Instant;

public record ProjectCreatedEvent(
        String projectId,
        String name,
        String description,
        Instant occurredAt
) implements HostEvent {
}
