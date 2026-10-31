package com.jlshell.terminal.model;

import com.google.gson.Gson;
import java.util.List;

/** Ordered display-only rules; earlier rules take precedence. */
public record TerminalHighlightConfig(boolean enabled, List<Rule> rules) {
    public static final String SETTINGS_KEY = "terminal.highlight.rules";
    public static final int MAX_RULES = 32;
    public record Rule(String name, boolean enabled, boolean regex, boolean ignoreCase,
                       String expression, String color) {
        @Override public String toString() { return name; }
    }
    public TerminalHighlightConfig {
        rules = List.copyOf(rules);
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Too many highlight rules");
    }
    private static final TerminalHighlightConfig DEFAULTS = createDefaults();
    public static TerminalHighlightConfig defaults() { return DEFAULTS; }
    private static TerminalHighlightConfig createDefaults() {
        return new TerminalHighlightConfig(true, List.of(
            level("ERROR / FATAL", "ERROR|FATAL|SEVERE", "#F87171"),
            level("WARN", "WARN|WARNING", "#FBBF24"),
            level("INFO", "INFO", "#38BDF8"),
            level("DEBUG", "DEBUG", "#A78BFA"),
            level("TRACE", "TRACE", "#94A3B8"),
            level("SUCCESS", "SUCCESS|SUCCEEDED", "#4ADE80")
        ));
    }
    private static Rule level(String name, String words, String color) {
        return new Rule(name, true, true, true, "\\b(?:" + words + ")\\b", color);
    }
    public String serialize() { return new Gson().toJson(this); }
    public static TerminalHighlightConfig parse(String raw) {
        if (raw == null || raw.isBlank()) return defaults();
        try {
            TerminalHighlightConfig config = new Gson().fromJson(raw, TerminalHighlightConfig.class);
            // Validate before using persisted settings; corrupt values must never prevent startup.
            new com.jlshell.terminal.support.TerminalLogHighlighter(config);
            return config;
        } catch (RuntimeException ex) { return defaults(); }
    }
}
