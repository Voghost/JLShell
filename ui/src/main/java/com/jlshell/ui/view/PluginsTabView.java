package com.jlshell.ui.view;

import java.util.Optional;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.core.session.SshSession;
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

    public PluginsTabView(
            PluginManager pluginManager,
            SshSession sshSession,
            TabPane workspaceTabs,
            I18nService i18nService,
            ThemeService themeService
    ) {
        this.i18nService = i18nService;
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
                                ? Optional.of(new com.jlshell.plugin.loader.SshSessionContextAdapter(sshSession))
                                : Optional.empty();
                        DefaultPluginContext ctx = new DefaultPluginContext(sshCtx, new DefaultPluginContext.Callbacks() {
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
                        pluginManager.activatePlugin(item.id(), ctx);
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
}