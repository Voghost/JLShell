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

        TextField displayName = new TextField();
        displayName.setPromptText(i18n.get("account.displayName"));
        TextField email = new TextField();
        email.setPromptText("name@example.com");
        PasswordField password = new PasswordField();
        PasswordField confirmPassword = new PasswordField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 20, 8, 20));
        int row = 0;
        if (register) {
            grid.add(new Label(i18n.get("account.displayName")), 0, row);
            grid.add(displayName, 1, row++);
        }
        grid.add(new Label(i18n.get("account.email")), 0, row);
        grid.add(email, 1, row++);
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
            String emailValue = email.getText() == null ? "" : email.getText().strip();
            String passwordValue = password.getText();
            if (emailValue.isBlank() || passwordValue == null || passwordValue.isBlank()) {
                showError(owner, i18n, i18n.get("account.error.missingFields"));
                return;
            }
            if (register && !passwordValue.equals(confirmPassword.getText())) {
                showError(owner, i18n, i18n.get("account.error.passwordMismatch"));
                return;
            }
            submit.setDisable(true);
            var future = register
                    ? accountService.register(emailValue, passwordValue, displayName.getText())
                    : accountService.login(emailValue, passwordValue);
            password.clear();
            confirmPassword.clear();
            future.whenComplete((session, error) -> Platform.runLater(() -> {
                submit.setDisable(false);
                if (error != null) {
                    showError(owner, i18n, rootMessage(error));
                    return;
                }
                dialog.close();
                Alert ok = new Alert(Alert.AlertType.INFORMATION, i18n.get("account.login.success"), ButtonType.OK);
                ok.setTitle(i18n.get("account.title"));
                ok.setHeaderText(null);
                if (owner != null) ok.initOwner(owner);
                ok.showAndWait();
            }));
        });

        dialog.showAndWait();
    }

    private static void showError(Stage owner, I18nService i18n, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(i18n.get("account.title"));
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
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

