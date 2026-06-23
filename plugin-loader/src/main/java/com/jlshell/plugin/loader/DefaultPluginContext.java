package com.jlshell.plugin.loader;

import java.util.Locale;
import java.util.Optional;

import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultPluginContext implements PluginContext {

    private static final Logger hostLog = LoggerFactory.getLogger("jlshell.plugin");

    private final String pluginId;
    private final String sessionId;
    private final CapabilityRegistry registry;
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

    public DefaultPluginContext(String pluginId, String sessionId,
                                CapabilityRegistry registry,
                                Optional<SshSessionContext> sshSession, Callbacks callbacks) {
        this.pluginId = pluginId;
        this.sessionId = sessionId;
        this.registry = registry;
        this.sshSession = sshSession;
        this.callbacks = callbacks;
    }

    public DefaultPluginContext(String pluginId, Optional<SshSessionContext> sshSession, Callbacks callbacks) {
        this(pluginId, null, CapabilityRegistry.empty(), sshSession, callbacks);
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public CapabilityRegistry capabilityRegistry() {
        if (registry instanceof CapabilityRegistryImpl impl) {
            return new PluginCapabilityRegistryView(impl, pluginId);
        }
        return registry;
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

    @Override
    public void debug(String message) {
        hostLog.debug("[{}] {}", pluginId, message);
    }

    @Override
    public void info(String message) {
        hostLog.info("[{}] {}", pluginId, message);
    }

    @Override
    public void warn(String message) {
        hostLog.warn("[{}] {}", pluginId, message);
    }

    @Override
    public void error(String message) {
        hostLog.error("[{}] {}", pluginId, message);
    }

    @Override
    public void error(String message, Throwable t) {
        hostLog.error("[{}] {}", pluginId, message, t);
    }
}
