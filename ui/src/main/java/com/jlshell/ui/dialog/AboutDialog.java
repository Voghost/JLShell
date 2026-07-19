package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

/**
 * 独立的"关于"对话框，从菜单栏 Help → About 打开。
 * 复用 PreferencesDialog 的版本号读取逻辑和内容布局。
 */
public class AboutDialog {

    private AboutDialog() {}

    public static void show(Stage owner, I18nService i18n, ThemeService themeService) {
        show(owner, i18n, themeService, null);
    }

    public static void show(Stage owner, I18nService i18n, ThemeService themeService, Runnable checkUpdatesAction) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("menu.help.about"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);

        Runnable dialogCheckUpdatesAction = checkUpdatesAction == null ? null : () -> {
            dialog.close();
            checkUpdatesAction.run();
        };
        dialog.getDialogPane().setContent(PreferencesDialog.buildAboutPane(i18n, dialogCheckUpdatesAction));
        dialog.getDialogPane().setPrefSize(860, 680);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
