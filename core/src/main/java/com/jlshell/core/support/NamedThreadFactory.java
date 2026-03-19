package com.jlshell.core.support;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为后台线程统一命名，便于排查日志和线程转储。
 */
public class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger(1);

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = Thread.ofPlatform()
                .name(prefix + "-" + sequence.getAndIncrement())
                .daemon(false)
                .unstarted(runnable);
        thread.setUncaughtExceptionHandler((current, throwable) ->
                System.err.printf("Unhandled exception on thread %s: %s%n", current.getName(), throwable.getMessage()));
        return thread;
    }
}
