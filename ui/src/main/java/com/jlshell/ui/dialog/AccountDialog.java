package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.util.Base64;

public final class AccountDialog {

    private AccountDialog() {}

    public static void showLogin(Stage owner, I18nService i18n, ThemeService themeService,
                                 AccountService accountService) {
        show(owner, i18n, themeService, accountService, false);
    }

    public static void showRegister(Stage owner, I18nService i18n, ThemeService themeService,
                                    AccountService accountService) {
        show(owner, i18n, themeService, accountService, true);
    }

    private static void show(Stage owner, I18nService i18n, ThemeService themeService,
                             AccountService accountService, boolean register) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get(register ? "account.register.title" : "account.login.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);

        // ── 登录表单字段 ──
        TextField username = new TextField();
        username.setPromptText(i18n.get("account.username.prompt"));
        TextField email = new TextField();
        email.setPromptText("name@example.com");
        PasswordField password = new PasswordField();
        PasswordField confirmPassword = new PasswordField();

        // ── 验证码控件（默认隐藏） ──
        Label captchaLabel = new Label();
        ImageView captchaImage = new ImageView();
        captchaImage.setFitWidth(160);
        captchaImage.setFitHeight(50);
        captchaImage.setPreserveRatio(true);
        Button captchaRefreshBtn = new Button(i18n.get("account.captcha.refresh"));
        VBox captchaVisual = new VBox(4, captchaLabel, new HBox(8, captchaImage, captchaRefreshBtn));
        captchaVisual.setAlignment(Pos.CENTER_LEFT);
        TextField captchaAnswer = new TextField();
        captchaAnswer.setPromptText(i18n.get("account.captcha.prompt"));
        VBox captchaRow = new VBox(6, captchaVisual, captchaAnswer);
        captchaRow.setVisible(false);
        captchaRow.setManaged(false);

        // 验证码状态
        String[] captchaToken = {null};

        // 刷新验证码回调
        Runnable refreshCaptcha = register
                ? () -> fetchAndShowCaptcha(owner, i18n, accountService, "__register__",
                        "register", captchaLabel, captchaImage, captchaAnswer,
                        captchaRow, captchaToken)
                : null;

        captchaRefreshBtn.setOnAction(e -> {
            if (refreshCaptcha != null) {
                refreshCaptcha.run();
            } else if (username.getText() != null && !username.getText().isBlank()) {
                fetchAndShowCaptcha(owner, i18n, accountService, username.getText().strip(),
                        null, captchaLabel, captchaImage, captchaAnswer,
                        captchaRow, captchaToken);
            }
        });

        // ── 注册：邮箱验证码 ──
        Label verificationLabel = new Label(i18n.get("account.verification.code"));
        TextField verificationCode = new TextField();
        verificationCode.setPromptText(i18n.get("account.verification.code.prompt"));
        verificationCode.setPrefWidth(120);
        Button sendCodeBtn = new Button(i18n.get("account.verification.send"));
        boolean[] codeSent = {false};
        HBox verificationRow = new HBox(8, verificationCode, sendCodeBtn);
        verificationRow.setAlignment(Pos.CENTER_LEFT);
        if (register) {
            // 注册时默认隐藏，captcha 验证成功后显示
            verificationRow.setVisible(false);
            verificationRow.setManaged(false);
        }

        // 发送验证码
        sendCodeBtn.setOnAction(e -> {
            String emailValue = email.getText() == null ? "" : email.getText().strip();
            if (emailValue.isBlank()) {
                showError(owner, i18n, themeService, i18n.get("account.error.missingFields"));
                return;
            }
            String cToken = captchaToken[0];
            String cAnswer = captchaAnswer.getText() == null ? "" : captchaAnswer.getText().strip();
            if (cToken == null || cToken.isBlank() || cAnswer.isBlank()) {
                showError(owner, i18n, themeService, i18n.get("account.captcha.prompt"));
                return;
            }
            sendCodeBtn.setDisable(true);
            accountService.sendVerification(emailValue, cToken, cAnswer)
                    .whenComplete((v, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            // 验证码可能已过期，刷新
                            if (error instanceof AccountService.AccountHttpException httpEx
                                    && "captcha_required".equals(httpEx.errorCode())) {
                                if (refreshCaptcha != null) refreshCaptcha.run();
                            }
                            showError(owner, i18n, themeService, resolveErrorMessage(error, i18n));
                            sendCodeBtn.setDisable(false);
                            return;
                        }
                        // 成功发送
                        codeSent[0] = true;
                        verificationRow.setVisible(true);
                        verificationRow.setManaged(true);
                        showInfo(owner, i18n, themeService, i18n.get("account.verification.sent"));
                        startCooldown(sendCodeBtn, i18n, 60);
                    }));
        });

        // ── 构建表单 ──
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 20, 8, 20));
        int row = 0;
        grid.add(new Label(i18n.get("account.username")), 0, row);
        grid.add(username, 1, row++);
        if (register) {
            grid.add(new Label(i18n.get("account.email")), 0, row);
            grid.add(email, 1, row++);
        }
        grid.add(new Label(i18n.get("account.password")), 0, row);
        grid.add(password, 1, row++);
        if (register) {
            grid.add(new Label(i18n.get("account.confirmPassword")), 0, row);
            grid.add(confirmPassword, 1, row++);
        }
        // 验证码区域
        grid.add(captchaRow, 0, row, 2, 1);
        if (register) row++;
        // 注册时：验证码行
        if (register) {
            grid.add(verificationLabel, 0, row);
            grid.add(verificationRow, 1, row);
        }
        dialog.getDialogPane().setContent(grid);

        ButtonType submitType = new ButtonType(
                i18n.get(register ? "account.register.action" : "account.login.action"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        Button submit = (Button) dialog.getDialogPane().lookupButton(submitType);
        submit.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            String usernameValue = username.getText() == null ? "" : username.getText().strip();
            String passwordValue = password.getText();
            if (usernameValue.isBlank() || passwordValue == null || passwordValue.isBlank()) {
                showError(owner, i18n, themeService, i18n.get("account.error.missingFields"));
                return;
            }
            if (register) {
                String emailValue = email.getText() == null ? "" : email.getText().strip();
                if (emailValue.isBlank()) {
                    showError(owner, i18n, themeService, i18n.get("account.error.missingFields"));
                    return;
                }
                if (!passwordValue.equals(confirmPassword.getText())) {
                    showError(owner, i18n, themeService, i18n.get("account.error.passwordMismatch"));
                    return;
                }
                if (!codeSent[0]) {
                    showError(owner, i18n, themeService, i18n.get("account.verification.required"));
                    return;
                }
            }
            submit.setDisable(true);
            String emailValue = register ? (email.getText() == null ? "" : email.getText().strip()) : "";
            String cToken = captchaToken[0];
            String cAnswer = captchaAnswer.getText() == null ? "" : captchaAnswer.getText().strip();

            var future = register
                    ? accountService.register(usernameValue, emailValue, passwordValue,
                            verificationCode.getText() == null ? "" : verificationCode.getText().strip())
                    : accountService.login(usernameValue, passwordValue, cToken, cAnswer);
            password.clear();
            confirmPassword.clear();
            captchaAnswer.clear();
            verificationCode.clear();
            future.whenComplete((session, error) -> Platform.runLater(() -> {
                submit.setDisable(false);
                if (error != null) {
                    // 登录失败后检查是否需要验证码
                    if (!register) {
                        fetchAndShowCaptcha(owner, i18n, accountService, usernameValue,
                                null, captchaLabel, captchaImage, captchaAnswer,
                                captchaRow, captchaToken);
                    }
                    showError(owner, i18n, themeService, resolveErrorMessage(error, i18n));
                    return;
                }
                dialog.close();
                showInfo(owner, i18n, themeService, i18n.get("account.login.success"));
            }));
        });

        // 注册时：打开对话框时先获取验证码
        if (register && refreshCaptcha != null) {
            refreshCaptcha.run();
        }

        dialog.showAndWait();
    }

    /** 获取验证码并显示（支持图片验证码和文本验证码）。 */
    private static void fetchAndShowCaptcha(Stage owner, I18nService i18n,
                                            AccountService accountService, String username,
                                            String purpose,
                                            Label captchaLabel, ImageView captchaImage,
                                            TextField captchaAnswer,
                                            VBox captchaRow, String[] captchaToken) {
        accountService.fetchCaptcha(username, purpose).whenComplete((challenge, error) -> {
            Platform.runLater(() -> {
                if (error != null || challenge == null || !challenge.required()) {
                    captchaRow.setVisible(false);
                    captchaRow.setManaged(false);
                    captchaToken[0] = null;
                    return;
                }
                captchaToken[0] = challenge.token();
                // 优先显示图片验证码
                if (challenge.imageBase64() != null && !challenge.imageBase64().isBlank()) {
                    try {
                        String dataUri = challenge.imageBase64();
                        String base64;
                        if (dataUri.contains(",")) {
                            base64 = dataUri.substring(dataUri.indexOf(',') + 1);
                        } else {
                            base64 = dataUri;
                        }
                        byte[] imageBytes = Base64.getDecoder().decode(base64);
                        Image img = new Image(new ByteArrayInputStream(imageBytes));
                        captchaImage.setImage(img);
                        captchaImage.setVisible(true);
                        captchaImage.setManaged(true);
                        captchaLabel.setVisible(false);
                        captchaLabel.setManaged(false);
                    } catch (Exception e) {
                        // 图片解析失败，回退到文本
                        captchaImage.setVisible(false);
                        captchaImage.setManaged(false);
                        captchaLabel.setText(challenge.question() != null ? challenge.question() : "");
                        captchaLabel.setVisible(true);
                        captchaLabel.setManaged(true);
                    }
                } else if (challenge.question() != null && !challenge.question().isBlank()) {
                    captchaImage.setVisible(false);
                    captchaImage.setManaged(false);
                    captchaLabel.setText(challenge.question());
                    captchaLabel.setVisible(true);
                    captchaLabel.setManaged(true);
                } else {
                    captchaImage.setVisible(false);
                    captchaImage.setManaged(false);
                    captchaLabel.setVisible(false);
                    captchaLabel.setManaged(false);
                }
                captchaRow.setVisible(true);
                captchaRow.setManaged(true);
                captchaAnswer.requestFocus();
            });
        });
    }

    /** 发送验证码后的 60 秒倒计时。 */
    private static void startCooldown(Button btn, I18nService i18n, int seconds) {
        Thread t = new Thread(() -> {
            for (int i = seconds; i > 0; i--) {
                final int remaining = i;
                Platform.runLater(() ->
                        btn.setText(i18n.get("account.verification.resend", String.valueOf(remaining))));
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
            Platform.runLater(() -> {
                btn.setText(i18n.get("account.verification.send"));
                btn.setDisable(false);
            });
        }, "jlshell-verification-cooldown");
        t.setDaemon(true);
        t.start();
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

    private static void showError(Stage owner, I18nService i18n, ThemeService themeService, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(i18n.get("account.title"));
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        themeService.applyToDialog(alert);
        alert.showAndWait();
    }

    private static void showInfo(Stage owner, I18nService i18n, ThemeService themeService, String message) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("account.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);

        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setMinWidth(260);
        messageLabel.setMaxWidth(360);

        VBox content = new VBox(messageLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(18, 20, 8, 20));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
