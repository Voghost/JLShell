package com.jlshell.ui.view;

import com.jlshell.ui.service.I18nService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Custom title bar for Windows — replaces the OS chrome with an in-app bar
 * containing the menu bar, app title, and window control buttons.
 * Supports drag-to-move and double-click to maximize/restore.
 */
public class CustomTitleBar extends HBox {

    private final Stage stage;
    private final MenuBar menuBar;
    private final Label titleLabel;
    private double dragOffsetX;
    private double dragOffsetY;

    public CustomTitleBar(Stage stage, MenuBar menuBar, I18nService i18n) {
        this.stage = stage;
        this.menuBar = menuBar;
        getStyleClass().add("custom-title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0, 0, 0, 4));

        // App icon
        Node icon = loadAppIcon();
        if (icon != null) {
            HBox.setMargin(icon, new Insets(0, 6, 0, 2));
            getChildren().add(icon);
        }

        // Menu bar (compact, no padding)
        menuBar.getStyleClass().add("embedded-menu-bar");
        getChildren().add(menuBar);

        // Spacer — drag zone
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);

        // Window title
        titleLabel = new Label("JLShell");
        titleLabel.getStyleClass().add("title-label");
        getChildren().add(titleLabel);

        // Another small spacer before window buttons
        Region spacer2 = new Region();
        spacer2.setMinWidth(8);
        getChildren().add(spacer2);

        // Window control buttons
        getChildren().addAll(
                createWindowButton("minimize", "–", i18n.get("window.minimize")),
                createWindowButton("maximize", "□", i18n.get("window.maximize")),
                createWindowButton("close", "✕", i18n.get("window.close"))
        );

        // Drag-to-move
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                dragOffsetX = e.getSceneX();
                dragOffsetY = e.getSceneY();
            }
        });
        setOnMouseDragged(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });

        // Double-click to maximize/restore
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    private Node loadAppIcon() {
        var url = CustomTitleBar.class.getResource("/icons/app_icon.png");
        if (url != null) {
            Image img = new Image(url.toExternalForm(), 16, 16, true, true);
            return new ImageView(img);
        }
        return null;
    }

    private Button createWindowButton(String styleClass, String text, String tooltip) {
        Button btn = new Button(text);
        btn.getStyleClass().addAll("window-btn", "window-btn-" + styleClass);
        btn.setTooltip(new Tooltip(tooltip));

        switch (styleClass) {
            case "minimize" -> btn.setOnAction(e -> stage.setIconified(true));
            case "maximize" -> {
                btn.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
                stage.maximizedProperty().addListener((obs, old, maximized) ->
                        btn.setText(maximized ? "⧉" : "□"));
            }
            case "close" -> btn.setOnAction(e ->
                    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        }

        return btn;
    }

    /**
     * 控制菜单栏的显隐（顶栏折叠时隐藏菜单栏，保留窗口控制按钮和标题）。
     */
    public void setMenuBarVisible(boolean visible) {
        menuBar.setManaged(visible);
        menuBar.setVisible(visible);
    }

    public void setTitleText(String title) {
        titleLabel.setText(title == null || title.isBlank() ? "JLShell" : title);
    }
}
