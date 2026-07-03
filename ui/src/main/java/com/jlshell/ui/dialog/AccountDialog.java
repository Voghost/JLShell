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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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

        TextField username = new TextField();
        username.setPromptText(i18n.get("account.username.prompt"));
        TextField email = new TextField();
        email.setPromptText("name@example.com");
        PasswordField password = new PasswordField();
        PasswordField confirmPassword = new PasswordField();

        // 验证码控件（默认隐藏）
        Label captchaLabel = new Label();
        TextField captchaAnswer = new TextField();
        captchaAnswer.setPromptText(i18n.get("account.captcha.prompt"));
        HBox captchaRow = new HBox(8, captchaLabel, captchaAnswer);
        captchaRow.setVisible(false);
        captchaRow.setManaged(false);

        // 验证码状态：token 在登录失败后由服务端返回
        String[] captchaToken = {null};

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
        grid.add(captchaRow, 0, row, 2, 1);
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
            }
            submit.setDisable(true);
            String emailValue = register ? (email.getText() == null ? "" : email.getText().strip()) : "";
            String cToken = captchaToken[0];
            String cAnswer = captchaAnswer.getText() == null ? "" : captchaAnswer.getText().strip();

            var future = register
                    ? accountService.register(usernameValue, emailValue, passwordValue)
                    : accountService.login(usernameValue, passwordValue, cToken, cAnswer);
            password.clear();
            confirmPassword.clear();
            captchaAnswer.clear();
            future.whenComplete((session, error) -> Platform.runLater(() -> {
                submit.setDisable(false);
                if (error != null) {
                    // 登录失败后检查是否需要验证码
                    if (!register) {
                        fetchAndShowCaptcha(owner, i18n, accountService, usernameValue,
                                captchaLabel, captchaAnswer, captchaRow, captchaToken);
                    }
                    showError(owner, i18n, themeService, resolveErrorMessage(error, i18n));
                    return;
                }
                dialog.close();
                showInfo(owner, i18n, themeService, i18n.get("account.login.success"));
            }));
        });

        dialog.showAndWait();
    }

    /** 登录失败后获取验证码，若服务端要求则显示验证码行。 */
    private static void fetchAndShowCaptcha(Stage owner, I18nService i18n,
                                            AccountService accountService, String username,
                                            Label captchaLabel, TextField captchaAnswer,
                                            HBox captchaRow, String[] captchaToken) {
        accountService.fetchCaptcha(username).whenComplete((challenge, error) -> {
            Platform.runLater(() -> {
                if (error != null || challenge == null || !challenge.required()) {
                    captchaRow.setVisible(false);
                    captchaRow.setManaged(false);
                    captchaToken[0] = null;
                    return;
                }
                captchaLabel.setText(challenge.question());
                captchaToken[0] = challenge.token();
                captchaRow.setVisible(true);
                captchaRow.setManaged(true);
                captchaAnswer.requestFocus();
            });
        });
    }

    private static String resolveErrorMessage(Throwable error, I18nService i18n) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AccountService.AccountHttpException httpEx) {
                return switch (httpEx.statusCode()) {
                    case 401 -> i18n.get("account.error.unauthorized");
                    case 403 -> i18n.get("account.error.forbidden");
                    case 409 -> i18n.get("account.error.conflict");
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
