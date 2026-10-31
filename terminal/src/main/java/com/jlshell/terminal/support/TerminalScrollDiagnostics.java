package com.jlshell.terminal.support;

import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.util.Arrays;

/** Opt-in EDT-only gesture metrics. Never records terminal text or connection details. */
final class TerminalScrollDiagnostics {
    private final Samples eventIntervals = new Samples();
    private final Samples frameIntervals = new Samples();
    private final Samples paintTimes = new Samples();
    private final Samples inputToPaint = new Samples();
    private final Timer idle = new Timer(500, event -> flush());
    private long lastInput, lastPaint, pendingInput;
    private int events, frames, historyAdjustments, resets;
    private double inputPixels;

    TerminalScrollDiagnostics() { idle.setRepeats(false); }

    void input(long now, double pixels) {
        if (lastInput != 0) eventIntervals.add(now - lastInput);
        lastInput = now;
        if (pendingInput == 0) pendingInput = now;
        events++;
        inputPixels += Math.abs(pixels);
        idle.restart();
    }

    void painted(long started, long finished) {
        if (events == 0) return;
        if (lastPaint != 0) frameIntervals.add(started - lastPaint);
        lastPaint = started;
        paintTimes.add(finished - started);
        if (pendingInput != 0) {
            inputToPaint.add(started - pendingInput);
            pendingInput = 0;
        }
        frames++;
    }

    void historyAdjusted() { if (events != 0) historyAdjustments++; }
    void externalReset() { if (events != 0) resets++; }
    void close() { idle.stop(); flush(); }

    private void flush() {
        if (events == 0) return;
        LoggerFactory.getLogger(TerminalScrollDiagnostics.class).info(
                "TERMINAL_SCROLL events={} inputPixels={} paints={} eventGapP95Ms={} paintGapP95Ms={} "
                        + "paintP95Ms={} inputToPaintStartP95Ms={} historyAdjustments={} externalResets={}",
                events, Math.round(inputPixels * 100) / 100.0, frames, eventIntervals.p95Millis(),
                frameIntervals.p95Millis(), paintTimes.p95Millis(), inputToPaint.p95Millis(), historyAdjustments, resets);
        events = frames = historyAdjustments = resets = 0;
        inputPixels = 0;
        lastInput = lastPaint = pendingInput = 0;
        eventIntervals.clear(); frameIntervals.clear(); paintTimes.clear(); inputToPaint.clear();
    }

    private static final class Samples {
        // Bounded per gesture, even if a user scrolls continuously for minutes.
        private final long[] values = new long[512];
        private int size, next;
        void add(long nanos) { values[next++ % values.length] = Math.max(0, nanos); size = Math.min(size + 1, values.length); }
        double p95Millis() {
            if (size == 0) return 0;
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return Math.round(sorted[(int)Math.ceil(size * .95) - 1] / 1000.0) / 1000.0;
        }
        void clear() { size = next = 0; }
    }
}
