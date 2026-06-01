package com.jlshell.ui.view;

import java.util.Optional;
import java.util.function.Consumer;

import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Lists available plugins for the current session and lets the user open them.
 */
public class PluginsTabView extends BorderPane {

    public PluginsTabView(
            PluginManager pluginManager,
            SshSession sshSession,
            Consumer<Tab> openTabCallback,
            I18nService i18nService,
            ThemeService themeService
    ) {
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
                    Label name = new Label(item.displayName());
                    name.getStyleClass().add("plugin-name");
                    Label desc = new Label(item.description());
                    desc.getStyleClass().add("plugin-desc");
                    Button openBtn = new Button(i18nService.get("plugin.open"));
                    openBtn.setOnAction(e -> {
                        Optional<SshSessionContext> sshCtx = (sshSession != null)
                                ? Optional.of(new com.jlshell.plugin.loader.SshSessionContextAdapter(sshSession))
                                : Optional.empty();
                        DefaultPluginContext ctx = new DefaultPluginContext(sshCtx, new DefaultPluginContext.Callbacks() {
                            private Tab openedTab;

                            @Override
                            public void openTab(String title, javafx.scene.Node content) {
                                javafx.application.Platform.runLater(() -> {
                                    openedTab = new Tab(title, content);
                                    openedTab.setClosable(true);
                                    openTabCallback.accept(openedTab);
                                });
                            }

                            @Override
                            public void closeTab() {
                                if (openedTab != null) {
                                    javafx.application.Platform.runLater(() -> {
                                        if (openedTab.getTabPane() != null) {
                                            openedTab.getTabPane().getTabs().remove(openedTab);
                                        }
                                        openedTab = null;
                                    });
                                }
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

        if (listView.getItems().isEmpty()) {
            setCenter(new Label(i18nService.get("plugin.noPlugins")));
        } else {
            setCenter(listView);
        }
    }
}