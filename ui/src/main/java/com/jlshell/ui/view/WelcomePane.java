package com.jlshell.ui.view;

import com.jlshell.ui.service.I18nService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Welcome panel shown in the workspace area when no session tabs are open.
 * Guides the user to create a new connection/folder and displays keyboard shortcuts.
 */
public class WelcomePane extends VBox {

    public WelcomePane(I18nService i18n, Runnable onCreateConnection, Runnable onCreateFolder) {
        setAlignment(Pos.CENTER);
        setSpacing(20);
        setPadding(new Insets(40));
        getStyleClass().add("welcome-pane");

        // App icon
        ImageView iconView = null;
        var iconUrl = WelcomePane.class.getResource("/icons/app_icon.png");
        if (iconUrl != null) {
            Image icon = new Image(iconUrl.toExternalForm(), 64, 64, true, true);
            iconView = new ImageView(icon);
        }

        // Title & subtitle
        Label title = new Label(i18n.get("welcome.title"));
        title.getStyleClass().add("welcome-title");

        Label subtitle = new Label(i18n.get("welcome.subtitle"));
        subtitle.getStyleClass().add("welcome-subtitle");

        VBox header = new VBox(6, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // Quick start label
        Label quickStart = new Label(i18n.get("welcome.quickStart"));
        quickStart.getStyleClass().add("welcome-section-label");

        // Action buttons
        Button newConnBtn = new Button(i18n.get("action.newConnection"));
        newConnBtn.getStyleClass().add("action-btn");
        newConnBtn.setOnAction(e -> onCreateConnection.run());

        Button newFolderBtn = new Button(i18n.get("sidebar.newFolder"));
        newFolderBtn.getStyleClass().add("action-btn-secondary");
        newFolderBtn.setOnAction(e -> onCreateFolder.run());

        HBox actions = new HBox(12, newConnBtn, newFolderBtn);
        actions.setAlignment(Pos.CENTER);
        actions.getStyleClass().add("welcome-actions");

        // Help section
        Label shortcutsLabel = new Label(i18n.get("welcome.help.shortcuts"));
        shortcutsLabel.getStyleClass().add("welcome-section-label");

        String mod = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘" : "Ctrl";

        VBox helpItems = new VBox(6);
        helpItems.getStyleClass().add("welcome-help");
        helpItems.getChildren().addAll(
                shortcutLabel(i18n.get("welcome.help.newConnection", mod)),
                shortcutLabel(i18n.get("welcome.help.refreshConnections", mod)),
                shortcutLabel(i18n.get("welcome.help.doubleClick")),
                shortcutLabel(i18n.get("welcome.help.rightClick"))
        );

        // Assemble
        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(420);
        if (iconView != null) {
            content.getChildren().add(iconView);
        }
        content.getChildren().addAll(header, new Separator(), quickStart, actions,
                new Separator(), shortcutsLabel, helpItems);

        getChildren().add(content);
    }

    private static Label shortcutLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("welcome-help-item");
        return label;
    }
}