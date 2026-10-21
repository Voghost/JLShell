package com.jlshell.app;

import java.io.PrintStream;
import java.util.function.BiConsumer;

/** Last-resort reporting must remain usable even if the logging implementation cannot load classes. */
final class UncaughtExceptionReporter implements Thread.UncaughtExceptionHandler {
    private final PrintStream fallback;
    private final BiConsumer<Thread, Throwable> logger;

    UncaughtExceptionReporter(PrintStream fallback, BiConsumer<Thread, Throwable> logger) {
        this.fallback = fallback; this.logger = logger;
    }

    @Override public void uncaughtException(Thread thread, Throwable throwable) {
        if (throwable instanceof IllegalArgumentException && throwable.getMessage() != null
                && throwable.getMessage().contains("cannot be <= 0") && thread.getName().startsWith("AWT-EventQueue")) return;
        fallback.println("Uncaught exception in thread [" + thread.getName() + "]");
        throwable.printStackTrace(fallback);
        try {
            logger.accept(thread, throwable);
        } catch (Throwable loggingFailure) {
            fallback.println("Logging unavailable; original exception was printed above. Logging failure:");
            loggingFailure.printStackTrace(fallback);
        }
    }
}
