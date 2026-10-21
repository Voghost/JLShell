package com.jlshell.ui.swing;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.DeferredSettingsPage;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingPreferencesLoadingTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
        edt(() -> { SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13); return null; });
    }

    @Test void searchLoadsOnlyVisitedPagesAndRetainsUnsavedEditors() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger initialLoads = new AtomicInteger(), secondLoads = new AtomicInteger();
        var finished = new CompletableFuture<Void>();
        try {
            Platform.runLater(() -> {
                try {
                    var settings = new AppSettingsService() {
                        public Optional<String> get(String key) { return Optional.empty(); }
                        public void set(String key, String value) {}
                        public void remove(String key) {}
                    };
                    var registry = new ColorSchemeRegistry(null, null) {
                        @Override public Optional<TerminalColorScheme> findByName(String name) { return Optional.empty(); }
                    };
                    var themes = new ThemeService(settings, registry, executor);
                    Tab unused = new Tab("Unvisited", new DeferredSettingsPage(() -> {
                        throw new AssertionError("An unvisited page must not be initialized");
                    }, "unused"));
                    Tab initial = new Tab("Initial", new DeferredSettingsPage(() -> {
                        initialLoads.incrementAndGet();
                        TextField editor = new TextField("original"); editor.setId("lazy.editor");
                        return new VBox(editor);
                    }, "initial-search"));
                    Tab second = new Tab("Second", new DeferredSettingsPage(() -> {
                        secondLoads.incrementAndGet();
                        TextField editor = new TextField("second"); editor.setId("lazy.second");
                        return new VBox(editor);
                    }, "needle"));
                    TabPane tabs = new TabPane(unused, initial, second);
                    tabs.getSelectionModel().select(initial);
                    SwingPreferencesDialog.showAndWait(tabs, new Button(), new I18nService(Locale.ENGLISH), themes, () -> {});
                    finished.complete(null);
                } catch (Throwable failure) { finished.completeExceptionally(failure); }
            });
            JDialog dialog = await(() -> java.util.Arrays.stream(Window.getWindows())
                    .filter(w -> w instanceof JDialog && w.isShowing() && "jlshell.settings".equals(w.getName()))
                    .map(JDialog.class::cast).findFirst().orElse(null));
            assertEquals(1, initialLoads.get()); assertEquals(0, secondLoads.get());
            JTextField editor = edt(() -> (JTextField) named(dialog, "lazy.editor"));
            edt(() -> { editor.setText("unsaved"); return null; });
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
            edt(() -> { ((JTextField) named(dialog, "settings.search")).setText("needle"); return null; });
            await(() -> named(dialog, "lazy.second"));
            assertEquals(1, secondLoads.get());
            edt(() -> { ((JTextField) named(dialog, "settings.search")).setText("initial-search"); return null; });
            Component returned = await(() -> named(dialog, "lazy.editor"));
            assertSame(editor, returned);
            assertEquals("unsaved", edt(editor::getText));
            assertEquals(1, initialLoads.get());
            edt(() -> { dialog.dispose(); return null; });
            finished.get(10, TimeUnit.SECONDS);
        } finally {
            edt(() -> { for (Window window : Window.getWindows()) if ("jlshell.settings".equals(window.getName())) window.dispose(); return null; });
            executor.shutdownNow();
        }
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
    private static <T> T await(Supplier<T> action) throws Exception {
        for (int i = 0; i < 200; i++) {
            T value = edt(action); if (value != null) return value;
            Thread.sleep(25);
        }
        throw new AssertionError("Settings did not become ready");
    }
    private static <T> T edt(Supplier<T> action) throws Exception {
        return SwingExecutors.supplyOnEdtAsync(action).get(10, TimeUnit.SECONDS);
    }
}
