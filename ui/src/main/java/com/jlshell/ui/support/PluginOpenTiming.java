package com.jlshell.ui.support;

import org.slf4j.LoggerFactory;

/** Per-click elapsed times, including FX/EDT queueing. Never contains connection or credential data. */
public final class PluginOpenTiming {
    public static final String PROPERTY = "jlshell.plugin.openTiming";
    private final String pluginId;
    private final long started = System.nanoTime();

    public PluginOpenTiming(String pluginId) { this.pluginId = pluginId; }

    public void mark(String stage) {
        LoggerFactory.getLogger(PluginOpenTiming.class).info("Plugin {} open stage={} elapsed={} ms",
                pluginId, stage, (System.nanoTime() - started) / 1_000_000);
    }
}
