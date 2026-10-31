package com.jlshell.terminal.support;

import java.util.ArrayDeque;

/** Continuous viewport offset in grid lines; driven by the EDT, with an injectable clock for tests. */
final class TerminalScrollAnimation {
    static final long DURATION_NANOS = 140_000_000L;
    static final long PRECISE_DELAY_NANOS = 20_000_000L;
    static final long DRAG_DELAY_NANOS = 30_000_000L;
    private static final int MAX_SEGMENTS = 64;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private double position, target, from;
    private long started, previousInput, interpolationDelay;
    private boolean active, animating;

    double position() { return position; }
    boolean active() { return active; }
    boolean animating() { return animating; }
    void directWheel(double delta, int current, int min, int max, long now) {
        if (!active) position = current;
        if (animating) advance(now);
        // Native touch input already follows the finger and supplies momentum events. Do not
        // queue it behind a fixed delay, or resume an older thumb/wheel target after takeover.
        segments.clear(); previousInput = 0;
        position = target = from = clamp(position + delta, min, max);
        active = true; animating = false;
    }

    void wheel(double delta, boolean precise, int current, int min, int max, long now) {
        if (!active) position = target = current;
        if (animating) advance(now);
        boolean reverse = (target - position) * delta < 0;
        double next = clamp((reverse ? position : target) + delta, min, max);
        if (precise) {
            interpolateTo(next, current, min, max, now, PRECISE_DELAY_NANOS);
            return;
        }
        segments.clear(); previousInput = 0;
        active = true;
        target = clamp(Math.rint(next), min, max);
        from = position; started = now; animating = Math.abs(target - position) > .001;
    }

    void dragTo(int value, int current, int min, int max, long now) {
        interpolateTo(value, current, min, max, now, DRAG_DELAY_NANOS);
    }

    private void interpolateTo(double value, int current, int min, int max, long now, long delay) {
        if (!active) position = target = current;
        if (animating) advance(now);
        value = clamp(value, min, max);
        if (active && value == target) return;
        boolean reverse = (value - target) * (target - position) < 0;
        // A short, time-stamped path fills gaps between device events. Unlike repeatedly
        // restarting an ease-out, its latency does not grow with the number of input events.
        if (reverse || interpolationDelay != delay || segments.size() >= MAX_SEGMENTS) segments.clear();
        if (segments.isEmpty()) {
            segments.add(new Segment(position, value, now, now + delay));
        } else {
            var last = segments.getLast();
            segments.add(new Segment(last.to, value, last.end, last.end + Math.max(0, now - previousInput)));
        }
        previousInput = now; interpolationDelay = delay;
        target = value; active = animating = true;
    }
    void advance(long now) {
        if (!animating) return;
        if (!segments.isEmpty()) {
            while (!segments.isEmpty()) {
                var segment = segments.getFirst();
                if (now >= segment.end) {
                    position = segment.to;
                    segments.removeFirst();
                } else {
                    double fraction = Math.max(0, (now - segment.start) / (double)(segment.end - segment.start));
                    position = segment.from + (segment.to - segment.from) * fraction;
                    return;
                }
            }
            position = target; animating = false;
            return;
        }
        double progress = Math.max(0, Math.min(1, (now - started) / (double) DURATION_NANOS));
        position = from + (target - from) * (1 - Math.pow(1 - progress, 3));
        if (progress >= 1) { position = target; animating = false; }
    }
    void freeze() { segments.clear(); target = position; animating = false; previousInput = 0; }
    void shift(int rows, int min, int max) {
        // New output moves history coordinates; it must not restart the gesture or easing clock.
        position = Math.max(min, Math.min(max, position + rows));
        target = Math.max(min, Math.min(max, target + rows));
        from = Math.max(min, Math.min(max, from + rows));
        for (var segment : segments) {
            segment.from = clamp(segment.from + rows, min, max);
            segment.to = clamp(segment.to + rows, min, max);
        }
    }
    void cancel(int current) {
        segments.clear(); previousInput = 0;
        position = target = current; active = animating = false;
    }

    private static double clamp(double value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class Segment {
        private double from, to;
        private final long start, end;
        Segment(double from, double to, long start, long end) {
            this.from = from; this.to = to; this.start = start; this.end = end;
        }
    }
}
