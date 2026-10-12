package com.jlshell.ui.view;

import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

/** IDEA 风格的设置分类导航；沿用原有 Tab 模型和设置内容。 */
public final class SettingsNavigation extends BorderPane {

    public SettingsNavigation(TabPane tabs) {
        getStyleClass().add("ide-settings");
        ListView<Tab> navigation = new ListView<>(tabs.getTabs());
        navigation.getStyleClass().add("ide-settings-navigation");
        navigation.setPrefWidth(190);
        navigation.setMinWidth(160);
        navigation.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Tab tab, boolean empty) {
                super.updateItem(tab, empty);
                textProperty().unbind();
                if (empty || tab == null) setText(null);
                else textProperty().bind(tab.textProperty());
            }
        });
        StackPane content = new StackPane();
        content.setMinSize(0, 0);
        content.getStyleClass().add("ide-settings-content");
        navigation.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) -> {
            if (tab == null) return;
            tabs.getSelectionModel().select(tab);
            content.getChildren().setAll(tab.getContent());
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) ->
                navigation.getSelectionModel().select(tab));
        navigation.getSelectionModel().select(tabs.getSelectionModel().getSelectedItem());
        setLeft(navigation);
        setCenter(content);
    }
}
