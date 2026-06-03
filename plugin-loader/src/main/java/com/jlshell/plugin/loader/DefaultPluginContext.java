package com.jlshell.plugin.loader;

import java.util.Locale;
import java.util.Optional;

import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;

public class DefaultPluginContext implements PluginContext {

    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final SimpleObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());
    private final Optional<SshSessionContext> sshSession;
    private final Callbacks callbacks;

    public interface Callbacks {
        void openTab(String title, Node content);
        void closeTab();
        void updateTabTitle(String title);
        String resolveI18n(String key, String fallback);
    }

    public DefaultPluginContext(Optional<SshSessionContext> sshSession, Callbacks callbacks) {
        this.sshSession = sshSession;
        this.callbacks = callbacks;
    }

    @Override
    public String themeName() {
        return themeName.get();
    }

    @Override
    public ReadOnlyStringProperty themeNameProperty() {
        return themeName;
    }

    public StringProperty writableThemeNameProperty() {
        return themeName;
    }

    @Override
    public Locale locale() {
        return locale.get();
    }

    @Override
    public ReadOnlyObjectProperty<Locale> localeProperty() {
        return locale;
    }

    public SimpleObjectProperty<Locale> writableLocaleProperty() {
        return locale;
    }

    @Override
    public Optional<SshSessionContext> sshSession() {
        return sshSession;
    }

    @Override
    public void openTab(String title, Node content) {
        callbacks.openTab(title, content);
    }

    @Override
    public void closeTab() {
        callbacks.closeTab();
    }

    @Override
    public void updateTabTitle(String title) {
        callbacks.updateTabTitle(title);
    }

    @Override
    public String resolveI18n(String key, String fallback) {
        return callbacks.resolveI18n(key, fallback);
    }

    @Override
    public void showNotification(String message, NotificationLevel level) {
        // TODO: implement notification UI
    }
}
