package com.jlshell.app;

import com.jlshell.core.support.PerformanceTrace;
import java.util.concurrent.atomic.AtomicBoolean;

/** One launch, spanning launcher, Swing toolkit and workbench initialization. */
final class StartupTiming {
    private static final PerformanceTrace TRACE = PerformanceTrace.start("startup", "desktop");
    private static final AtomicBoolean SPLASH = new AtomicBoolean();
    static PerformanceTrace trace() { return TRACE; }
    static boolean beginSplash() { return SPLASH.compareAndSet(false, true); }

    private StartupTiming() {}
}
