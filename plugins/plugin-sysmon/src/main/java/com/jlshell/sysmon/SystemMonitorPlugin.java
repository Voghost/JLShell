package com.jlshell.sysmon;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;

import javafx.scene.Node;

/**
 * System Monitor plugin — real-time CPU, memory, network, and disk monitoring
 * with historical trend charts. Works locally (OSHI) or remotely (SSH).
 */
public class SystemMonitorPlugin implements JlShellPlugin, PluginView {

    private PluginContext activeContext;
    private MonitorDashboard dashboard;

    // ── JlShellPlugin ────────────────────────────────────────────────────

    @Override public String id()              { return "com.jlshell.sysmon"; }
    @Override public String displayName()     { return "System Monitor"; }
    @Override public String version()         { return "0.1.0"; }
    @Override public String description()     { return "Real-time CPU, memory, network, and disk monitoring with trend charts."; }
    @Override public boolean requiresSshSession() { return false; }

    @Override
    public String displayName(Locale locale) {
        return getBundle(locale).getString("plugin.name");
    }

    @Override
    public String description(Locale locale) {
        return getBundle(locale).getString("plugin.description");
    }

    private ResourceBundle getBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle("com.jlshell.sysmon.messages", locale);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle("com.jlshell.sysmon.messages", Locale.ENGLISH);
        }
    }

    @Override
    public void activate(PluginContext context) {
        this.activeContext = context;
        PluginView view = view();
        if (view != null) {
            context.openTab(displayName(context.locale()), view.createView(context));
        }
    }

    @Override
    public void deactivate() {
        if (dashboard != null) {
            dashboard.stopPolling();
            dashboard = null;
        }
        if (activeContext != null) {
            activeContext.closeTab();
            activeContext = null;
        }
    }

    @Override public PluginView view() { return this; }

    // ── PluginView ──────────────────────────────────────────────────────

    @Override
    public Node createView(PluginContext context) {
        dashboard = new MonitorDashboard();
        return dashboard.createView(context);
    }

    @Override
    public void onTabSelected() {
        if (dashboard != null) dashboard.startPolling();
    }

    @Override
    public void onTabDeselected() {
        if (dashboard != null) dashboard.stopPolling();
    }

    @Override
    public void onThemeChanged(String themeName) {
        if (dashboard != null) dashboard.applyTheme(themeName);
    }

    @Override
    public void onLocaleChanged(Locale locale) {
        if (activeContext != null) {
            activeContext.updateTabTitle(displayName(locale));
        }
        if (dashboard != null) dashboard.applyLocale(locale);
    }
}