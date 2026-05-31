package com.jlshell.ui.theme;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

/**
 * 主题管理服务。
 */
public class ThemeService {

    private final ObjectProperty<AppTheme> currentTheme = new SimpleObjectProperty<>(AppTheme.DARK);

    public ObjectProperty<AppTheme> currentThemeProperty() {
        return currentTheme;
    }

    public AppTheme currentTheme() {
        return currentTheme.get();
    }

    public void setTheme(AppTheme theme) {
        currentTheme.set(theme);
    }

    public void apply(Scene scene) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(getClass().getResource(currentTheme().stylesheet()).toExternalForm());
    }

    /** Apply the current theme stylesheet to a dialog pane. */
    public void applyToDialog(DialogPane pane) {
        pane.getStylesheets().add(getClass().getResource(currentTheme().stylesheet()).toExternalForm());
    }

    /** Apply the current theme stylesheet to a dialog. */
    public void applyToDialog(Dialog<?> dialog) {
        applyToDialog(dialog.getDialogPane());
    }
}
