package com.jlshell.plugin.api;

import java.util.Locale;

import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilityRegistry;
import com.jlshell.plugin.api.storage.PluginStorage;
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

    String resolveI18n(String key, String fallback);

    void showNotification(String message, NotificationLevel level);

    default void debug(String message) {}

    default void info(String message) {}

    default void warn(String message) {}

    default void error(String message) {}

    default void error(String message, Throwable t) {}
}
