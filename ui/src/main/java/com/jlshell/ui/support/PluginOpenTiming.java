package com.jlshell.ui.support;

import com.jlshell.core.support.PerformanceTrace;

/** Per-click elapsed times, including UI dispatch and EDT queueing. Never contains connection or credential data. */
public final class PluginOpenTiming {
    public static final String PROPERTY = "jlshell.plugin.openTiming";
    private final PerformanceTrace trace;
    private final PerformanceTrace.Span dispatchQueue;

    public PluginOpenTiming(String pluginId) {
        trace = PerformanceTrace.start("plugin-open", pluginId);
        dispatchQueue = trace.step("ui-dispatch-queue");
    }

    public PerformanceTrace trace() { return trace; }
    public PerformanceTrace.Span step(String stage) { return trace.step(stage); }
    public void finish(String outcome) { trace.finish(outcome); }
    public void failed(Throwable error) { trace.failed(error); }

    public void mark(String stage) {
        if (stage.equals("edt-dispatch")) dispatchQueue.close();
        trace.mark(stage);
    }

}
