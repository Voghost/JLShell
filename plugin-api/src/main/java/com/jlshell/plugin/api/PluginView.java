package com.jlshell.plugin.api;

import java.util.function.Supplier;
import javax.swing.JComponent;
import java.util.Locale;

/**
 * Implemented by plugins that want to open a workspace tab.
 */
public interface PluginView {

    Supplier<JComponent> createView(PluginContext context);

    default void onTabSelected() {}

    default void onTabDeselected() {}

    default void onThemeChanged(String themeName) {}

    default void onLocaleChanged(Locale locale) {}

    default void onSessionClosed() {}
}
