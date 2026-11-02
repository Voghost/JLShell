package com.jlshell.core.support;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Bounded user-facing activity notifications, explicitly published by business flows. */
public final class RecentApplicationLogs {
    public record Entry(Instant time, String level, String source, String message) {}
    private static final RecentApplicationLogs SHARED = new RecentApplicationLogs();
    public static RecentApplicationLogs shared() { return SHARED; }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public void append(Instant time, String level, String source, String message) {
        if (message == null || message.isBlank()) return;
        Entry entry = new Entry(time, level, source, message.length() > 8192 ? message.substring(0, 8192) + "…" : message);
        synchronized (entries) { entries.addFirst(entry); while (entries.size() > 200) entries.removeLast(); }
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Never break the logging caller. */ }
        }
    }
    public List<Entry> snapshot() { synchronized (entries) { return List.copyOf(entries); } }
    public AutoCloseable subscribe(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
}
