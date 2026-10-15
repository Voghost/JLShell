package com.jlshell.ui.service;

import java.util.concurrent.TimeUnit;

import com.jlshell.ssh.support.HostKeyConfirmationCallback;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Shows the themed Swing confirmation for unknown or changed SSH host keys.
 * Blocks the calling SSHJ transport thread until the user responds.
 */
public class HostKeyConfirmationService implements HostKeyConfirmationCallback {

    private static final long TIMEOUT_SECONDS = 60;

    private final I18nService i18n;
    private final ThemeService themeService;

    public HostKeyConfirmationService(I18nService i18n, ThemeService themeService) {
        this.i18n = i18n;
        this.themeService = themeService;
    }

    @Override
    public boolean confirm(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        return confirmDecision(hostname, port, keyType, fingerprint, mismatch).accepted();
    }

    @Override
    public TrustDecision confirmDecision(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        var active = new java.util.concurrent.atomic.AtomicReference<Alert>();
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        var response = FxThread.supplyAsync(() -> {
            if (expired.get()) return TrustDecision.REJECT;
            Alert alert = new Alert(mismatch ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION);
            active.set(alert);
            alert.setTitle(i18n.get(mismatch ? "hostkey.mismatch.title" : "hostkey.unknown.title"));
            alert.setHeaderText(i18n.get(mismatch ? "hostkey.mismatch.header" : "hostkey.unknown.header", hostname, String.valueOf(port)));
            alert.getDialogPane().setContent(createContent(keyType, fingerprint, mismatch ? i18n.get("hostkey.mismatch.detail") : null));
            ButtonType once = new ButtonType(i18n.get("hostkey.trustOnce"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            ButtonType save = new ButtonType(i18n.get("hostkey.trustAlways"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(once, save, ButtonType.CANCEL);
            alert.getDialogPane().lookupButton(once).getProperties().put("swing.name", "hostkey.once");
            alert.getDialogPane().lookupButton(save).getProperties().put("swing.name", "hostkey.save");
            var result = com.jlshell.ui.swing.SwingAlerts.showAndWait(alert, themeService, i18n);
            if (expired.get()) return TrustDecision.REJECT;
            if (result.orElse(null) == once) return TrustDecision.TRUST_ONCE;
            if (result.orElse(null) == save) return TrustDecision.TRUST_AND_SAVE;
            return TrustDecision.REJECT;
        });
        try {
            return response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // Timeout and UI failure both reject the key; a stale dialog must not grant trust later.
        }
        expired.set(true);
        FxThread.run(() -> { if (active.get() != null) active.get().close(); });
        return TrustDecision.REJECT;
    }

    private VBox createContent(String keyType, String fingerprint, String warningText) {
        VBox content = new VBox(8);

        Label typeLabel = new Label(i18n.get("hostkey.unknown.keyType", keyType));
        Label fpLabel = new Label(i18n.get("hostkey.unknown.fingerprint", fingerprint));
        fpLabel.setWrapText(true);
        fpLabel.setId("hostkey.fingerprint");

        if (warningText != null) {
            Label warning = new Label(warningText);
            warning.setWrapText(true);
            content.getChildren().addAll(typeLabel, fpLabel, warning);
        } else {
            content.getChildren().addAll(typeLabel, fpLabel);
        }

        return content;
    }
}
