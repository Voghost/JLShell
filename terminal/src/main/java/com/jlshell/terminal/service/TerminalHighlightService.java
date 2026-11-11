package com.jlshell.terminal.service;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.support.TerminalLogHighlighter;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** Application-scoped persisted config, with weak listeners so closed terminals can be collected. */
public final class TerminalHighlightService {
    private final AppSettingsService settings;
    private volatile TerminalHighlightConfig config;
    private final CopyOnWriteArrayList<WeakReference<Runnable>> listeners = new CopyOnWriteArrayList<>();
    public TerminalHighlightService(AppSettingsService settings) {
        this.settings = settings;
        config = TerminalHighlightConfig.parse(settings.get(TerminalHighlightConfig.SETTINGS_KEY, ""));
    }
    public TerminalHighlightConfig config() { return config; }
    public void update(TerminalHighlightConfig next) {
        new TerminalLogHighlighter(next); // validate before persisting
        settings.set(TerminalHighlightConfig.SETTINGS_KEY, next.serialize());
        config = next;
        for (var ref : listeners) {
            Runnable listener = ref.get();
            if (listener == null) listeners.remove(ref); else listener.run();
        }
    }
    public void listen(Runnable listener) { listeners.add(new WeakReference<>(listener)); }
}
