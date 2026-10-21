package com.jlshell.core.support;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceTraceTest {
    private final AtomicLong nanos = new AtomicLong();
    private final List<PerformanceTrace.Event> events = new CopyOnWriteArrayList<>();

    private PerformanceTrace trace() {
        return new PerformanceTrace("test", "plugin", "-", nanos::get, events::add);
    }

    @Test void separatesStageDurationFromTotalElapsedAndLinksChildren() {
        var trace = trace();
        nanos.set(20_000_000);
        var child = trace.child("provider", "example.Plugin");
        var stage = child.step("constructor");
        nanos.set(620_000_000);
        stage.close();
        stage.close();
        child.finish("success");
        trace.finish("success");
        var ends = events.stream().filter(event -> event.event().equals("end")).toList();
        assertEquals(3, ends.size());
        assertEquals(600, ends.get(0).durationMs());
        assertTrue(ends.get(0).slow());
        assertEquals(ends.get(2).traceId(), ends.get(0).parentId());
        assertEquals(620, ends.get(2).elapsedMs());
    }

    @Test void recordsCheckedFailureWithoutExceptionMessageAndRethrowsOriginal() {
        var trace = trace();
        var failure = new IOException("sensitive payload");
        var thrown = assertThrows(IOException.class, () -> trace.call("package", () -> { throw failure; }));
        assertSame(failure, thrown);
        trace.failed(failure);
        trace.finish("success");
        assertEquals(2, events.stream().filter(event -> event.outcome().equals("failed")).count());
        assertTrue(events.stream().noneMatch(event -> event.toString().contains("sensitive payload")));
        assertEquals(IOException.class.getName(), events.getLast().errorType());
    }

    @Test void queueSpanCanCrossThreadsAndCompletionIsIdempotent() throws Exception {
        var trace = trace();
        var queue = trace.step("edt-queue");
        nanos.set(45_000_000);
        Thread worker = new Thread(() -> { queue.close(); trace.finish("first-paint"); });
        worker.start(); worker.join(5_000);
        assertFalse(worker.isAlive());
        trace.finish("closed");
        queue.close();
        assertEquals(2, events.stream().filter(event -> event.event().equals("end")).count());
        assertEquals(45, events.get(2).durationMs());
        assertEquals("first-paint", events.getLast().outcome());
    }

    @Test void sanitizesPluginControlledIdentifiersToOneBoundedLogToken() {
        var trace = new PerformanceTrace("scan", "plugin\r\n outcome=success " + "x".repeat(300),
                "-", nanos::get, events::add);
        trace.mark("stage\nforged=yes");
        assertTrue(events.getFirst().subject().length() <= 160);
        assertFalse(events.getFirst().subject().contains("\n"));
        assertFalse(events.getFirst().subject().contains("="));
        assertEquals("stage_forged_yes", events.getLast().stage());
    }
}
