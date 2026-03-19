package com.jlshell.plugin.api;

import java.util.Optional;

/**
 * Runtime context passed to a plugin on {@link JlShellPlugin#activate(PluginContext)}.
 */
public interface PluginContext {

    /** SSH session capabilities, present only when connected to an SSH host. */
    Optional<SshSessionContext> sshSession();

    /** Open a new workspace tab with the given title and content node. */
    void openTab(String title, javafx.scene.Node content);

    /** Close the tab that was opened by this plugin, if any. */
    void closeTab();

    void showNotification(String message, NotificationLevel level);
}
