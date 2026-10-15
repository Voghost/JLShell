package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** Shared message/confirmation presentation, following IDEA MessageDialog and DialogWrapper. */
public final class SwingAlerts {
    private SwingAlerts() {}

    public static Optional<ButtonType> showAndWait(Alert alert, ThemeService themes, I18nService i18n) {
        themes.applyToDialog(alert);
        if (!SwingDialogHost.enabled()) return alert.showAndWait();
        Node message = alert.getDialogPane().getContent();
        if (message == null) {
            Label text = new Label(alert.getContentText() == null ? "" : alert.getContentText());
            text.setWrapText(true);
            text.setId("message.text");
            message = text;
        }
        VBox textColumn = new VBox(10);
        String header = alert.getHeaderText();
        if (header != null && !header.isBlank() && !header.equals(alert.getTitle())) {
            Label heading = new Label(header); heading.setWrapText(true);
            heading.getStyleClass().add("message-heading"); textColumn.getChildren().add(heading);
        }
        textColumn.getChildren().add(message);
        Label icon = new Label();
        String iconKey = switch (alert.getAlertType()) {
            case ERROR -> "OptionPane.errorIcon";
            case WARNING -> "OptionPane.warningIcon";
            case CONFIRMATION -> "OptionPane.questionIcon";
            default -> "OptionPane.informationIcon";
        };
        icon.getProperties().put("swing.icon", iconKey);
        HBox body = new HBox(16, icon, textColumn);
        body.setPadding(new Insets(20, 24, 20, 24));
        HBox.setHgrow(textColumn, Priority.ALWAYS);
        alert.setHeaderText(null);
        alert.getDialogPane().setGraphic(null);
        alert.getDialogPane().setContent(body);
        return SwingDialogHost.showNativeMessageAndWait(alert, themes, i18n,
                "jlshell.message." + alert.getAlertType().name().toLowerCase(java.util.Locale.ROOT), 580);
    }
}
