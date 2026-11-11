package com.jlshell.plugin.api;

import java.util.function.Supplier;
import javax.swing.JComponent;
import java.util.Locale;

import com.jlshell.plugin.api.security.PluginAccessPolicyProvider;

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

    default Supplier<JComponent> settingsView(ProgramPluginContext context) {
        return null;
    }

    /** 返回权限提供者时，宿主仅会接受来自受信任加载源的实现。 */
    default PluginAccessPolicyProvider accessPolicyProvider() {
        return null;
    }
}
