package com.jlshell.plugin.api;

/**
 * Implemented by plugins that want to open a workspace tab.
 */
public interface PluginView {

    javafx.scene.Node createView(PluginContext context);

    default void onTabSelected() {}

    default void onTabDeselected() {}
}
