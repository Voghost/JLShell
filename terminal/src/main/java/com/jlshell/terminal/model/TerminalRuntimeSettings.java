package com.jlshell.terminal.model;

/**
 * Runtime settings that affect terminal memory and buffering behavior.
 */
public record TerminalRuntimeSettings(int scrollbackLines) {

    public static final int DEFAULT_SCROLLBACK_LINES = 5_000;
    public static final int MIN_SCROLLBACK_LINES = 500;
    public static final int MAX_SCROLLBACK_LINES = 50_000;

    public TerminalRuntimeSettings {
        scrollbackLines = clampScrollback(scrollbackLines);
    }

    public static TerminalRuntimeSettings defaults() {
        return new TerminalRuntimeSettings(DEFAULT_SCROLLBACK_LINES);
    }

    public static int clampScrollback(int value) {
        return Math.max(MIN_SCROLLBACK_LINES, Math.min(MAX_SCROLLBACK_LINES, value));
    }
}
