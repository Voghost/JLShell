package com.jlshell.ui.swing;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.view.PluginsTabView;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingPluginPageTest {
    @BeforeAll
    static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
        SwingExecutors.runOnEdtAsync(() -> SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void legacyPageRetainsGraphicCellsIconsAndCustomEditorAcrossThemeChanges() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        AtomicInteger clicks = new AtomicInteger();
        var themes = FxThread.supplyAsync(() -> {
            var settings = new AppSettingsService() {
                public Optional<String> get(String key) { return Optional.empty(); }
                public void set(String key, String value) {}
                public void remove(String key) {}
            };
            var registry = new ColorSchemeRegistry(null, null) {
                @Override public Optional<TerminalColorScheme> findByName(String name) { return Optional.empty(); }
            };
            return new ThemeService(settings, registry, executor);
        }).get(10, TimeUnit.SECONDS);
        var tab = FxThread.supplyAsync(() -> {
            ListView<String> list = new ListView<>();
            list.setId("legacy-list");
            list.getItems().add("Loaded component");
            list.setCellFactory(view -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(null);
                    setGraphic(empty ? null : new Label("Custom cell: " + item));
                }
            });
            SVGPath icon = new SVGPath();
            icon.setContent("M0 0 L12 0 L6 12 Z");
            Label graphic = new Label("Plugin icon", icon);
            graphic.setId("legacy-icon");
            Button button = new Button("Plugin action");
            button.setOnAction(event -> clicks.incrementAndGet());
            VBox body = new VBox(list, new EditorRegion(), graphic, button);
            body.setStyle("-fx-padding: 19px;");
            return new Tab("Legacy plugin", body);
        }).get(10, TimeUnit.SECONDS);
        var prepared = FxThread.supplyAsync(() -> SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), themes))
                .get(10, TimeUnit.SECONDS);
        var page = SwingExecutors.supplyOnEdtAsync(prepared.factory()).get(10, TimeUnit.SECONDS);
        var frame = SwingExecutors.supplyOnEdtAsync(() -> {
            JFrame window = new JFrame();
            window.add(page); window.setSize(480, 560); window.setVisible(true);
            return window;
        }).get(10, TimeUnit.SECONDS);
        try {
            SwingFxPanel bridge = SwingExecutors.supplyOnEdtAsync(() -> {
                SwingFxPanel found = descendant(page, SwingFxPanel.class);
                assertNotNull(found, "Legacy Node pages must bypass the lossy Swing form converter");
                return found;
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> {
                var root = (VBox) tab.getContent();
                assertSame(root, ((BorderPane) bridge.getScene().getRoot()).getCenter());
                root.applyCss(); root.layout();
                var list = (ListView<?>) root.lookup("#legacy-list");
                assertNotNull(list.getCellFactory());
                assertTrue(list.lookupAll(".list-cell").stream().anyMatch(node -> node instanceof ListCell<?> cell
                        && cell.getGraphic() instanceof Label label && label.getText().equals("Custom cell: Loaded component")));
                var editor = (EditorRegion) root.lookup("#legacy-editor");
                assertSame(editor.text, editor.getChildrenUnmodifiable().getFirst());
                assertTrue(editor.getWidth() > 0 && editor.getHeight() > 0);
                assertInstanceOf(SVGPath.class, ((Label) root.lookup("#legacy-icon")).getGraphic());
                themes.setTheme(AppTheme.LIGHT);
                assertSame(root, ((BorderPane) bridge.getScene().getRoot()).getCenter());
                assertEquals("-fx-padding: 19px;", root.getStyle());
                ((Button) root.getChildren().getLast()).fire();
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, clicks.get());
            // Program settings contribute a subtree inside a native host form.
            var renderer = new SwingFormRenderer(new I18nService(Locale.ENGLISH), true);
            var nestedFactory = FxThread.supplyAsync(() -> {
                var settings = new VBox(SwingFxPanel.preservePluginView(new EditorRegion(), themes));
                return renderer.prepare(settings);
            }).get(10, TimeUnit.SECONDS);
            SwingFxPanel nestedBridge = SwingExecutors.supplyOnEdtAsync(() -> {
                var nested = descendant(nestedFactory.get(), SwingFxPanel.class);
                assertNotNull(nested, "Program settings must preserve third-party JavaFX subtrees");
                return nested;
            }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> {
                var boundary = (BorderPane) ((BorderPane) nestedBridge.getScene().getRoot()).getCenter();
                assertInstanceOf(EditorRegion.class, boundary.getCenter());
                renderer.close();
                assertNull(nestedBridge.getScene());
                return null;
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(page::close).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> { assertNull(bridge.getScene()); return null; }).get(10, TimeUnit.SECONDS);
        } finally {
            SwingExecutors.runOnEdtAsync(() -> { page.close(); frame.dispose(); }).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> { prepared.releaseModel().run(); return null; }).get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void explicitSwingFactoryStillRunsAndClosesOnEdt() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        var prepared = FxThread.supplyAsync(() -> {
            Tab tab = new Tab("Swing plugin", new Pane());
            tab.getProperties().put(PluginsTabView.NATIVE_VIEW, (Supplier<JComponent>) () -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                return new NativePage(closes);
            });
            return SwingPluginPage.prepare(tab, new I18nService(Locale.ENGLISH), null);
        }).get(10, TimeUnit.SECONDS);
        SwingExecutors.runOnEdtAsync(() -> {
            SwingPluginPage page = prepared.factory().get();
            assertNull(descendant(page, SwingFxPanel.class));
            assertNotNull(descendant(page, NativePage.class));
            page.close(); page.close();
            assertEquals(1, closes.get());
        }).get(10, TimeUnit.SECONDS);
    }

    private static <T> T descendant(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            T found = descendant(child, type);
            if (found != null) return found;
        }
        return null;
    }

    /** Models a third-party self-rendering Region such as a RichTextFX editor. */
    private static final class EditorRegion extends Region {
        private final Text text = new Text("Editor content");
        EditorRegion() { setId("legacy-editor"); getChildren().add(text); setPrefHeight(70); }
        @Override protected void layoutChildren() { text.relocate(8, 8); }
    }

    private static final class NativePage extends JPanel implements AutoCloseable {
        private final AtomicInteger closes;
        NativePage(AtomicInteger closes) { this.closes = closes; }
        @Override public void close() { assertTrue(SwingUtilities.isEventDispatchThread()); closes.incrementAndGet(); }
    }
}
