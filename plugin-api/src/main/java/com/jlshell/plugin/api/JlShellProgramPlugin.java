package com.jlshell.plugin.api;

import java.util.Locale;

public interface JlShellProgramPlugin {

    String id();

    String displayName();

    default String displayName(Locale locale) { return displayName(); }

    String version();

    default String author() { return "system"; }

    default String minHostVersionInclusive() { return ""; }

    default String maxHostVersionInclusive() { return ""; }

    String description();

    default String description(Locale locale) { return description(); }

    void activate(ProgramPluginContext context);

    void deactivate();

    default javafx.scene.Node settingsView(ProgramPluginContext context) {
        return null;
    }
}
