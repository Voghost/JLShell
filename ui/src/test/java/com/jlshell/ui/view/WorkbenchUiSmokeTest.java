package com.jlshell.ui.view;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.HostKeyVerificationMode;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.css.CssParser;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 需要桌面环境的真实 JavaFX 验证；无显示器的 CI 默认跳过。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class WorkbenchUiSmokeTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(15, TimeUnit.SECONDS);
    }

    @Test
    void changesBothSceneAndOpenDialogThemeWithoutRemovingPluginStyles() throws Exception {
        onFx(() -> {
            Locale previous = Locale.getDefault();
            try (var executor = Executors.newSingleThreadExecutor()) {
                Locale.setDefault(Locale.GERMANY);
                ThemeService theme = theme(executor);
                Button button = new Button("连接");
                button.getStyleClass().add("plugin-surface");
                Scene scene = new Scene(new VBox(button), 640, 480);
                String pluginCss = getClass().getResource("/css/plugin-fixture.css").toExternalForm();
                scene.getStylesheets().add(pluginCss);
                DialogPane dialog = new DialogPane();
                theme.applyToDialog(dialog);
                for (AppTheme selected : List.of(AppTheme.DARK, AppTheme.LIGHT, AppTheme.DARK)) {
                    theme.setTheme(selected);
                    theme.apply(scene);
                    scene.getRoot().applyCss();
                    assertTrue(scene.getStylesheets().contains(pluginCss));
                    assertEquals(0.85, button.getOpacity(), 0.001);
                    assertEquals(2, dialog.getStylesheets().size());
                    assertTrue(dialog.getStylesheets().getFirst().endsWith(selected.stylesheet()));
                    Color expected = Color.web(selected == AppTheme.DARK ? "#191a1d" : "#f7f8fa");
                    assertEquals(expected, button.getBackground().getFills().getFirst().getFill());
                    assertTrue(theme.accentStyle().contains("0.28"));
                }
            } finally {
                Locale.setDefault(previous);
            }
        });
    }

    @Test
    void keepsThousandsOfConnectionsVirtualizedAndPreservesFolderExpansionWhenFiltering() throws Exception {
        onFx(() -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var tree = new SidebarTreeView(new I18nService(Locale.SIMPLIFIED_CHINESE), 4);
                var profiles = IntStream.range(0, 4000).mapToObj(i -> new ConnectionProfile("conn-" + i,
                        "开发服务器 " + i, "192.0.2.10", 22, "developer", AuthenticationType.PASSWORD,
                        HostKeyVerificationMode.STRICT, null, null, false, null, ConnectionType.SSH,
                        "folder", null)).toList();
                tree.populate(List.of(new FolderProfile("folder", "服务器", null, null, 0)), profiles, Set.of());
                BorderPane root = new BorderPane(tree.getTreeView());
                Scene scene = new Scene(root, 280, 480);
                theme(executor).apply(scene);
                root.applyCss();
                root.layout();
                assertTrue(tree.getTreeView().getFixedCellSize() > 26);
                assertTrue(root.lookupAll(".tree-cell").size() < 50);
                tree.collapseAllFolders();
                tree.applyFilter("3999");
                assertEquals(2, tree.getTreeView().getExpandedItemCount());
                tree.applyFilter("");
                assertEquals(1, tree.getTreeView().getExpandedItemCount());
                tree.expandAllFolders();
                tree.getTreeView().scrollTo(3500);
                root.layout();
                assertTrue(root.lookupAll(".tree-cell").size() < 50);
                assertEquals(4001, tree.getTreeView().getExpandedItemCount());
            }
        });
    }

    @Test
    void nativeSessionNavigationHidesOnlyLegacyHeaderAcrossThemesAndSkinReplacement() throws Exception {
        onFx(() -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                ThemeService themes = theme(executor);
                TabPane pluginTabs = new TabPane(new Tab("插件自己的页", new Label("内容")));
                Tab plugin = new Tab("插件", pluginTabs);
                TabPane model = new TabPane(new Tab("终端", new Label("终端")), plugin);
                model.getStyleClass().add("native-session-tabs");
                BorderPane root = new BorderPane(model);
                Scene scene = new Scene(root, 700, 400);
                for (AppTheme selected : List.of(AppTheme.DARK, AppTheme.LIGHT, AppTheme.DARK)) {
                    themes.setTheme(selected); themes.apply(scene);
                    model.setSkin(new javafx.scene.control.skin.TabPaneSkin(model));
                    model.getSelectionModel().select(plugin);
                    root.applyCss(); root.layout();
                    javafx.scene.Node header = model.getChildrenUnmodifiable().stream()
                            .filter(node -> node.getStyleClass().contains("tab-header-area")).findFirst().orElseThrow();
                    assertEquals(0, header.getBoundsInParent().getHeight(), .1);
                    assertSame(pluginTabs, plugin.getContent());
                    assertTrue(pluginTabs.lookup(".tab-header-area").getBoundsInParent().getHeight() > 10,
                            "An external plugin's own tabs must remain visible");
                }
            }
        });
    }

    @Test
    void settingsNavigationRetainsOriginalTabSelectionAndContent() throws Exception {
        onFx(() -> {
            Tab general = new Tab("常规", new Label("常规设置"));
            Tab terminal = new Tab("终端", new Label("终端设置"));
            TabPane model = new TabPane(general, terminal);
            model.getSelectionModel().select(terminal);
            SettingsNavigation view = new SettingsNavigation(model);
            ListView<?> navigation = (ListView<?>) view.getLeft();
            assertSame(terminal, navigation.getSelectionModel().getSelectedItem());
            navigation.getSelectionModel().select(0);
            assertSame(general, model.getSelectionModel().getSelectedItem());
            model.getSelectionModel().select(terminal);
            assertSame(terminal, navigation.getSelectionModel().getSelectedItem());
            assertSame(terminal.getContent().getParent(), view.getCenter());
        });
    }

    private static ThemeService theme(java.util.concurrent.ExecutorService executor) {
        var settings = new AppSettingsService() {
            private final java.util.Map<String, String> values = new HashMap<>();
            public Optional<String> get(String key) { return Optional.ofNullable(values.get(key)); }
            public void set(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        };
        var registry = new ColorSchemeRegistry(null, null) {
            @Override
            public Optional<TerminalColorScheme> findByName(String name) { return Optional.empty(); }
        };
        return new ThemeService(settings, registry, executor);
    }

    @Test
    void swingCompatibilityContainerKeepsPluginNodeAndActionsAcrossThemeChanges() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var service = new java.util.concurrent.atomic.AtomicReference<ThemeService>();
        var pluginRoot = new java.util.concurrent.atomic.AtomicReference<VBox>();
        var pluginButton = new java.util.concurrent.atomic.AtomicReference<Button>();
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        onFx(() -> {
            service.set(theme(executor));
            Button button = new Button("插件动作");
            button.setOnAction(event -> {
                assertTrue(Platform.isFxApplicationThread());
                invocations.incrementAndGet();
            });
            pluginButton.set(button);
            pluginRoot.set(new VBox(button));
            pluginRoot.get().setStyle("-fx-padding: 19px;");
        });
        var panel = com.jlshell.terminal.support.SwingExecutors.supplyOnEdtAsync(() ->
                new com.jlshell.ui.swing.SwingFxPanel(service.get())).get(10, TimeUnit.SECONDS);
        try {
            panel.setView(pluginRoot.get());
            onFx(() -> {
                assertSame(pluginRoot.get(), ((BorderPane) panel.getScene().getRoot()).getCenter());
                service.get().setTheme(AppTheme.LIGHT);
                assertSame(pluginRoot.get(), ((BorderPane) panel.getScene().getRoot()).getCenter());
                assertEquals("-fx-padding: 19px;", pluginRoot.get().getStyle());
                pluginButton.get().fire();
                assertEquals(1, invocations.get());
            });
        } finally {
            panel.close();
            onFx(() -> assertNull(panel.getScene()));
            executor.close();
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Platform.runLater(() -> {
            CssParser.errorsProperty().clear();
            try {
                action.run();
                assertTrue(CssParser.errorsProperty().isEmpty(), () -> CssParser.errorsProperty().toString());
                finished.complete(null);
            } catch (Throwable error) {
                finished.completeExceptionally(error);
            }
        });
        finished.get(20, TimeUnit.SECONDS);
    }
}
