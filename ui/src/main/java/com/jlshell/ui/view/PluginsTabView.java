package com.jlshell.ui.view;

import java.util.Optional;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.loader.CapabilityRegistryImpl;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
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

/**
 * Lists available plugins for the current session and lets the user open them.
 */
public class PluginsTabView extends BorderPane {

    private final I18nService i18nService;
    private final PluginManager pluginManager;
    private final SftpService sftpService;
    /** 本工作区 Tab 对应的会话 id（SSH 会话或合成的 local-uuid），用于 per-session registry 路由 */
    private final String sessionId;
    private final java.util.Set<String> activatedPluginIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PluginsTabView(
            PluginManager pluginManager,
            String sessionId,
            SshSession sshSession,
            TabPane workspaceTabs,
            I18nService i18nService,
            ThemeService themeService,
            SftpService sftpService
    ) {
        this.i18nService = i18nService;
        this.pluginManager = pluginManager;
        this.sftpService = sftpService;
        this.sessionId = sessionId;
        setPadding(new Insets(8));

        ListView<PluginDescriptor> listView = new ListView<>();
        listView.getItems().addAll(pluginManager.getAvailablePlugins());

        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PluginDescriptor item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    JlShellPlugin plugin = item.instance();
                    Label name = new Label(plugin.displayName(i18nService.getLocale()));
                    name.getStyleClass().add("plugin-name");
                    Label desc = new Label(plugin.description(i18nService.getLocale()));
                    desc.getStyleClass().add("plugin-desc");
                    Button openBtn = new Button(i18nService.get("plugin.open"));
                    openBtn.setOnAction(e -> {
                        // Prevent duplicate: if this plugin is already open in this session's tab pane, just select it
                        for (Tab tab : workspaceTabs.getTabs()) {
                            String existingId = (String) tab.getProperties().get("pluginId");
                            if (existingId != null && existingId.equals(item.id())) {
                                workspaceTabs.getSelectionModel().select(tab);
                                return;
                            }
                        }
                        Optional<SshSessionContext> sshCtx = (sshSession != null)
                                ? Optional.of(new com.jlshell.plugin.loader.SshSessionContextAdapter(sshSession, sftpService))
                                : Optional.empty();
                        CapabilityRegistryImpl sessionRegistry = pluginManager.registryForSession(sessionId);
                        DefaultPluginContext ctx = new DefaultPluginContext(item.id(), sessionId, sessionRegistry, sshCtx, new DefaultPluginContext.Callbacks() {
                            private Tab openedTab;

                            @Override
                            public void openTab(String title, javafx.scene.Node content) {
                                Platform.runLater(() -> {
                                    openedTab = new Tab(title, content);
                                    openedTab.setClosable(true);
                                    openedTab.getProperties().put("pluginId", item.id());
                                    workspaceTabs.getTabs().add(openedTab);
                                    workspaceTabs.getSelectionModel().select(openedTab);
                                });
                            }

                            @Override
                            public void closeTab() {
                                if (openedTab != null) {
                                    Platform.runLater(() -> {
                                        if (openedTab.getTabPane() != null) {
                                            openedTab.getTabPane().getTabs().remove(openedTab);
                                        }
                                        openedTab = null;
                                    });
                                }
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
                        });
                        ctx.writableThemeNameProperty().bind(pluginManager.themeNameProperty());
                        ctx.writableLocaleProperty().bind(pluginManager.localeProperty());
                        pluginManager.adoptContext(sessionId, item.id(), ctx);
                        pluginManager.activatePlugin(item.id(), ctx);
                        activatedPluginIds.add(item.id());
                    });
                    HBox row = new HBox(8, new VBox(2, name, desc), openBtn);
                    HBox.setHgrow(row.getChildren().get(0), Priority.ALWAYS);
                    row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    setGraphic(row);
                    setText(null);
                }
            }
        });

        i18nService.localeProperty().addListener((obs, oldLocale, newLocale) -> listView.refresh());

        if (listView.getItems().isEmpty()) {
            setCenter(new Label(i18nService.get("plugin.noPlugins")));
        } else {
            setCenter(listView);
        }
    }

    public void stopPlugins() {
        activatedPluginIds.forEach(id -> pluginManager.deactivatePlugin(sessionId, id));
        activatedPluginIds.clear();
    }
}