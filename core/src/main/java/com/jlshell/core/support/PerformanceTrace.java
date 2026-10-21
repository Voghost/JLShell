package com.jlshell.core.support;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Small, cross-thread diagnostic traces. Does not retain contexts, UI nodes or exception payloads. */
public final class PerformanceTrace {
    private static final String RUN_ID = Long.toUnsignedString(ProcessHandle.current().pid(), 36)
            + "-" + Long.toUnsignedString(System.currentTimeMillis(), 36);
    private static final AtomicLong IDS = new AtomicLong();
    private final String id = RUN_ID + "-" + Long.toUnsignedString(IDS.incrementAndGet(), 36);
    private final String parentId;
    private final String operation;
    private final String subject;
    private final LongSupplier clock;
    private final Consumer<Event> sink;
    private final long started;
    private final long slowMillis;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicLong steps = new AtomicLong();

    public static PerformanceTrace start(String operation, String subject) {
        return new PerformanceTrace(operation, subject, "-", System::nanoTime, PerformanceTrace::log);
    }

    PerformanceTrace(String operation, String subject, String parentId, LongSupplier clock, Consumer<Event> sink) {
        this.operation = safe(operation);
        this.subject = safe(subject);
        this.parentId = parentId;
        this.clock = clock;
        this.sink = Boolean.parseBoolean(System.getProperty("jlshell.performance.enabled", "true")) ? sink : event -> {};
        slowMillis = Math.max(1, Long.getLong("jlshell.performance.slowMillis", 500L));
        started = clock.getAsLong();
        emit("total", "start", "pending", 0, started, null);
    }

    public PerformanceTrace child(String operation, String subject) {
        return new PerformanceTrace(operation, subject, id, clock, sink);
    }

    public String id() { return id; }

    public Span step(String stage) { return new Span(safe(stage) + "#" + steps.incrementAndGet()); }

    public void mark(String stage) {
        if (!finished.get()) emit(safe(stage), "mark", "pending", 0, clock.getAsLong(), null);
    }

    public <T, E extends Throwable> T call(String stage, Task<T, E> action) throws E {
        try (var span = step(stage)) {
            try { return action.get(); }
            catch (Throwable error) { span.failed(error); throw error; }
        }
    }

    public void run(String stage, Runnable action) { call(stage, () -> { action.run(); return null; }); }

    public void finish(String outcome) { finish(outcome, null); }

    public void failed(Throwable error) { finish("failed", error); }

    private void finish(String outcome, Throwable error) {
        if (finished.compareAndSet(false, true)) {
            long now = clock.getAsLong();
            emit("total", "end", safe(outcome), millis(now - started), now, error);
        }
    }

    public final class Span implements AutoCloseable {
        private final String stage;
        private final long began = clock.getAsLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile String errorType;

        private Span(String stage) {
            this.stage = stage;
            emit(stage, "start", "pending", 0, began, null);
        }

        public void failed(Throwable error) { errorType = error.getClass().getName(); }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                long now = clock.getAsLong();
                emit(stage, "end", errorType == null ? "success" : "failed",
                        millis(now - began), now, errorType);
            }
        }
    }

    @FunctionalInterface public interface Task<T, E extends Throwable> { T get() throws E; }

    private void emit(String stage, String event, String outcome, long duration, long now, Object error) {
        String errorType = error instanceof Throwable throwable ? throwable.getClass().getName() : String.valueOf(error);
        sink.accept(new Event(id, parentId, operation, subject, stage, event, outcome,
                duration, millis(now - started), event.equals("end") && duration >= slowMillis,
                error == null ? "-" : safe(errorType)));
    }

    private static long millis(long nanos) { return Math.max(0, nanos / 1_000_000L); }

    private static String safe(String text) {
        if (text == null || text.isBlank()) return "-";
        return text.substring(0, Math.min(text.length(), 160)).replaceAll("[\\p{Cntrl}\\s=]+", "_");
    }

    record Event(String traceId, String parentId, String operation, String subject, String stage,
                 String event, String outcome, long durationMs, long elapsedMs, boolean slow, String errorType) {}

    private static void log(Event event) {
        var logger = LoggerFactory.getLogger("com.jlshell.performance");
        String pattern = "Performance traceId={} parentId={} operation={} subject={} stage={} event={} outcome={} durationMs={} elapsedMs={} slow={} errorType={}";
        Object[] args = {event.traceId, event.parentId, event.operation, event.subject, event.stage,
                event.event, event.outcome, event.durationMs, event.elapsedMs, event.slow, event.errorType};
        if (event.slow || event.outcome.equals("failed")) logger.warn(pattern, args);
        else logger.info(pattern, args);
    }
}
