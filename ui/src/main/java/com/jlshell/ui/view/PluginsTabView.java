package com.jlshell.ui.view;

import java.util.function.Consumer;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.loader.DefaultPluginContext;
import com.jlshell.plugin.loader.PluginDescriptor;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.ui.service.I18nService;
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
            I18nService i18nService
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
                        PluginContext ctx = new DefaultPluginContext(sshSession, openTabCallback);
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
