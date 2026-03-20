package com.jlshell.ui.theme;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;

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
}
