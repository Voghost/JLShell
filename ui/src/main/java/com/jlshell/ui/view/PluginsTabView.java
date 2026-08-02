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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
            java.util.function.Function<String, PluginStorage> storageFactory
    ) {
        this.i18nService = i18nService;
        this.pluginManager = pluginManager;
        this.programSessionRegistry = programSessionRegistry;
        this.sessionId = sessionId;
        this.workspaceTabs = workspaceTabs;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.sshContext = new SshSessionContextAdapter(sshSession, sftpService);

        listView.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(LaunchItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(item.displayName(i18nService.getLocale()));
                name.getStyleClass().add("plugin-name");
                Label description = new Label(item.description(i18nService.getLocale()));
                description.getStyleClass().add("plugin-desc");
                Button open = new Button(i18nService.get("plugin.open"));
                open.setOnAction(event -> activate(item));
                HBox row = new HBox(8, new VBox(2, name, description), open);
                HBox.setHgrow(row.getChildren().getFirst(), Priority.ALWAYS);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });

        localeListener = (obs, oldLocale, newLocale) -> listView.refresh();
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

            @Override
            public void openTab(String title, javafx.scene.Node content) {
                Platform.runLater(() -> {
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
        listView.getItems().setAll(available);
        Set<String> availableSessionIds = sessionPlugins.stream()
                .map(PluginDescriptor::id)
                .collect(java.util.stream.Collectors.toSet());
        activatedSessionPluginIds.retainAll(availableSessionIds);
        setCenter(available.isEmpty() ? new Label(i18nService.get("plugin.noPlugins")) : listView);
    }

    public void stopPlugins() {
        activatedSessionPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedSessionPluginIds.clear();
        if (programSessionRegistry != null) {
            programSessionRegistry.deactivateSession(sessionId);
        }
    }

    public void dispose() {
        i18nService.localeProperty().removeListener(localeListener);
        pluginManager.catalogRevisionProperty().removeListener(catalogRevisionListener);
        if (programSessionRegistry != null) {
            programSessionRegistry.revisionProperty().removeListener(catalogRevisionListener);
        }
        stopPlugins();
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
