package com.jlshell.plugin.loader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jlshell.plugin.api.event.HostEvent;
import com.jlshell.plugin.api.event.HostEvents;
import com.jlshell.plugin.api.event.ProjectCreatedEvent;
import org.junit.jupiter.api.Test;

class HostEventBusImplTest {

    @Test
    void dispatchesByTypeAndScopeCanReleaseAllSubscriptions() throws Exception {
        HostEventBusImpl bus = new HostEventBusImpl();
        HostEvents events = bus.scoped("plugin-a");
        List<HostEvent> received = new ArrayList<>();
        events.subscribe(HostEvent.class, received::add);
        events.subscribe(ProjectCreatedEvent.class, received::add);
        ProjectCreatedEvent event = new ProjectCreatedEvent("p1", "demo", "", Instant.now());

        bus.publish(event);
        assertThat(received).containsExactly(event, event);

        ((AutoCloseable) events).close();
        bus.publish(new ProjectCreatedEvent("p2", "other", "", Instant.now()));
        assertThat(received).hasSize(2);
    }

    @Test
    void failingListenerDoesNotBlockOtherSubscribers() {
        HostEventBusImpl bus = new HostEventBusImpl();
        List<ProjectCreatedEvent> received = new ArrayList<>();
        bus.scoped("broken").subscribe(ProjectCreatedEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        bus.scoped("healthy").subscribe(ProjectCreatedEvent.class, received::add);

        bus.publish(new ProjectCreatedEvent("p1", "demo", "", Instant.now()));
        assertThat(received).hasSize(1);
    }
}
