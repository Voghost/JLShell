package com.jlshell.core.support;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.ui.ObservableValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Serialized host-state publication, including concurrent subscriptions and reentrant writes. */
public final class MutableValue<T> implements ObservableValue<T> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MutableValue.class);
    private volatile T value;
    private final List<Watch<T>> watchers = new ArrayList<>();
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private boolean delivering;
    private Object bindingToken;
    private Registration binding;

    public MutableValue(T initial) { value = initial; }
    @Override public T get() { return value; }

    public void set(T next) {
        boolean drain;
        synchronized (this) {
            if (bindingToken != null) throw new IllegalStateException("Value is bound");
            drain = enqueueChange(next);
        }
        if (drain) drain();
    }

    private boolean enqueueChange(T next) {
        if (Objects.equals(value, next)) return false;
        value = next;
        List<Watch<T>> targets = List.copyOf(watchers);
        pending.add(() -> targets.forEach(watch -> watch.deliver(next)));
        return startDelivery();
    }

    @Override public Registration subscribe(Consumer<? super T> listener) {
        Watch<T> watch = new Watch<>(Objects.requireNonNull(listener, "listener"));
        boolean drain;
        synchronized (this) {
            watchers.add(watch);
            T initial = value;
            pending.add(() -> watch.deliver(initial));
            drain = startDelivery();
        }
        if (drain) drain();
        return () -> {
            watch.closed.set(true);
            synchronized (this) { watchers.remove(watch); }
        };
    }

    private boolean startDelivery() {
        if (delivering) return false;
        delivering = true;
        return true;
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = pending.poll();
                if (next == null) { delivering = false; return; }
            }
            next.run();
        }
    }

    public void bind(ObservableValue<T> source) {
        Objects.requireNonNull(source, "source");
        if (source == this) throw new IllegalArgumentException("Cannot bind a value to itself");
        unbind();
        Object token = new Object();
        synchronized (this) { bindingToken = token; }
        Registration subscription;
        try {
            subscription = source.subscribe(next -> {
                boolean drain;
                synchronized (this) {
                    if (bindingToken != token) return;
                    drain = enqueueChange(next);
                }
                if (drain) drain();
            });
        } catch (RuntimeException | Error error) {
            synchronized (this) { if (bindingToken == token) bindingToken = null; }
            throw error;
        }
        synchronized (this) {
            if (bindingToken == token) { binding = subscription; return; }
        }
        subscription.close();
    }

    public void unbind() {
        Registration previous;
        synchronized (this) { bindingToken = null; previous = binding; binding = null; }
        if (previous != null) previous.close();
    }

    public synchronized boolean isBound() { return bindingToken != null; }

    private static final class Watch<T> {
        private final Consumer<? super T> listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Watch(Consumer<? super T> listener) { this.listener = listener; }
        private void deliver(T value) {
            if (closed.get()) return;
            try { listener.accept(value); }
            catch (RuntimeException error) { log.warn("Host state listener failed", error); }
        }
    }
}
