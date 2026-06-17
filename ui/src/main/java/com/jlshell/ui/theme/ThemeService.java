package com.jlshell.ui.theme;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

/**
 * 主题管理服务。
 * UI 主题（CSS 样式表）与终端配色方案独立管理。
 */
public class ThemeService {

    private final ObjectProperty<AppTheme> currentTheme = new SimpleObjectProperty<>(AppTheme.DARK);
    private final ObjectProperty<TerminalColorScheme> activeColorScheme;
    private final AppSettingsService appSettings;
    private final ColorSchemeRegistry registry;

    public ThemeService(AppSettingsService appSettings, ColorSchemeRegistry registry) {
        this.appSettings = appSettings;
        this.registry = registry;
        String savedTheme = appSettings.get("ui.theme", "DARK");
        currentTheme.set("LIGHT".equals(savedTheme) ? AppTheme.LIGHT : AppTheme.DARK);
        String savedScheme = appSettings.get("terminal.colorScheme.active", "dark");
        TerminalColorScheme scheme = registry.findByName(savedScheme).orElse(TerminalColorScheme.dark());
        this.activeColorScheme = new SimpleObjectProperty<>(scheme);
    }

    public ObjectProperty<AppTheme> currentThemeProperty() {
        return currentTheme;
    }

    public AppTheme currentTheme() {
        return currentTheme.get();
    }

    public void setTheme(AppTheme theme) {
        currentTheme.set(theme);
    }

    public ObjectProperty<TerminalColorScheme> activeColorSchemeProperty() {
        return activeColorScheme;
    }

    public TerminalColorScheme activeColorScheme() {
        return activeColorScheme.get();
    }

    public void setActiveColorScheme(TerminalColorScheme scheme) {
        activeColorScheme.set(scheme);
        appSettings.set("terminal.colorScheme.active", scheme.name());
    }

    public void apply(Scene scene) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(getClass().getResource(currentTheme().stylesheet()).toExternalForm());
    }

    public void applyToDialog(DialogPane pane) {
        pane.getStylesheets().add(getClass().getResource(currentTheme().stylesheet()).toExternalForm());
    }

    public void applyToDialog(Dialog<?> dialog) {
        applyToDialog(dialog.getDialogPane());
    }

    public ColorSchemeRegistry registry() {
        return registry;
    }
}
