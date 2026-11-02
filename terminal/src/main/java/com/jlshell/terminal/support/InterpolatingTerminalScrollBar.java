package com.jlshell.terminal.support;

import javax.swing.JScrollBar;

/** Keeps native thumb dragging, while presenting the terminal through its pixel interpolator. */
final class InterpolatingTerminalScrollBar extends JScrollBar {
    private final RefreshableTerminalPanel terminal;

    InterpolatingTerminalScrollBar(RefreshableTerminalPanel terminal) { this.terminal = terminal; }

    @Override public void setValue(int value) {
        // BasicScrollBarUI supplies absolute thumb targets while adjusting. Programmatic jumps
        // (search, keyboard and clear) continue to update the terminal's model immediately.
        if (terminal != null && getValueIsAdjusting() && terminal.scrollBarTo(value)) return;
        super.setValue(value);
    }
}
