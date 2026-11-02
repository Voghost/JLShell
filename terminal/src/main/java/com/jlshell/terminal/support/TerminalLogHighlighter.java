package com.jlshell.terminal.support;

import com.jlshell.terminal.model.TerminalHighlightConfig;
import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded matching and a small per-terminal cache, independent of the terminal buffer. */
public final class TerminalLogHighlighter {
    public record Span(int start, int end, Color color) {}
    private record Compiled(Pattern pattern, Color color) {}
    private static final int MAX_TEXT = 8192;
    private final boolean enabled;
    private final List<Compiled> rules = new ArrayList<>();
    private final Map<String, List<Span>> cache = new LinkedHashMap<>(64, .75f, true);
    private int cachedWeight;
    public TerminalLogHighlighter(TerminalHighlightConfig config) {
        enabled = config.enabled();
        for (var rule : config.rules()) {
            if (rule.name() == null || rule.expression() == null || rule.expression().isEmpty()
                    || rule.expression().length() > 256 || rule.color() == null
                    || !rule.color().matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("Invalid highlight rule");
            Pattern pattern = Pattern.compile(rule.regex() ? rule.expression() : Pattern.quote(rule.expression()),
                    rule.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            if (rule.enabled()) rules.add(new Compiled(pattern, Color.decode(rule.color())));
        }
    }
    public boolean enabled() { return enabled; }
    public List<Span> match(String text) {
        if (!enabled || text.isEmpty()) return List.of();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        List<Span> cached = cache.get(text);
        if (cached != null) return cached;
        Color[] cells = new Color[text.length()];
        Budget budget = new Budget();
        for (var rule : rules) {
            try {
                var matcher = rule.pattern.matcher(new LimitedText(text, 0, text.length(), budget));
                while (matcher.find()) {
                    if (matcher.start() == matcher.end()) continue;
                    for (int i = matcher.start(); i < matcher.end(); i++)
                        if (cells[i] == null) cells[i] = rule.color;
                }
            } catch (BudgetExceeded | StackOverflowError ex) {
                // User regex must not stall or exhaust the Swing rendering thread.
                break;
            }
        }
        List<Span> result = new ArrayList<>();
        for (int i = 0; i < cells.length;) {
            int start = i; Color color = cells[i++];
            while (i < cells.length && java.util.Objects.equals(cells[i], color)) i++;
            if (color != null) result.add(new Span(start, i, color));
        }
        List<Span> immutable = List.copyOf(result);
        cache.put(text, immutable);
        cachedWeight += text.length() + immutable.size() * 8;
        var entries = cache.entrySet().iterator();
        while (cache.size() > 128 || cachedWeight > 65_536) {
            var oldest = entries.next();
            cachedWeight -= oldest.getKey().length() + oldest.getValue().size() * 8;
            entries.remove();
        }
        return immutable;
    }
    private static final class Budget { int remaining = 100_000; }
    private static final class BudgetExceeded extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
    private record LimitedText(String text, int start, int end, Budget budget) implements CharSequence {
        @Override public int length() { return end - start; }
        @Override public char charAt(int index) {
            if (--budget.remaining < 0) throw new BudgetExceeded();
            if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
            return text.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            if (from < 0 || to < from || to > length()) throw new IndexOutOfBoundsException();
            return new LimitedText(text, start + from, start + to, budget);
        }
        @Override public String toString() { return text.substring(start, end); }
    }
}
