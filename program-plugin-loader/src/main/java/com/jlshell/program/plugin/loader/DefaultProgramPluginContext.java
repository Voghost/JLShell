package com.jlshell.program.plugin.loader;

import java.util.Locale;
import java.util.Optional;

import com.jlshell.plugin.api.NotificationLevel;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.ProgramPluginContext;
import com.jlshell.plugin.api.SshSessionContext;
import com.jlshell.plugin.api.event.HostEvents;
import com.jlshell.plugin.api.project.ProjectIntegration;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.security.PluginAccessPolicy;
import com.jlshell.plugin.api.session.ProgramSessionIntegration;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import com.jlshell.program.api.AccountSessionService;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultProgramPluginContext implements ProgramPluginContext, PluginContext {

    private static final Logger hostLog = LoggerFactory.getLogger("jlshell.program-plugin");

    private final String pluginId;
    private final CapabilityRegistry registry;
    private final CapabilityBus capabilityBus;
    private final PluginStorage storage;
    private final SecureStorage secureStorage;
    private final ProjectIntegration projectIntegration;
    private final HostEvents hostEvents;
    private final ProgramSessionIntegration sessionIntegration;
    private final PluginAccessPolicy accessPolicy;
    private final AccountSessionService accountSession;
    private final Callbacks callbacks;
    private final StringProperty themeName = new SimpleStringProperty("dark");
    private final SimpleObjectProperty<Locale> locale = new SimpleObjectProperty<>(Locale.getDefault());

    public interface Callbacks {
        String resolveI18n(String key, String fallback);
    }

    public DefaultProgramPluginContext(String pluginId, CapabilityRegistry registry,
                                       CapabilityBus capabilityBus, PluginStorage storage,
                                       SecureStorage secureStorage,
                                       ProjectIntegration projectIntegration,
                                       HostEvents hostEvents,
                                       ProgramSessionIntegration sessionIntegration,
                                       PluginAccessPolicy accessPolicy,
                                       AccountSessionService accountSession,
                                       Callbacks callbacks) {
        this.pluginId = pluginId;
        this.registry = registry;
        this.capabilityBus = capabilityBus;
        this.storage = storage;
        this.secureStorage = secureStorage == null ? SecureStorage.unavailable() : secureStorage;
        this.projectIntegration = projectIntegration == null
                ? ProjectIntegration.unavailable() : projectIntegration;
        this.hostEvents = hostEvents == null ? HostEvents.unavailable() : hostEvents;
        this.sessionIntegration = sessionIntegration == null
                ? ProgramSessionIntegration.unavailable() : sessionIntegration;
        this.accessPolicy = accessPolicy == null ? PluginAccessPolicy.allowAll() : accessPolicy;
        this.accountSession = accountSession == null ? AccountSessionService.unavailable() : accountSession;
        this.callbacks = callbacks;
    }

    public DefaultProgramPluginContext(String pluginId, CapabilityRegistry registry,
                                       CapabilityBus capabilityBus, PluginStorage storage,
                                       Callbacks callbacks) {
        this(pluginId, registry, capabilityBus, storage,
                SecureStorage.unavailable(), ProjectIntegration.unavailable(),
                HostEvents.unavailable(), ProgramSessionIntegration.unavailable(),
                PluginAccessPolicy.allowAll(), AccountSessionService.unavailable(), callbacks);
    }

    @Override public String themeName() { return themeName.get(); }

    @Override public ReadOnlyStringProperty themeNameProperty() { return themeName; }

    void setThemeName(String name) { themeName.set(name); }

    @Override public Locale locale() { return locale.get(); }

    @Override public ReadOnlyObjectProperty<Locale> localeProperty() { return locale; }

    void setLocale(Locale loc) { locale.set(loc); }

    @Override public CapabilityRegistry capabilityRegistry() { return registry; }

    @Override public CapabilityBus capabilityBus() { return capabilityBus; }

    @Override public PluginStorage storage() { return storage; }

    @Override public SecureStorage secureStorage() { return secureStorage; }

    @Override public ProjectIntegration projectIntegration() { return projectIntegration; }

    @Override public HostEvents hostEvents() { return hostEvents; }

    @Override public ProgramSessionIntegration sessionIntegration() { return sessionIntegration; }

    @Override public AccountSessionService accountSession() { return accountSession; }

    @Override public PluginAccessPolicy accessPolicy() { return accessPolicy; }

    @Override public Optional<SshSessionContext> sshSession() { return Optional.empty(); }

    @Override public void openTab(String title, javafx.scene.Node content) {}

    @Override public void closeTab() {}

    @Override public void updateTabTitle(String title) {}

    @Override
    public String resolveI18n(String key, String fallback) {
        return callbacks == null ? fallback : callbacks.resolveI18n(key, fallback);
    }

    @Override public void showNotification(String message, NotificationLevel level) {}

    @Override public void debug(String message) { hostLog.debug("[{}] {}", pluginId, message); }

    @Override public void info(String message) { hostLog.info("[{}] {}", pluginId, message); }

    @Override public void warn(String message) { hostLog.warn("[{}] {}", pluginId, message); }

    @Override public void error(String message) { hostLog.error("[{}] {}", pluginId, message); }

    @Override public void error(String message, Throwable t) { hostLog.error("[{}] {}", pluginId, message, t); }

    void dispose() {
        closeIfNeeded(projectIntegration);
        closeIfNeeded(hostEvents);
        closeIfNeeded(sessionIntegration);
    }

    private void closeIfNeeded(Object value) {
        if (!(value instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception error) {
            hostLog.warn("[{}] Failed to release plugin context resource", pluginId, error);
        }
    }
}
