package com.jlshell.plugin.loader;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.jlshell.plugin.api.event.HostEvent;
import com.jlshell.plugin.api.event.HostEvents;
import com.jlshell.plugin.api.lifecycle.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 宿主内部事件总线；插件只能获得 scoped 的只读订阅视图。 */
public final class HostEventBusImpl {

    private static final Logger log = LoggerFactory.getLogger(HostEventBusImpl.class);

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Subscriber<?>>> subscribers =
            new ConcurrentHashMap<>();

    public HostEvents scoped(String ownerId) {
        return new ScopedHostEvents(ownerId);
    }

    public void publish(HostEvent event) {
        Objects.requireNonNull(event, "event");
        subscribers.forEach((eventType, listeners) -> {
            if (!eventType.isInstance(event)) {
                return;
            }
            listeners.forEach(listener -> dispatch(listener, event));
        });
    }

    public void clearOwner(String ownerId) {
        subscribers.values().forEach(list -> list.removeIf(subscriber -> subscriber.ownerId.equals(ownerId)));
        subscribers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private <E extends HostEvent> Registration subscribe(
            String ownerId, Class<E> eventType, Consumer<? super E> listener) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        Subscriber<E> subscriber = new Subscriber<>(ownerId, eventType, listener);
        subscribers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            CopyOnWriteArrayList<Subscriber<?>> listeners = subscribers.get(eventType);
            if (listeners != null) {
                listeners.remove(subscriber);
                if (listeners.isEmpty()) {
                    subscribers.remove(eventType, listeners);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <E extends HostEvent> void dispatch(Subscriber<?> raw, HostEvent event) {
        Subscriber<E> subscriber = (Subscriber<E>) raw;
        try {
            subscriber.listener.accept(subscriber.eventType.cast(event));
        } catch (RuntimeException error) {
            log.warn("Host event listener failed for {}", subscriber.ownerId, error);
        }
    }

    private record Subscriber<E extends HostEvent>(
            String ownerId,
            Class<E> eventType,
            Consumer<? super E> listener
    ) {
    }

    private final class ScopedHostEvents implements HostEvents, AutoCloseable {
        private final String ownerId;

        private ScopedHostEvents(String ownerId) {
            this.ownerId = ownerId;
        }

        @Override public boolean available() { return true; }

        @Override
        public <E extends HostEvent> Registration subscribe(
                Class<E> eventType, Consumer<? super E> listener) {
            return HostEventBusImpl.this.subscribe(ownerId, eventType, listener);
        }

        @Override public void close() {
            clearOwner(ownerId);
        }
    }
}
