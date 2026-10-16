package com.jlshell.ui.service;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.impl.PersistentFontProfileService;
import com.jlshell.plugin.api.ui.FontSpec;
import com.jlshell.plugin.loader.ScopedPluginFonts;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class HostPluginFontsTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }

    @Test void reflectsPersistedPreferencesAndPublishesInitialDistinctOrderedChanges() throws Exception {
        var settings = new Settings();
        var profiles = new PersistentFontProfileService(settings);
        var executor = Executors.newSingleThreadExecutor();
        var themes = FxThread.supplyAsync(() -> new ThemeService(settings, new ColorSchemeRegistry(null, null), executor))
                .get(10, TimeUnit.SECONDS);
        var fonts = new HostPluginFonts(profiles, themes);
        List<FontSpec> terminal = new CopyOnWriteArrayList<>(), ui = new CopyOnWriteArrayList<>();
        var first = fonts.onTerminalFontChanged(terminal::add);
        var second = fonts.onUiFontChanged(ui::add);
        drain();
        assertEquals(List.of(new FontSpec("SauceCodePro NFM", 13)), terminal);
        assertEquals(List.of(new FontSpec("", 13)), ui);
        profiles.updateActiveProfile(new FontProfile("Example Mono", 15.5, true, 1));
        profiles.updateActiveProfile(new FontProfile("Example Mono", 15.5, false, 2)); // Same visible font.
        profiles.updateActiveProfile(new FontProfile("Second Mono", 17, false, 2));
        FxThread.supplyAsync(() -> {
            settings.set("ui.font.family", "Example UI"); settings.set("ui.font.size", "16");
            themes.refreshUiFont(); themes.refreshUiFont(); return null;
        }).get(10, TimeUnit.SECONDS);
        drain();
        assertEquals(List.of(new FontSpec("SauceCodePro NFM", 13), new FontSpec("Example Mono", 15.5), new FontSpec("Second Mono", 17)), terminal);
        assertEquals(List.of(new FontSpec("", 13), new FontSpec("Example UI", 16)), ui);
        assertEquals(new FontSpec("Second Mono", 17), fonts.terminalFont());
        assertEquals(new FontSpec("Example UI", 16), fonts.uiFont());
        assertTrue(themes.uiStyle().contains("16px"));
        int reads = settings.reads;
        fonts.terminalFont(); fonts.uiFont(); assertEquals(reads, settings.reads, "Snapshot getters must not access storage");
        first.close(); second.close(); fonts.close(); executor.shutdownNow(); drain();
    }

    @Test void rapidUpdatesBeforeInitialDeliveryNeverRevertTheCurrentFont() throws Exception {
        var settings = new Settings(); var profiles = new PersistentFontProfileService(settings);
        var executor = Executors.newSingleThreadExecutor();
        var themes = FxThread.supplyAsync(() -> new ThemeService(settings, new ColorSchemeRegistry(null, null), executor))
                .get(10, TimeUnit.SECONDS);
        var fonts = new HostPluginFonts(profiles, themes);
        List<FontSpec> received = new CopyOnWriteArrayList<>();
        FxThread.supplyAsync(() -> {
            fonts.onTerminalFontChanged(received::add);
            profiles.updateActiveProfile(new FontProfile("First", 16, true, 1));
            profiles.updateActiveProfile(new FontProfile("Latest", 18, true, 1));
            assertEquals(new FontSpec("Latest", 18), fonts.terminalFont());
            assertTrue(received.isEmpty(), "Callbacks must not execute inside the preference write");
            return null;
        }).get(10, TimeUnit.SECONDS);
        drain();
        assertEquals(List.of(new FontSpec("Latest", 18)), received);
        fonts.close(); executor.shutdownNow(); drain();
    }

    @Test void pendingInitialDeliveryIsCancelledAndFailingSubscriberCannotBreakOthers() throws Exception {
        var settings = new Settings(); var profiles = new PersistentFontProfileService(settings);
        var executor = Executors.newSingleThreadExecutor();
        var themes = FxThread.supplyAsync(() -> new ThemeService(settings, new ColorSchemeRegistry(null, null), executor))
                .get(10, TimeUnit.SECONDS);
        var fonts = new HostPluginFonts(profiles, themes);
        var scoped = new ScopedPluginFonts(fonts);
        List<FontSpec> received = new CopyOnWriteArrayList<>();
        FxThread.supplyAsync(() -> {
            scoped.onUiFontChanged(received::add); scoped.close(); return null;
        }).get(10, TimeUnit.SECONDS);
        drain(); assertTrue(received.isEmpty());
        fonts.onTerminalFontChanged(font -> { throw new IllegalStateException("test subscriber failure"); });
        fonts.onTerminalFontChanged(received::add); drain();
        profiles.updateActiveProfile(new FontProfile("Next", 19, true, 1)); drain();
        assertEquals(2, received.size());
        fonts.close();
        profiles.updateActiveProfile(new FontProfile("After close", 21, true, 1)); drain();
        assertEquals(2, received.size()); assertFalse(fonts.available());
        executor.shutdownNow();
    }

    private static void drain() throws Exception { FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS); }
    private static class Settings implements AppSettingsService {
        final Map<String, String> values = new ConcurrentHashMap<>();
        volatile int reads;
        public Optional<String> get(String key) { reads++; return Optional.ofNullable(values.get(key)); }
        public void set(String key, String value) { values.put(key, value); }
        public void remove(String key) { values.remove(key); }
    }
}
