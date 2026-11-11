package com.jlshell.plugin.api.ui;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.Objects;
import java.util.function.Consumer;

/** Toolkit-independent read-only host state. */
public interface ObservableValue<T> {
    T get();

    /**
     * Delivers an initial snapshot and distinct changes in order. Callbacks may run on a
     * publisher thread. UI consumers must dispatch to EDT. Close to stop delivery.
     */
    Registration subscribe(Consumer<? super T> listener);

    static <T> ObservableValue<T> constant(T value) {
        return new ObservableValue<>() {
            public T get() { return value; }
            public Registration subscribe(Consumer<? super T> listener) {
                Objects.requireNonNull(listener, "listener").accept(value);
                return Registration.noop();
            }
        };
    }
}
