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

    private final VisibleTerminalAccess terminalAccess;
    @Override public java.util.Optional<com.jlshell.plugin.api.host.TerminalAccess> terminalAccess() { return java.util.Optional.of(terminalAccess); }
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
        this.terminalAccess = new VisibleTerminalAccess(sessionId.toString(), widget, this::currentDirectory);
        this.settingsProvider = settingsProvider;
        this.ttyConnector = ttyConnector;
        ttyConnector.eventSink(terminalAccess::publish);
    }

    @Override public String selectedText() {
        var buffer=widget.getTerminalTextBuffer(); buffer.lock();
        try { var selection=widget.getTerminalPanel().getSelection();
            return selection==null ? "" : com.jediterm.terminal.model.SelectionUtil.getSelectionText(selection,buffer);
        } finally { buffer.unlock(); }
    }
    @Override public void textActions(java.util.function.Supplier<java.util.List<RefreshableTerminalPanel.PopupAction>> actions) {
        if(widget.getTerminalPanel() instanceof RefreshableTerminalPanel panel) panel.setExtensionActions(actions);
    }
    @Override
    public SessionId sessionId() {
        return sessionId;
    }

    @Override public String encodingName() { return ttyConnector.encodingName(); }

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
        return widget.requestVisualRefresh();
    }

    @Override
    public CompletableFuture<Void> updateColorScheme(TerminalColorScheme colorScheme) {
        Objects.requireNonNull(colorScheme, "colorScheme must not be null");
        settingsProvider.updateColorScheme(colorScheme);
        return widget.requestVisualRefresh();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        terminalAccess.close();
        ttyConnector.close();
        return SwingExecutors.runOnEdtAsync(() -> {
            widget.stop();
            widget.close();
        }).thenCompose(unused -> ttyConnector.closeFuture());
    }

    @Override
    public void sendStringToTerminal(String text) {
        if (text == null || text.isEmpty()) return;
        SwingExecutors.runOnEdtAsync(() -> {
            com.jediterm.terminal.TerminalStarter starter = widget.getTerminalStarter();
            if (starter != null) {
                starter.sendString(text, true);
            }
        });
    }

    @Override
    public java.awt.Point getCursorLocationInComponent() {
        if (widget.getTerminalPanel() instanceof RefreshableTerminalPanel rtp) {
            return javax.swing.SwingUtilities.convertPoint(
                    rtp,
                    rtp.getCursorLocationInComponent(),
                    widget.getComponent()
            );
        }
        return new java.awt.Point(0, 0);
    }
}
