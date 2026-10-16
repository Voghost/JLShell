package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.plugin.api.ui.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ScopedPluginFontsTest {
    @Test void subscriptionsAreRevokedPerContextAndQueuedCallbacksAreIgnored() {
        Source source = new Source();
        ScopedPluginFonts first = new ScopedPluginFonts(source), second = new ScopedPluginFonts(source);
        List<FontSpec> a = new ArrayList<>(), b = new ArrayList<>();
        var manual = first.onUiFontChanged(a::add);
        manual.close(); manual.close();
        first.onTerminalFontChanged(a::add); second.onTerminalFontChanged(b::add);
        var queued = source.listeners.getFirst();
        first.close(); first.close();
        queued.accept(new FontSpec("Queued", 19));
        source.publish(new FontSpec("Next", 17));
        assertTrue(a.isEmpty()); assertEquals(List.of(new FontSpec("Next", 17)), b);
        assertEquals(1, source.listeners.size());
        assertFalse(first.available()); assertTrue(second.available());
        assertThrows(IllegalStateException.class, first::terminalFont);
        assertThrows(IllegalStateException.class, () -> first.onUiFontChanged(a::add));
        second.close(); assertTrue(source.listeners.isEmpty());
    }

    @Test void sessionManagerAttachesServiceWithoutReplacingSubscriptionsOnRepeatedAdoption() {
        Source source = new Source();
        var manager = new PluginManager("/not/exist"); manager.setFontService(source);
        var context = new DefaultPluginContext("test.font", "session", new CapabilityRegistryImpl(), Optional.empty(),
                new DefaultPluginContext.Callbacks() {
                    public void openTab(String title, javafx.scene.Node node) {}
                    public void closeTab() {}
                    public void updateTabTitle(String title) {}
                    public String resolveI18n(String key, String fallback) { return fallback; }
                });
        manager.adoptContext("session", "test.font", context);
        List<FontSpec> received = new ArrayList<>();
        context.fonts().onUiFontChanged(received::add);
        manager.adoptContext("session", "test.font", context);
        assertTrue(context.fonts().available());
        source.publish(new FontSpec("Live", 15)); assertEquals(1, received.size());
        manager.deactivateSession("session");
        assertTrue(source.listeners.isEmpty()); assertFalse(context.fonts().available());
    }

    static class Source implements PluginFonts {
        final List<Consumer<FontSpec>> listeners = new ArrayList<>();
        public boolean available() { return true; }
        public FontSpec terminalFont() { return new FontSpec("Mono", 14); }
        public FontSpec uiFont() { return new FontSpec("", 13); }
        public Registration onTerminalFontChanged(Consumer<FontSpec> listener) { listeners.add(listener); return () -> listeners.remove(listener); }
        public Registration onUiFontChanged(Consumer<FontSpec> listener) { return onTerminalFontChanged(listener); }
        void publish(FontSpec font) { List.copyOf(listeners).forEach(listener -> listener.accept(font)); }
    }
}
