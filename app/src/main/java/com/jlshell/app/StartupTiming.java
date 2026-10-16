package com.jlshell.app;

import com.jlshell.core.support.PerformanceTrace;
import java.util.concurrent.atomic.AtomicBoolean;

/** One launch, spanning launcher, JavaFX initialization and the Swing workbench. */
final class StartupTiming {
    private static final PerformanceTrace TRACE = PerformanceTrace.start("startup", "desktop");
    private static final AtomicBoolean SPLASH = new AtomicBoolean();
    private static PerformanceTrace.Span toolkit;

    static PerformanceTrace trace() { return TRACE; }
    static boolean beginSplash() { return SPLASH.compareAndSet(false, true); }
    static void beforeJavaFx() { toolkit = TRACE.step("javafx-toolkit-bootstrap"); }
    static void javaFxInitialized() {
        if (toolkit != null) toolkit.close();
        TRACE.mark("javafx-init-enter");
    }

    private StartupTiming() {}
}
