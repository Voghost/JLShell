package com.jlshell.sysmon;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.jlshell.plugin.api.JlShellPlugin;
import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.PluginView;
import com.jlshell.plugin.api.rpc.Capability;

import javafx.scene.Node;

/**
 * System Monitor plugin — real-time CPU, memory, network, and disk monitoring
 * with historical trend charts. Works locally (OSHI) or remotely (SSH).
 */
public class SystemMonitorPlugin implements JlShellPlugin, PluginView {

    private PluginContext activeContext;
    private MonitorDashboard dashboard;
    private volatile SwingMonitorDashboard swingDashboard;

    // ── JlShellPlugin ────────────────────────────────────────────────────

    @Override public String id()              { return "com.jlshell.sysmon"; }
    @Override public String displayName()     { return "System Monitor"; }
    @Override public String version()         { return "0.1.0"; }
    @Override public String author()          { return "JLShell"; }
    @Override public String minHostVersionInclusive() { return "0.1.0"; }
    @Override public String maxHostVersionInclusive() { return "0.1.999"; }
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
        if (!openNativeView(context) && view != null)
            context.openTab(displayName(context.locale()), view.createView(context));
        // 注册 getMetrics 能力 — 其他插件或外部 API 可通过 CapabilityBus 调用
        try {
            context.capabilityRegistry().register(
                Capability.builder("getMetrics")
                    .description("Get current system metrics (CPU, memory, network, disk) from the remote server.")
                    .requiresSession(true)
                    .handler((args, capCtx) -> {
                        com.jlshell.plugin.api.SshSessionContext ssh = capCtx.sshSession().orElseThrow();
                        com.jlshell.sysmon.RemoteMetricsCollector collector = new com.jlshell.sysmon.RemoteMetricsCollector(ssh);
                        return collector.collect().thenApply(metrics -> {
                            com.google.gson.JsonObject result = new com.google.gson.JsonObject();
                            result.addProperty("cpuUsage", metrics.cpuUsage());
                            result.addProperty("cpuCores", metrics.cpuCores());
                            result.addProperty("loadAvg1m", metrics.cpuLoadAvg1m());
                            result.addProperty("memTotalBytes", metrics.memTotal());
                            result.addProperty("memUsedBytes", metrics.memUsed());
                            result.addProperty("netRecvBytes", metrics.netBytesRecv());
                            result.addProperty("netSentBytes", metrics.netBytesSent());
                            com.google.gson.JsonArray disksArr = new com.google.gson.JsonArray();
                            for (com.jlshell.sysmon.SystemMetrics.DiskInfo d : metrics.disks()) {
                                com.google.gson.JsonObject diskObj = new com.google.gson.JsonObject();
                                diskObj.addProperty("mount", d.mount());
                                diskObj.addProperty("totalBytes", d.total());
                                diskObj.addProperty("usedBytes", d.used());
                                disksArr.add(diskObj);
                            }
                            result.add("disks", disksArr);
                            return (com.google.gson.JsonElement) result;
                        });
                    })
                    .build());
        } catch (Throwable t) {
            // 旧 host 无 capabilityRegistry — register 静默失败，不影响插件其余功能
        }
    }

    // Capability lookup keeps this plugin independently buildable against the released SDK,
    // and allows older hosts without the new method to use the existing FX fallback.
    private boolean openNativeView(PluginContext context) {
        try {
            if (!Boolean.TRUE.equals(PluginContext.class.getMethod("supportsSwingViews").invoke(context))) return false;
            java.util.function.Supplier<javax.swing.JComponent> factory = () -> {
                swingDashboard = new SwingMonitorDashboard(context); return swingDashboard;
            };
            PluginContext.class.getMethod("openSwingTab", String.class, java.util.function.Supplier.class)
                    .invoke(context, displayName(context.locale()), factory);
            return true;
        } catch (NoSuchMethodException olderSdk) {
            return false;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot open native system monitor", failure);
        }
    }

    @Override
    public void deactivate() {
        if (swingDashboard != null) { swingDashboard.close(); swingDashboard = null; }
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
        if (swingDashboard != null) swingDashboard.startPolling();
        if (dashboard != null) dashboard.startPolling();
    }

    @Override
    public void onTabDeselected() {
        if (swingDashboard != null) swingDashboard.stopPolling();
        if (dashboard != null) dashboard.stopPolling();
    }

    @Override
    public void onThemeChanged(String themeName) {
        if (swingDashboard != null) swingDashboard.applyTheme(themeName);
        if (dashboard != null) dashboard.applyTheme(themeName);
    }

    @Override
    public void onLocaleChanged(Locale locale) {
        if (swingDashboard != null) swingDashboard.applyLocale(locale);
        if (activeContext != null) {
            activeContext.updateTabTitle(displayName(locale));
        }
        if (dashboard != null) dashboard.applyLocale(locale);
    }

    @Override
    public void onSessionClosed() {
        if (swingDashboard != null) swingDashboard.close();
        if (dashboard != null) dashboard.stopPolling();
    }
}
