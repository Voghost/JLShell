package com.jlshell.plugin.api;

import java.util.Locale;
import java.util.Optional;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;

/**
 * Runtime context passed to a plugin on {@link JlShellPlugin#activate(PluginContext)}.
 */
public interface PluginContext {

    /** Current theme name — "dark" or "light". */
    String themeName();

    /** Observable theme name — plugins can listen for changes. */
    ReadOnlyStringProperty themeNameProperty();

    /** Current locale. */
    Locale locale();

    /** Observable locale — plugins can listen for changes. */
    ReadOnlyObjectProperty<Locale> localeProperty();

    /** SSH session capabilities, present only when connected to an SSH host. */
    Optional<SshSessionContext> sshSession();

    /** Open a new workspace tab with the given title and content node. */
    void openTab(String title, javafx.scene.Node content);

    /** Close the tab that was opened by this plugin, if any. */
    void closeTab();

    /** Update the title of the tab opened by this plugin. */
    void updateTabTitle(String title);

    /** Resolve an i18n key from the host application's resource bundle. Returns fallback if key is missing. */
    String resolveI18n(String key, String fallback);

    void showNotification(String message, NotificationLevel level);

    /** Log a message at DEBUG level. Tagged with the plugin's id for filtering. */
    default void debug(String message) {}

    /** Log a message at INFO level. */
    default void info(String message) {}

    /** Log a message at WARN level. */
    default void warn(String message) {}

    /** Log a message at ERROR level. */
    default void error(String message) {}

    /** Log a message at ERROR level with an exception. */
    default void error(String message, Throwable t) {}
}
