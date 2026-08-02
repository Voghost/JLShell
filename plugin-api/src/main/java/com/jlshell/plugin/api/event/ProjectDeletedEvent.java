package com.jlshell.plugin.api.event;

import java.time.Instant;

public record ProjectDeletedEvent(String projectId, Instant occurredAt) implements HostEvent {
}
