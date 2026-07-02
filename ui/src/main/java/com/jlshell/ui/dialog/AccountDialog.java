package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
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
            grid.add(confirmPassword, 1, row);
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
            }
            submit.setDisable(true);
            String emailValue = register ? (email.getText() == null ? "" : email.getText().strip()) : "";
            var future = register
                    ? accountService.register(usernameValue, emailValue, passwordValue)
                    : accountService.login(usernameValue, passwordValue);
            password.clear();
            confirmPassword.clear();
            future.whenComplete((session, error) -> Platform.runLater(() -> {
                submit.setDisable(false);
                if (error != null) {
                    showError(owner, i18n, themeService, resolveErrorMessage(error, i18n));
                    return;
                }
                dialog.close();
            }));
        });

        dialog.showAndWait();
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
