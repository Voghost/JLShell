package com.jlshell.ui.theme;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.ui.support.FxThread;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class TerminalThemeFollowingTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }

    @Test void remembersBothKindsAndIgnoresStaleStartupResult() throws Exception {
        Map<String, String> values = new HashMap<>();
        var settings = new AppSettingsService() {
            public Optional<String> get(String key) { return Optional.ofNullable(values.get(key)); }
            public void set(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        };
        var registry = new ColorSchemeRegistry(null, null) {
            public Optional<TerminalColorScheme> findByName(String name) {
                return Optional.of(copyScheme(name.equals("custom-light") ? TerminalColorScheme.light() : TerminalColorScheme.dark(), name));
            }
        };
        var startup = new AtomicReference<Runnable>();
        var executor = new AbstractExecutorService() {
            public void execute(Runnable task) { startup.set(task); }
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        var service = FxThread.supplyAsync(() -> {
            var theme = new ThemeService(settings, registry, executor);
            theme.setActiveColorScheme(registry.copyScheme(TerminalColorScheme.dark(), "custom-dark"));
            theme.setTheme(AppTheme.LIGHT);
            assertTrue(theme.activeColorScheme().isLight());
            theme.setActiveColorScheme(registry.copyScheme(TerminalColorScheme.light(), "custom-light"));
            theme.setTheme(AppTheme.DARK);
            assertEquals("custom-dark", theme.activeColorScheme().name());
            theme.setTheme(AppTheme.LIGHT);
            assertEquals("custom-light", theme.activeColorScheme().name());
            return theme;
        }).get(10, TimeUnit.SECONDS);
        startup.get().run();
        FxThread.supplyAsync(() -> {
            assertEquals("custom-light", service.activeColorScheme().name());
            assertEquals("custom-light", values.get("terminal.colorScheme.active"));
            assertEquals("custom-dark", values.get("terminal.colorScheme.DARK"));
            return null;
        }).get(10, TimeUnit.SECONDS);
    }
}
