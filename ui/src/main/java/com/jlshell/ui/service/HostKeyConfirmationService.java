package com.jlshell.ui.service;

import com.jlshell.ssh.support.HostKeyConfirmationCallback;
import com.jlshell.ui.swing.SwingHostKeyDialog;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Shows a Swing host-key confirmation while the SSH transport waits for the decision. */
public class HostKeyConfirmationService implements HostKeyConfirmationCallback {
    private static final long TIMEOUT_SECONDS = 60;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HostKeyConfirmationService.class);
    private final I18nService i18n;

    public HostKeyConfirmationService(I18nService i18n) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    @Override
    public boolean confirm(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        return confirmDecision(hostname, port, keyType, fingerprint, mismatch).accepted();
    }

    @Override
    public TrustDecision confirmDecision(String hostname, int port, String keyType, String fingerprint, boolean mismatch) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Host-key verification must run on the SSH transport thread");
        }
        try (var request = SwingHostKeyDialog.show(i18n, hostname, port, keyType, fingerprint, mismatch)) {
            try {
                return request.result().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.TimeoutException error) {
                log.info("SSH host-key confirmation timed out for {}:{}", hostname, port);
            } catch (java.util.concurrent.ExecutionException error) {
                log.warn("Cannot display SSH host-key confirmation for {}:{}", hostname, port, error.getCause());
            }
        }
        return TrustDecision.REJECT;
    }
}
