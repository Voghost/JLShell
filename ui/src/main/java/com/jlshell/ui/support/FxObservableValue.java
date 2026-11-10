package com.jlshell.ui.support;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.ui.ObservableValue;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Adapter for project fields while the remaining host form models are migrated to Swing. */
public final class FxObservableValue {
    private FxObservableValue() { }

    public static <T> ObservableValue<T> adapt(javafx.beans.value.ObservableValue<T> source) {
        return new ObservableValue<>() {
            public T get() { return source.getValue(); }
            public Registration subscribe(Consumer<? super T> listener) {
                Objects.requireNonNull(listener, "listener");
                AtomicBoolean closed = new AtomicBoolean();
                javafx.beans.value.ChangeListener<T> adapter = (observable, previous, next) -> {
                    if (!closed.get()) listener.accept(next);
                };
                FxThread.run(() -> {
                    if (closed.get()) return;
                    source.addListener(adapter);
                    listener.accept(source.getValue());
                });
                return () -> {
                    closed.set(true);
                    FxThread.run(() -> source.removeListener(adapter));
                };
            }
        };
    }
}
