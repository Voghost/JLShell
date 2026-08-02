package com.jlshell.plugin.api;

import java.util.Locale;

import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.event.HostEvents;
import com.jlshell.plugin.api.project.ProjectIntegration;
import com.jlshell.plugin.api.security.PluginAccessPolicy;
import com.jlshell.plugin.api.session.ProgramSessionIntegration;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.storage.SecureStorage;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;

public interface ProgramPluginContext {

    String themeName();

    ReadOnlyStringProperty themeNameProperty();

    Locale locale();

    ReadOnlyObjectProperty<Locale> localeProperty();

    CapabilityRegistry capabilityRegistry();

    CapabilityBus capabilityBus();

    PluginStorage storage();

    default SecureStorage secureStorage() { return SecureStorage.unavailable(); }

    default ProjectIntegration projectIntegration() { return ProjectIntegration.unavailable(); }

    default HostEvents hostEvents() { return HostEvents.unavailable(); }

    /**
     * Register a session surface owned by this Program plugin.
     *
     * <p>The host creates a short-lived {@link PluginContext} for each activated SSH session
     * contribution and revokes it when the session, plugin, or application stops.</p>
     */
    default ProgramSessionIntegration sessionIntegration() {
        return ProgramSessionIntegration.unavailable();
    }

    default PluginAccessPolicy accessPolicy() { return PluginAccessPolicy.allowAll(); }

    String resolveI18n(String key, String fallback);

    void showNotification(String message, NotificationLevel level);

    default void debug(String message) {}

    default void info(String message) {}

    default void warn(String message) {}

    default void error(String message) {}

    default void error(String message, Throwable t) {}
}
