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

    void showNotification(String message, NotificationLevel level);
}
