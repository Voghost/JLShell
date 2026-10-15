package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jlshell.core.session.SshSession;
import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.loader.SshSessionContextAdapter;
import com.jlshell.program.plugin.loader.ProgramSessionIntegrationRegistry;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/** Lists Session plugins and session surfaces contributed by Program plugins. */
public class PluginsTabView extends BorderPane {

    private final I18nService i18nService;
    private final PluginManager pluginManager;
    private final ProgramSessionIntegrationRegistry programSessionRegistry;
    private final CapabilityBus capabilityBus;
    private final java.util.function.Function<String, PluginStorage> storageFactory;
    private final String sessionId;
    private final SshSessionContext sshContext;
    private final TabPane workspaceTabs;
    private final Set<String> activatedSessionPluginIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ChangeListener<java.util.Locale> localeListener;
    private final ChangeListener<Number> catalogRevisionListener;
    public static final String NATIVE_VIEW = "jlshell.plugin.swingView";
    private final java.util.concurrent.atomic.AtomicReference<com.jlshell.ui.swing.SwingPluginList> nativeView = new java.util.concurrent.atomic.AtomicReference<>();
    private boolean disposed;
    public static final String PINNED_SHORTCUTS = "jlshell.pinnedPluginShortcuts";
    public record PinnedShortcut(String id, String title, Runnable open, com.jlshell.ui.swing.PluginIcons.Source icon) {}
    private final javafx.collections.ObservableList<PinnedShortcut> pinnedShortcuts;
    private final com.jlshell.core.service.AppSettingsService settings;
    private final Set<String> pinnedIds = new java.util.HashSet<>();
    private final ListView<LaunchItem> listView = new ListView<>();

    public PluginsTabView(
            PluginManager pluginManager,
            ProgramSessionIntegrationRegistry programSessionRegistry,
            String sessionId,
            SshSession sshSession,
            TabPane workspaceTabs,
            I18nService i18nService,
            ThemeService themeService,
            SftpService sftpService,
            CapabilityBus capabilityBus,
            java.util.function.Function<String, PluginStorage> storageFactory,
            com.jlshell.core.service.AppSettingsService settings
    ) {
        this.i18nService = i18nService;
        this.settings = settings;
        Tab pluginListTab = workspaceTabs.getTabs().stream()
                .filter(tab -> tab.getContent() == this || tab.getProperties().containsKey(PINNED_SHORTCUTS))
                .findFirst().orElse(null);
        // The list belongs to the session, so reconnecting can replace its entries without losing listeners.
        @SuppressWarnings("unchecked")
        var existing = pluginListTab == null ? null : (javafx.collections.ObservableList<PinnedShortcut>)
                pluginListTab.getProperties().get(PINNED_SHORTCUTS);
        pinnedShortcuts = existing == null ? javafx.collections.FXCollections.observableArrayList() : existing;
        getProperties().put(PINNED_SHORTCUTS, pinnedShortcuts);
        this.pluginManager = pluginManager;
        this.programSessionRegistry = programSessionRegistry;
        this.sessionId = sessionId;
        this.workspaceTabs = workspaceTabs;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.sshContext = new SshSessionContextAdapter(sshSession, sftpService);

        listView.setId("session-plugin-list");
        listView.setMinWidth(0);
        listView.setFixedCellSize(-1);
        listView.setCellFactory(ignored -> new ListCell<>() {
            {
                setMinWidth(0);
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                prefWidthProperty().bind(listView.widthProperty().subtract(20));
                setMaxWidth(Double.MAX_VALUE);
            }
            @Override protected void updateItem(LaunchItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) { setGraphic(null); return; }
                Label name = new Label(item.displayName(i18nService.getLocale()));
                name.getStyleClass().add("plugin-name");
                name.setWrapText(true); name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE);
                Label description = new Label(item.description(i18nService.getLocale()));
                description.getStyleClass().add("plugin-desc");
                description.setWrapText(true); description.setMinWidth(0); description.setMaxWidth(Double.MAX_VALUE);
                Button open = new Button(i18nService.get("plugin.open"));
                open.setOnAction(event -> activate(item));
                boolean pinned = pinnedIds.contains(item.tabKey());
                javafx.scene.control.ToggleButton pin = new javafx.scene.control.ToggleButton(
                        i18nService.get(pinned ? "plugin.unpinFromSidebar" : "plugin.pinToSidebar"));
                pin.setId("plugin-pin-" + item.tabKey());
                pin.setSelected(pinned);
                pin.setOnAction(event -> {
                    if (pin.isSelected()) pinnedIds.add(item.tabKey()); else pinnedIds.remove(item.tabKey());
                    if (settings != null) settings.set("ui.pluginSidebar.pinned." + item.tabKey(), Boolean.toString(pin.isSelected()));
                    publishPinnedShortcuts(); listView.refresh();
                });
                javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(6, 6, pin, open);
                actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
                VBox row = new VBox(6, name, description, actions);
                row.getStyleClass().add("session-plugin-card");
                row.setMinWidth(0);
                row.prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, widthProperty().subtract(20)));
                row.setMaxWidth(Double.MAX_VALUE);
                setGraphic(row);
            }
        });

        localeListener = (obs, oldLocale, newLocale) -> { listView.refresh(); publishPinnedShortcuts(); };
        i18nService.localeProperty().addListener(localeListener);
        catalogRevisionListener = (obs, oldRevision, newRevision) -> refreshOnFxThread();
        refreshPluginList();
        pluginManager.catalogRevisionProperty().addListener(catalogRevisionListener);
        if (programSessionRegistry != null) {
            programSessionRegistry.revisionProperty().addListener(catalogRevisionListener);
        }
    }

    private void activate(LaunchItem item) {
        for (Tab tab : workspaceTabs.getTabs()) {
            if (item.tabKey().equals(tab.getProperties().get("pluginId"))) {
                workspaceTabs.getSelectionModel().select(tab);
                return;
            }
        }

        CapabilityRegistryImpl sessionRegistry = pluginManager.registryForSession(sessionId);
        PluginStorage storage = storageFactory == null ? null : storageFactory.apply(item.ownerPluginId());
        DefaultPluginContext context = new DefaultPluginContext(
                item.ownerPluginId(), sessionId, sessionRegistry, capabilityBus, storage,
                Optional.of(sshContext), callbacks(item));
        context.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
        context.writableLocaleProperty().bind(pluginManager.localeProperty());

        if (item.sessionPlugin() != null) {
            pluginManager.adoptContext(sessionId, item.ownerPluginId(), context);
            pluginManager.activatePlugin(item.ownerPluginId(), context);
            activatedSessionPluginIds.add(item.ownerPluginId());
        } else if (programSessionRegistry != null) {
            programSessionRegistry.activate(item.ownerPluginId(), sessionId, context);
        }
    }

    private DefaultPluginContext.Callbacks callbacks(LaunchItem item) {
        return new DefaultPluginContext.Callbacks() {
            private Tab openedTab;

            @Override public boolean supportsSwingViews() { return com.jlshell.ui.swing.SwingDialogHost.enabled(); }

            @Override public void openSwingTab(String title, java.util.function.Supplier<javax.swing.JComponent> content) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    openedTab = new Tab(title, new javafx.scene.layout.Pane());
                    openedTab.getProperties().put("pluginId", item.tabKey());
                    openedTab.getProperties().put(NATIVE_VIEW, content);
                    openedTab.setOnClosed(event -> deactivateAfterUserClose(item));
                    workspaceTabs.getTabs().add(openedTab);
                    workspaceTabs.getSelectionModel().select(openedTab);
                });
            }

            @Override
            public void openTab(String title, javafx.scene.Node content) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    openedTab = new Tab(title, content);
                    openedTab.setClosable(true);
                    openedTab.getProperties().put("pluginId", item.tabKey());
                    openedTab.setOnClosed(event -> deactivateAfterUserClose(item));
                    workspaceTabs.getTabs().add(openedTab);
                    workspaceTabs.getSelectionModel().select(openedTab);
                });
            }

            @Override
            public void closeTab() {
                if (openedTab == null) {
                    return;
                }
                Platform.runLater(() -> {
                    if (openedTab != null && openedTab.getTabPane() != null) {
                        openedTab.getTabPane().getTabs().remove(openedTab);
                    }
                    openedTab = null;
                });
            }

            @Override
            public void updateTabTitle(String title) {
                if (openedTab != null) {
                    Platform.runLater(() -> openedTab.setText(title));
                }
            }

            @Override
            public String resolveI18n(String key, String fallback) {
                return i18nService.getOrDefault(key, fallback);
            }
        };
    }

    private void deactivateAfterUserClose(LaunchItem item) {
        if (item.sessionPlugin() != null) {
            activatedSessionPluginIds.remove(item.ownerPluginId());
            pluginManager.deactivatePlugin(sessionId, item.ownerPluginId());
        } else if (programSessionRegistry != null) {
            programSessionRegistry.deactivate(sessionId, item.ownerPluginId());
        }
    }

    private void refreshOnFxThread() {
        if (Platform.isFxApplicationThread()) {
            refreshPluginList();
        } else {
            Platform.runLater(this::refreshPluginList);
        }
    }

    private void refreshPluginList() {
        List<LaunchItem> available = new ArrayList<>();
        List<PluginDescriptor> sessionPlugins = pluginManager.getAvailablePlugins();
        sessionPlugins.forEach(plugin -> available.add(LaunchItem.session(plugin)));
        if (programSessionRegistry != null) {
            programSessionRegistry.contributions(sshContext)
                    .forEach(contribution -> available.add(LaunchItem.program(contribution)));
        }
        available.sort(java.util.Comparator.comparing(item ->
                item.displayName(i18nService.getLocale()), String.CASE_INSENSITIVE_ORDER));
        if (settings != null) {
            for (LaunchItem item : available) {
                if (Boolean.parseBoolean(settings.get("ui.pluginSidebar.pinned." + item.tabKey(), "false"))) pinnedIds.add(item.tabKey());
                else pinnedIds.remove(item.tabKey());
            }
        }
        listView.getItems().setAll(available);
        publishPinnedShortcuts();
        Set<String> availableSessionIds = sessionPlugins.stream()
                .map(PluginDescriptor::id)
                .collect(java.util.stream.Collectors.toSet());
        activatedSessionPluginIds.retainAll(availableSessionIds);
        setCenter(available.isEmpty() ? new Label(i18nService.get("plugin.noPlugins")) : listView);
    }

    private void publishPinnedShortcuts() {
        publishNativeList();
        pinnedShortcuts.setAll(listView.getItems().stream().filter(item -> pinnedIds.contains(item.tabKey()))
                .map(item -> new PinnedShortcut(item.tabKey(), item.displayName(i18nService.getLocale()), () -> activate(item), item.icon()))
                .toList());
    }

    /** FX 线程准备快照与动作；列表组件在 EDT 构建。 */
    public java.util.function.Supplier<javax.swing.JComponent> prepareNativeView() {
        var initial = nativeEntries();
        String search = i18nService.get("plugins.store.search"), open = i18nService.get("plugin.open"),
                pin = i18nService.get("plugin.pinToSidebar"), unpin = i18nService.get("plugin.unpinFromSidebar"), empty = i18nService.get("plugin.noPlugins");
        return () -> {
            var view = new com.jlshell.ui.swing.SwingPluginList(); nativeView.set(view);
            view.update(initial, search, open, pin, unpin, empty); return view;
        };
    }

    private List<com.jlshell.ui.swing.SwingPluginList.Entry> nativeEntries() {
        return listView.getItems().stream().map(item -> new com.jlshell.ui.swing.SwingPluginList.Entry(
                item.tabKey(), item.displayName(i18nService.getLocale()), item.description(i18nService.getLocale()),
                item.sessionPlugin() == null ? "Program" : item.sessionPlugin().version(), pinnedIds.contains(item.tabKey()),
                () -> Platform.runLater(() -> { if (!disposed) activate(item); }),
                selected -> Platform.runLater(() -> {
                    if (disposed) return;
                    if (selected) pinnedIds.add(item.tabKey()); else pinnedIds.remove(item.tabKey());
                    if (settings != null) settings.set("ui.pluginSidebar.pinned." + item.tabKey(), Boolean.toString(selected));
                    publishPinnedShortcuts(); listView.refresh();
                }), item.icon())).toList();
    }

    private void publishNativeList() {
        var entries = nativeEntries();
        String search = i18nService.get("plugins.store.search"), open = i18nService.get("plugin.open"),
                pin = i18nService.get("plugin.pinToSidebar"), unpin = i18nService.get("plugin.unpinFromSidebar"), empty = i18nService.get("plugin.noPlugins");
        javax.swing.SwingUtilities.invokeLater(() -> {
            var view = nativeView.get(); if (view != null) view.update(entries, search, open, pin, unpin, empty);
        });
    }

    public void stopPlugins() {
        activatedSessionPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedSessionPluginIds.clear();
        if (programSessionRegistry != null) {
            programSessionRegistry.deactivateSession(sessionId);
        }
    }

    public void dispose() {
        disposed = true; nativeView.set(null);
        i18nService.localeProperty().removeListener(localeListener);
        pluginManager.catalogRevisionProperty().removeListener(catalogRevisionListener);
        if (programSessionRegistry != null) {
            programSessionRegistry.revisionProperty().removeListener(catalogRevisionListener);
        }
        stopPlugins();
        pinnedShortcuts.clear();
        setCenter(null);
    }

    private record LaunchItem(
            String ownerPluginId,
            PluginDescriptor sessionPlugin,
            ProgramSessionIntegrationRegistry.RegisteredContribution programContribution
    ) {
        static LaunchItem session(PluginDescriptor descriptor) {
            return new LaunchItem(descriptor.id(), descriptor, null);
        }

        static LaunchItem program(ProgramSessionIntegrationRegistry.RegisteredContribution contribution) {
            return new LaunchItem(contribution.pluginId(), null, contribution);
        }

        String tabKey() {
            return (sessionPlugin == null ? "program:" : "session:") + ownerPluginId;
        }

        com.jlshell.ui.swing.PluginIcons.Source icon() {
            Object plugin = sessionPlugin == null ? programContribution.contribution() : sessionPlugin.instance();
            return com.jlshell.ui.swing.PluginIcons.local(plugin == null ? null : plugin.getClass());
        }

        String displayName(java.util.Locale locale) {
            if (sessionPlugin != null) {
                JlShellPlugin plugin = sessionPlugin.instance();
                return plugin.displayName(locale);
            }
            ProgramSessionContribution contribution = programContribution.contribution();
            return contribution.displayName(locale);
        }

        String description(java.util.Locale locale) {
            if (sessionPlugin != null) {
                return sessionPlugin.instance().description(locale);
            }
            return programContribution.contribution().description(locale);
        }
    }
}
