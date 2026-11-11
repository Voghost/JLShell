package com.jlshell.ui.swing;

import com.jlshell.ssh.support.HostKeyConfirmationCallback.TrustDecision;
import com.jlshell.ui.service.I18nService;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;

/** Non-blocking host-key confirmation, with cancellation shared with the SSH timeout. */
public final class SwingHostKeyDialog {
    private SwingHostKeyDialog() { }

    public static Request show(I18nService i18n, String hostname, int port,
                               String keyType, String fingerprint, boolean mismatch) {
        Request request = new Request();
        SwingUtilities.invokeLater(() -> {
            if (request.result.isDone()) return;
            try {
                request.open(i18n, hostname, port, keyType, fingerprint, mismatch);
            } catch (RuntimeException error) {
                request.result.completeExceptionally(error);
                request.dispose();
            }
        });
        return request;
    }

    public static final class Request implements AutoCloseable {
        private final CompletableFuture<TrustDecision> result = new CompletableFuture<>();
        private JDialog dialog; // EDT only.

        private Request() { }

        public CompletableFuture<TrustDecision> result() { return result; }

        private void open(I18nService i18n, String hostname, int port,
                          String keyType, String fingerprint, boolean mismatch) {
            String prefix = mismatch ? "hostkey.mismatch" : "hostkey.unknown";
            dialog = new SwingModalDialog(SwingDialogChrome.owner(), i18n.get(prefix + ".title"));
            dialog.setName(mismatch ? "jlshell.message.warning" : "jlshell.message.confirmation");
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            SwingDialogChrome.configure(dialog);

            JPanel details = new JPanel();
            details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
            var heading = new SwingWrappingLabel(i18n.get(prefix + ".header", hostname, String.valueOf(port)));
            heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
            details.add(heading);
            details.add(Box.createVerticalStrut(10));
            details.add(new SwingWrappingLabel(i18n.get("hostkey.unknown.keyType", keyType)));
            var fingerprintLabel = new SwingWrappingLabel(i18n.get("hostkey.unknown.fingerprint", fingerprint));
            fingerprintLabel.setName("hostkey.fingerprint");
            details.add(fingerprintLabel);
            if (mismatch) details.add(new SwingWrappingLabel(i18n.get("hostkey.mismatch.detail")));
            JPanel body = new JPanel(new BorderLayout(16, 0));
            body.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
            body.add(new JLabel(UIManager.getIcon(mismatch ? "OptionPane.warningIcon" : "OptionPane.questionIcon")), BorderLayout.WEST);
            body.add(details, BorderLayout.CENTER);
            dialog.add(body, BorderLayout.CENTER);

            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
            footer.setBorder(SwingTheme.border(1, 0, 0, 0));
            JButton cancel = button(i18n.get("settings.cancel"), "dialog.cancel", TrustDecision.REJECT);
            JButton save = button(i18n.get("hostkey.trustAlways"), "hostkey.save", TrustDecision.TRUST_AND_SAVE);
            JButton once = button(i18n.get("hostkey.trustOnce"), "hostkey.once", TrustDecision.TRUST_ONCE);
            footer.add(cancel);
            footer.add(save);
            footer.add(once);
            dialog.add(footer, BorderLayout.SOUTH);
            dialog.getRootPane().setDefaultButton(once);
            dialog.getRootPane().registerKeyboardAction(event -> close(), KeyStroke.getKeyStroke("ESCAPE"),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { close(); }
                @Override public void windowClosed(WindowEvent event) { result.complete(TrustDecision.REJECT); }
            });
            dialog.setSize(580, dialog.getPreferredSize().height);
            dialog.setLocationRelativeTo(dialog.getOwner());
            if (!result.isDone()) dialog.setVisible(true);
            else dispose();
        }

        private JButton button(String text, String name, TrustDecision decision) {
            JButton button = new JButton(text);
            button.setName(name);
            IdeaFormStyle.apply(button);
            button.addActionListener(event -> {
                result.complete(decision);
                dispose();
            });
            return button;
        }

        private void dispose() {
            if (dialog != null) dialog.dispose();
        }

        @Override public void close() {
            result.complete(TrustDecision.REJECT);
            SwingUtilities.invokeLater(this::dispose);
        }
    }
}
