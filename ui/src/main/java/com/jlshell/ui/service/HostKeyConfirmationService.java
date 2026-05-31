package com.jlshell.ui.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.jlshell.ssh.support.HostKeyConfirmationCallback;
import com.jlshell.ui.support.FxThread;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Shows a JavaFX dialog to confirm unknown or changed SSH host keys.
 * Blocks the calling SSHJ transport thread until the user responds.
 */
public class HostKeyConfirmationService implements HostKeyConfirmationCallback {

    private static final long TIMEOUT_SECONDS = 60;

    private final I18nService i18n;

    public HostKeyConfirmationService(I18nService i18n) {
        this.i18n = i18n;
    }

    @Override
    public boolean confirm(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        try {
            return FxThread.<Boolean>supplyAsync(() -> showDialog(hostname, port, keyType, fingerprint, mismatch))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean showDialog(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        Alert alert;
        if (mismatch) {
            alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(i18n.get("hostkey.mismatch.title"));
            alert.setHeaderText(i18n.get("hostkey.mismatch.header", hostname, String.valueOf(port)));
            alert.getDialogPane().setContent(createContent(keyType, fingerprint, i18n.get("hostkey.mismatch.detail")));
        } else {
            alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(i18n.get("hostkey.unknown.title"));
            alert.setHeaderText(i18n.get("hostkey.unknown.header", hostname, String.valueOf(port)));
            alert.getDialogPane().setContent(createContent(keyType, fingerprint, null));
        }

        ButtonType trustButton = new ButtonType(i18n.get("hostkey.unknown.trust"));
        alert.getButtonTypes().setAll(trustButton, ButtonType.CANCEL);

        alert.getDialogPane().setPrefWidth(480);

        return alert.showAndWait()
                .filter(trustButton::equals)
                .isPresent();
    }

    private VBox createContent(String keyType, String fingerprint, String warningText) {
        VBox content = new VBox(8);

        Label typeLabel = new Label(i18n.get("hostkey.unknown.keyType", keyType));
        Label fpLabel = new Label(i18n.get("hostkey.unknown.fingerprint", fingerprint));
        fpLabel.setStyle("-fx-font-family: monospace; -fx-wrap-text: true;");

        if (warningText != null) {
            Label warning = new Label(warningText);
            warning.setStyle("-fx-text-fill: #cc0000; -fx-wrap-text: true;");
            content.getChildren().addAll(typeLabel, fpLabel, warning);
        } else {
            content.getChildren().addAll(typeLabel, fpLabel);
        }

        return content;
    }
}
