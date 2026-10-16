package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.ui.FontSpec;
import com.jlshell.plugin.api.ui.PluginFonts;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** One context's subscriptions; revocation never closes the shared host service. */
public final class ScopedPluginFonts implements PluginFonts, AutoCloseable {
    private final PluginFonts source;
    private final java.util.Set<Registration> registrations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScopedPluginFonts(PluginFonts source) { this.source = Objects.requireNonNull(source); }
    @Override public boolean available() { return !closed.get() && source.available(); }
    @Override public FontSpec terminalFont() { requireOpen(); return source.terminalFont(); }
    @Override public FontSpec uiFont() { requireOpen(); return source.uiFont(); }
    @Override public Registration onTerminalFontChanged(Consumer<FontSpec> listener) { return subscribe(source::onTerminalFontChanged, listener); }
    @Override public Registration onUiFontChanged(Consumer<FontSpec> listener) { return subscribe(source::onUiFontChanged, listener); }

    private Registration subscribe(Function<Consumer<FontSpec>, Registration> register, Consumer<FontSpec> listener) {
        requireOpen(); Objects.requireNonNull(listener, "listener");
        AtomicBoolean removed = new AtomicBoolean();
        Registration delegate = register.apply(font -> { if (!closed.get() && !removed.get()) listener.accept(font); });
        Registration registration = new Registration() {
            public void close() {
                if (!removed.compareAndSet(false, true)) return;
                registrations.remove(this); delegate.close();
            }
        };
        registrations.add(registration);
        if (closed.get()) registration.close();
        return registration;
    }

    private void requireOpen() { if (closed.get()) throw new IllegalStateException("Plugin font context stopped"); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Registration registration : registrations) {
            try { registration.close(); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(ScopedPluginFonts.class).warn("Cannot close plugin font subscription", error);
            }
        }
        registrations.clear();
    }
}
