package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.theme.ThemeService;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** Account forms use native controls. Network completions are guarded by the form lifecycle. */
public final class AccountDialog {
    private AccountDialog() {}

    public static CompletableFuture<Void> showLogin(Object owner, I18nService i18n, ThemeService themes, AccountService account) {
        return show(i18n, themes, account, false);
    }

    public static CompletableFuture<Void> showRegister(Object owner, I18nService i18n, ThemeService themes, AccountService account) {
        return show(i18n, themes, account, true);
    }

    public static CompletableFuture<Void> showBrowserLogin(Object owner, I18nService i18n, ThemeService themes, AccountService account) {
        return SwingFormDialog.showMessageAsync(i18n.get("account.login.web.title"), "jlshell.account.browser", i18n, themes,
                () -> new BrowserForm(i18n, account), 580, 280);
    }

    public static CompletableFuture<Void> showChangePassword(Object owner, I18nService i18n, ThemeService themes, AccountService account) {
        return SwingFormDialog.showAsync(i18n.get("account.changePassword.title"), "jlshell.account.password", i18n, themes, () -> {
            PasswordForm form = new PasswordForm(i18n, account);
            return new SwingFormDialog.Form<>(form, form::submit, i18n.get("account.changePassword"));
        }, 560, 340).thenApply(ignored -> null);
    }

    private static CompletableFuture<Void> show(I18nService i18n, ThemeService themes, AccountService account, boolean register) {
        return SwingFormDialog.showAsync(i18n.get(register ? "account.register.title" : "account.login.title"),
                "jlshell.account", i18n, themes, () -> {
                    LoginForm form = new LoginForm(i18n, account, register);
                    return new SwingFormDialog.Form<>(form, form::submit,
                            i18n.get(register ? "account.register.action" : "account.login.action"));
                }, 580, register ? 590 : 430).thenApply(ignored -> null);
    }

    private abstract static class AccountForm extends JPanel implements AutoCloseable {
        final I18nService i18n;
        final AccountService account;
        final JLabel status = new JLabel(" ");
        final JPanel fields = new JPanel(new GridBagLayout());
        boolean closed;
        boolean busy;
        int row;

        AccountForm(I18nService i18n, AccountService account) {
            super(new BorderLayout(0, 12));
            this.i18n = i18n;
            this.account = account;
            setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
            add(fields, BorderLayout.NORTH);
            status.setName("account.status");
            add(status, BorderLayout.SOUTH);
        }

        void field(String label, JComponent control) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(6, 0, 6, 12);
            fields.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            fields.add(control, c);
        }

        void busy(boolean value) {
            busy = value;
            JRootPane root = getRootPane();
            if (root != null && root.getDefaultButton() != null) root.getDefaultButton().setEnabled(!value);
        }

        void complete(Throwable error) {
            if (closed) return;
            busy(false);
            if (error == null) {
                Window window = SwingUtilities.getWindowAncestor(this);
                if (window != null) window.dispose();
            } else status.setText(resolveErrorMessage(error, i18n));
        }

        @Override public void close() { closed = true; }
    }

    private static final class BrowserForm extends AccountForm {
        private AccountService.BrowserLoginAttempt attempt;
        private boolean completed;
        BrowserForm(I18nService i18n, AccountService account) {
            super(i18n, account);
            status.setText(i18n.get("account.login.web.waiting"));
            JTextField url = new JTextField();
            url.setEditable(false);
            field("URL", url);
            JButton reopen = new JButton(i18n.get("account.login.web.reopen"));
            JButton copy = new JButton(i18n.get("account.login.web.copy"));
            reopen.setEnabled(false);
            copy.setEnabled(false);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            actions.add(reopen);
            actions.add(copy);
            add(actions);
            reopen.addActionListener(event -> CompletableFuture.supplyAsync(attempt::openBrowser)
                    .thenAccept(opened -> SwingUtilities.invokeLater(() -> {
                        if (!closed && !opened) status.setText(i18n.get("account.login.web.manual"));
                    })));
            copy.addActionListener(event -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url.getText()), null);
                status.setText(i18n.get("account.login.web.copied"));
            });
            CompletableFuture.supplyAsync(account::loginWithBrowser).whenComplete((started, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) { if (started != null) started.cancel(); return; }
                if (error != null) { status.setText(resolveErrorMessage(error, i18n)); return; }
                attempt = started;
                url.setText(started.authorizationUri().toString());
                reopen.setEnabled(true);
                copy.setEnabled(true);
                status.setText(i18n.get(started.browserOpened() ? "account.login.web.waiting" : "account.login.web.manual"));
                started.completion().whenComplete((session, failure) -> SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    completed = true;
                    reopen.setEnabled(false);
                    copy.setEnabled(false);
                    complete(failure);
                }));
            }));
        }
        @Override public void close() {
            super.close();
            if (!completed && attempt != null) attempt.cancel();
        }
    }

    private static final class LoginForm extends AccountForm {
        final boolean register;
        final JTextField username = new JTextField(), email = new JTextField(), captchaAnswer = new JTextField(), verification = new JTextField();
        final JPasswordField password = new JPasswordField(), confirm = new JPasswordField();
        final JPanel captchaRow = new JPanel(new BorderLayout(8, 6));
        final JLabel captcha = new JLabel();
        final JButton sendCode;
        Timer cooldown;
        String captchaToken;
        boolean codeSent;
        long captchaRequest;

        LoginForm(I18nService i18n, AccountService account, boolean register) {
            super(i18n, account);
            this.register = register;
            username.setName("account.username");
            password.setName("account.password");
            field(i18n.get("account.username"), username);
            if (register) field(i18n.get("account.email"), email);
            field(i18n.get("account.password"), password);
            if (register) field(i18n.get("account.confirmPassword"), confirm);
            JButton refresh = new JButton(i18n.get("action.refresh"));
            refresh.setToolTipText(i18n.get("account.captcha.refresh"));
            refresh.addActionListener(event -> refreshCaptcha());
            captchaRow.add(captcha);
            captchaRow.add(refresh, BorderLayout.EAST);
            captchaRow.add(captchaAnswer, BorderLayout.SOUTH);
            captchaRow.setVisible(false);
            field("", captchaRow);
            sendCode = new JButton(i18n.get("account.verification.send"));
            if (register) {
                JPanel code = new JPanel(new BorderLayout(8, 0));
                code.add(verification);
                code.add(sendCode, BorderLayout.EAST);
                field(i18n.get("account.verification.code"), code);
                sendCode.addActionListener(event -> sendVerification());
                refreshCaptcha();
            }
        }

        void refreshCaptcha() {
            String user = username.getText().strip();
            if (!register && user.isBlank()) return;
            long request = ++captchaRequest;
            account.fetchCaptcha(register ? "__register__" : user, register ? "register" : null)
                    .thenApply(challenge -> {
                        Icon image = null;
                        if (challenge != null && challenge.imageBase64() != null) {
                            try {
                                String encoded = challenge.imageBase64();
                                byte[] bytes = Base64.getDecoder().decode(encoded.substring(encoded.indexOf(',') + 1));
                                var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                                if (decoded != null) image = new ImageIcon(decoded.getScaledInstance(150, 46, Image.SCALE_SMOOTH));
                            } catch (Exception ignored) { /* Text challenges are also supported. */ }
                        }
                        return new Captcha(challenge, image);
                    }).whenComplete((loaded, error) -> SwingUtilities.invokeLater(() -> {
                        if (closed || request != captchaRequest) return;
                        if (error != null) { status.setText(resolveErrorMessage(error, i18n)); return; }
                        var challenge = loaded.challenge();
                        boolean required = challenge != null && challenge.required();
                        captchaToken = required ? challenge.token() : null;
                        captchaRow.setVisible(required);
                        captchaAnswer.setText("");
                        captcha.setIcon(loaded.image());
                        captcha.setText(required && loaded.image() == null ? challenge.question() : "");
                        fields.revalidate();
                        fields.repaint();
                        if (required) captchaAnswer.requestFocusInWindow();
                    }));
        }

        void sendVerification() {
            String address = email.getText().strip();
            if (address.isBlank()) { status.setText(i18n.get("account.error.missingFields")); return; }
            if (captchaToken == null || captchaAnswer.getText().isBlank()) {
                status.setText(i18n.get("account.captcha.prompt"));
                return;
            }
            sendCode.setEnabled(false);
            account.sendVerification(address, captchaToken, captchaAnswer.getText().strip())
                    .whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
                        if (closed) return;
                        if (error != null) {
                            status.setText(resolveErrorMessage(error, i18n));
                            sendCode.setEnabled(true);
                            refreshCaptcha();
                            return;
                        }
                        codeSent = true;
                        status.setText(i18n.get("account.verification.sent"));
                        int[] remaining = {60};
                        sendCode.setText(i18n.get("account.verification.resend", remaining[0]));
                        cooldown = new Timer(1000, event -> {
                            if (--remaining[0] > 0) sendCode.setText(i18n.get("account.verification.resend", remaining[0]));
                            else {
                                cooldown.stop();
                                sendCode.setText(i18n.get("account.verification.send"));
                                sendCode.setEnabled(true);
                            }
                        });
                        cooldown.start();
                    }));
        }

        Boolean submit() {
            if (busy) return null;
            String user = username.getText().strip();
            String secret = takePassword(password);
            String confirmation = takePassword(confirm);
            if (user.isBlank() || secret.isBlank() || register && email.getText().isBlank()) {
                status.setText(i18n.get("account.error.missingFields"));
                return null;
            }
            if (register && !secret.equals(confirmation)) {
                status.setText(i18n.get("account.error.passwordMismatch"));
                return null;
            }
            if (register && !codeSent) { status.setText(i18n.get("account.verification.required")); return null; }
            busy(true);
            password.setText("");
            confirm.setText("");
            var request = register ? account.register(user, email.getText().strip(), secret, verification.getText().strip())
                    : account.login(user, secret, captchaToken, captchaAnswer.getText().strip());
            captchaAnswer.setText("");
            verification.setText("");
            request.whenComplete((session, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return;
                if (error != null && !register) refreshCaptcha();
                complete(error);
            }));
            return null;
        }

        @Override public void close() {
            super.close();
            if (cooldown != null) cooldown.stop();
            password.setText("");
            confirm.setText("");
            captchaAnswer.setText("");
            verification.setText("");
        }
    }

    private record Captcha(AccountService.CaptchaChallenge challenge, Icon image) {}

    private static final class PasswordForm extends AccountForm {
        final JPasswordField previous = new JPasswordField(), password = new JPasswordField(), confirm = new JPasswordField();
        PasswordForm(I18nService i18n, AccountService account) {
            super(i18n, account);
            field(i18n.get("account.changePassword.oldPassword"), previous);
            field(i18n.get("account.changePassword.newPassword"), password);
            field(i18n.get("account.changePassword.confirmPassword"), confirm);
        }
        Boolean submit() {
            if (busy) return null;
            String old = takePassword(previous), next = takePassword(password), confirmation = takePassword(confirm);
            if (old.isBlank() || next.isBlank()) status.setText(i18n.get("account.error.missingFields"));
            else if (next.length() < 8) status.setText(i18n.get("account.changePassword.error.minLength"));
            else if (!next.equals(confirmation)) status.setText(i18n.get("account.changePassword.error.mismatch"));
            else {
                busy(true);
                previous.setText(""); password.setText(""); confirm.setText("");
                account.changePassword(old, next).whenComplete((session, error) -> SwingUtilities.invokeLater(() -> {
                    if (closed) return;
                    if (error == null) status.setText(i18n.get("account.changePassword.success"));
                    complete(error);
                }));
            }
            return null;
        }
        @Override public void close() {
            super.close();
            previous.setText(""); password.setText(""); confirm.setText("");
        }
    }

    private static String takePassword(JPasswordField field) {
        char[] value = field.getPassword();
        try { return new String(value); }
        finally { Arrays.fill(value, '\0'); }
    }

    private static String resolveErrorMessage(Throwable error, I18nService i18n) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AccountService.AccountHttpException httpEx) {
                // 优先按 error code 映射
                String code = httpEx.errorCode();
                if (code != null) {
                    String i18nKey = switch (code) {
                        case "invalid_credentials" -> "account.error.unauthorized";
                        case "captcha_required" -> "account.captcha.prompt";
                        case "verification_invalid" -> "account.error.verificationInvalid";
                        case "verification_rate_limited" -> "account.error.verificationRateLimited";
                        case "verification_cooldown" -> "account.error.verificationCooldown";
                        case "email_send_failed" -> "account.error.emailSendFailed";
                        case "username_exists" -> "account.error.usernameExists";
                        case "email_exists" -> "account.error.emailExists";
                        default -> null;
                    };
                    if (i18nKey != null) return i18n.get(i18nKey);
                }
                // 回退到 HTTP 状态码映射
                return switch (httpEx.statusCode()) {
                    case 401 -> i18n.get("account.error.unauthorized");
                    case 403 -> i18n.get("account.error.forbidden");
                    case 409 -> i18n.get("account.error.conflict");
                    case 429 -> i18n.get("account.error.rateLimited");
                    default -> i18n.get("account.error.httpError", String.valueOf(httpEx.statusCode()));
                };
            }
            current = current.getCause();
        }
        return rootMessage(error);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
