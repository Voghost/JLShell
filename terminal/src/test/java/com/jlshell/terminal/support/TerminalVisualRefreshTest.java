package com.jlshell.terminal.support;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TerminalVisualRefreshTest {

    @Test
    void colorRefreshDoesNotResizeButFontAndLineSpacingDo() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 13, false, 1),
                    TerminalColorScheme.dark());
            StyleState style = new StyleState();
            CountingPanel panel = new CountingPanel(settings, new TerminalTextBuffer(80, 24, style), style);
            panel.init(new javax.swing.JScrollBar());
            try {
                panel.refreshVisuals();
                int baseline = panel.resizes;
                settings.updateColorScheme(TerminalColorScheme.dark());
                panel.refreshVisuals();
                assertEquals(baseline, panel.resizes);
                settings.updateFontProfile(new FontProfile("Monospaced", 15, false, 1));
                panel.refreshVisuals();
                assertEquals(baseline + 1, panel.resizes);
                settings.updateFontProfile(new FontProfile("Monospaced", 15, false, 1.2));
                panel.refreshVisuals();
                assertEquals(baseline + 2, panel.resizes);
            } finally {
                panel.dispose();
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test
    void combinesQueuedRefreshesAndCompletesAllCallers() throws Exception {
        var widget = SwingExecutors.supplyOnEdtAsync(() -> JlshellJediTermWidget.create(80, 24,
                new JlshellSettingsProvider(new FontProfile("Monospaced", 13, false, 1), TerminalColorScheme.dark()),
                key -> key)).get(10, TimeUnit.SECONDS);
        try {
            // 在同一 EDT 回调中请求两次：刷新将在下一个调度周期执行。
            CompletableFuture<Void> completion = SwingExecutors.supplyOnEdtAsync(() -> {
                var first = widget.requestVisualRefresh();
                var second = widget.requestVisualRefresh();
                assertSame(first, second);
                return first;
            }).get(10, TimeUnit.SECONDS);
            completion.get(10, TimeUnit.SECONDS);
            widget.requestVisualRefresh().get(10, TimeUnit.SECONDS);
        } finally {
            SwingExecutors.runOnEdtAsync(widget::close).get(10, TimeUnit.SECONDS);
        }
    }

    private static final class CountingPanel extends RefreshableTerminalPanel {
        private int resizes;

        private CountingPanel(JlshellSettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
            super(settings, buffer, style, settings, key -> key);
        }

        @Override
        protected void reinitFontAndResize() {
            resizes++;
            super.reinitFontAndResize();
        }
    }
}
