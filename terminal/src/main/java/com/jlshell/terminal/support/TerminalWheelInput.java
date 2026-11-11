package com.jlshell.terminal.support;

import java.awt.event.MouseWheelEvent;

/** Converts platform wheel units into terminal rows without discarding subpixel motion. */
final class TerminalWheelInput {
    record Motion(double rows, boolean precise, boolean direct) {}

    private final boolean mac;
    private long lastPreciseNanos;

    TerminalWheelInput(boolean mac) { this.mac = mac; }

    Motion motion(MouseWheelEvent event, int cellHeight, int viewportRows, long now) {
        double rotation = event.getPreciseWheelRotation();
        if (!Double.isFinite(rotation) || rotation == 0) return new Motion(0, true, false);
        int type = event.getScrollType();
        if (type == 3) {
            // JBR TouchScrollUtil: rotation * amount is already a pixel delta.
            return new Motion(rotation * event.getScrollAmount() / Math.max(1, cellHeight), true, true);
        }
        if (mac && type == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            // OpenJDK AWTView scales Cocoa precise deltas by 0.1. Like IDEA JBScrollBar,
            // restore logical pixels here, independently of font height and scrollAmount.
            // Integer rotations can also belong to a trackpad gesture or native momentum.
            return new Motion(rotation * 10 / Math.max(1, cellHeight), true, true);
        }
        boolean fractional = Math.abs(rotation - Math.rint(rotation)) > .0001;
        if (fractional) lastPreciseNanos = now;
        boolean precise = fractional || (lastPreciseNanos != 0 && now - lastPreciseNanos >= 0
                && now - lastPreciseNanos < 150_000_000L);
        int increment = type == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? Math.max(1, viewportRows) : Math.max(1, event.getScrollAmount());
        return new Motion(rotation * increment, precise, false);
    }
}
