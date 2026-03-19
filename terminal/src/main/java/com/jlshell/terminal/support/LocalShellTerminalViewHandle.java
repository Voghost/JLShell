package com.jlshell.terminal.support;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.TerminalViewHandle;

/**
 * 本地 Shell 终端句柄。
 */
public class LocalShellTerminalViewHandle implements TerminalViewHandle {

    private final SessionId sessionId;
    private final String title;
    private final JlshellJediTermWidget widget;
    private final JlshellSettingsProvider settingsProvider;
    private final LocalShellTtyConnector ttyConnector;

    public LocalShellTerminalViewHandle(
            SessionId sessionId,
            String title,
            JlshellJediTermWidget widget,
            JlshellSettingsProvider settingsProvider,
            LocalShellTtyConnector ttyConnector
    ) {
        this.sessionId = sessionId;
        this.title = title;
        this.widget = widget;
        this.settingsProvider = settingsProvider;
        this.ttyConnector = ttyConnector;
    }

    @Override
    public SessionId sessionId() {
        return sessionId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public JComponent component() {
        return widget.getComponent();
    }

    @Override
    public void requestFocus() {
        SwingExecutors.runOnEdtAsync(() -> {
            widget.requestFocus();
            widget.requestFocusInWindow();
            widget.getTerminalPanel().requestFocus();
            widget.getTerminalPanel().requestFocusInWindow();
        });
    }

    @Override
    public CompletableFuture<Void> updateFontProfile(FontProfile fontProfile) {
        Objects.requireNonNull(fontProfile, "fontProfile must not be null");
        settingsProvider.updateFontProfile(fontProfile);
        return SwingExecutors.runOnEdtAsync(widget::refreshVisuals);
    }

    @Override
    public CompletableFuture<Void> updateColorScheme(TerminalColorScheme colorScheme) {
        Objects.requireNonNull(colorScheme, "colorScheme must not be null");
        settingsProvider.updateColorScheme(colorScheme);
        return SwingExecutors.runOnEdtAsync(widget::refreshVisuals);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        ttyConnector.close();
        return SwingExecutors.runOnEdtAsync(() -> {
            widget.stop();
            widget.close();
        }).thenCompose(unused -> ttyConnector.closeFuture());
    }
}
