package com.jlshell.ui.service;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.ui.FontSpec;
import com.jlshell.plugin.api.ui.PluginFonts;
import com.jlshell.ui.theme.ThemeService;
import java.awt.EventQueue;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Immutable snapshots and serialized notifications, without font enumeration or disk reads in getters. */
public final class HostPluginFonts implements PluginFonts, AutoCloseable {
    private final FontStream terminal;
    private final FontStream ui;
    private final AutoCloseable terminalSubscription;
    private final Registration uiSubscription;
    private volatile boolean closed;

    public HostPluginFonts(FontProfileService profiles, ThemeService themes) {
        terminal = new FontStream(terminalSpec(profiles.activeProfile()));
        ui = new FontStream(uiSpec(themes.uiFont()));
        terminalSubscription = profiles.onChanged(profile -> terminal.publish(terminalSpec(profile)));
        uiSubscription = themes.onUiFontChanged(font -> ui.publish(uiSpec(font)));
    }

    private static FontSpec terminalSpec(FontProfile profile) { return new FontSpec(profile.family(), profile.size()); }
    private static FontSpec uiSpec(ThemeService.UiFont font) {
        return new FontSpec("System".equalsIgnoreCase(font.family()) ? "" : font.family(), font.size());
    }

    @Override public boolean available() { return !closed; }
    @Override public FontSpec terminalFont() { requireOpen(); return terminal.value.font(); }
    @Override public FontSpec uiFont() { requireOpen(); return ui.value.font(); }
    @Override public Registration onTerminalFontChanged(Consumer<FontSpec> listener) { return terminal.subscribe(listener); }
    @Override public Registration onUiFontChanged(Consumer<FontSpec> listener) { return ui.subscribe(listener); }

    private void requireOpen() { if (closed) throw new IllegalStateException("Host fonts stopped"); }

    private record FontValue(FontSpec font, long revision) {}

    private final class FontStream {
        private volatile FontValue value;
        private final CopyOnWriteArrayList<Watch> watches = new CopyOnWriteArrayList<>();
        FontStream(FontSpec initial) { value = new FontValue(initial, 0); }
        Registration subscribe(Consumer<FontSpec> listener) {
            Objects.requireNonNull(listener, "listener");
            requireOpen();
            Watch watch = new Watch(listener);
            // A subscription starts with the latest snapshot. Revisions suppress older queued events.
            EventQueue.invokeLater(() -> {
                if (closed || watch.closed.get()) return;
                watches.add(watch);
                watch.deliver(value);
            });
            return () -> { watch.closed.set(true); watches.remove(watch); };
        }
        synchronized void publish(FontSpec next) {
            if (closed || value.font().equals(next)) return;
            FontValue published = new FontValue(next, value.revision() + 1);
            value = published;
            EventQueue.invokeLater(() -> {
                if (!closed) for (Watch watch : watches) watch.deliver(published);
            });
        }
    }

    private final class Watch {
        final Consumer<FontSpec> listener;
        final AtomicBoolean closed = new AtomicBoolean();
        private long lastRevision = -1; // EDT only.
        Watch(Consumer<FontSpec> listener) { this.listener = listener; }
        void deliver(FontValue value) {
            if (HostPluginFonts.this.closed || closed.get() || value.revision() <= lastRevision) return;
            lastRevision = value.revision();
            try { listener.accept(value.font()); }
            catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(HostPluginFonts.class).warn("Plugin font listener failed", error);
            }
        }
    }

    @Override public void close() {
        closed = true;
        try { terminalSubscription.close(); }
        catch (Exception error) { org.slf4j.LoggerFactory.getLogger(HostPluginFonts.class).warn("Cannot remove font listener", error); }
        terminal.watches.clear(); ui.watches.clear();
        uiSubscription.close();
    }
}
