package com.jlshell.terminal.support;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.JComponent;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.TerminalViewHandle;
import javafx.beans.property.StringProperty;

/**
 * JediTerm 终端句柄默认实现。
 */
public class DefaultTerminalViewHandle implements TerminalViewHandle {

    private final SessionId sessionId;
    private final String title;
    private final JlshellJediTermWidget widget;
    private final JlshellSettingsProvider settingsProvider;
    private final ShellTtyConnector ttyConnector;
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    public DefaultTerminalViewHandle(
            SessionId sessionId,
            String title,
            JlshellJediTermWidget widget,
            JlshellSettingsProvider settingsProvider,
            ShellTtyConnector ttyConnector
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
        // 焦点切换通常由 JavaFX 事件触发，如果这里同步等待 EDT，
        // 在 macOS 的 SwingNode 桥接场景下容易形成 UI 线程互相等待。
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
        return closeInternal(true);
    }

    public CompletableFuture<Void> closeAfterDisconnectAsync() {
        return closeInternal(false);
    }

    private CompletableFuture<Void> closeInternal(boolean userInitiated) {
        if (!closeStarted.compareAndSet(false, true)) {
            return closeFuture;
        }

        if (userInitiated) {
            ttyConnector.close();
        } else {
            ttyConnector.closeAfterDisconnect();
        }
        SwingExecutors.runOnEdtAsync(() -> {
            widget.stop();
            widget.close();
        }).thenCompose(unused -> ttyConnector.closeFuture())
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        closeFuture.completeExceptionally(throwable);
                    } else {
                        closeFuture.complete(null);
                    }
                });
        return closeFuture;
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
    public void sendStringToTerminalSilently(String text) {
        if (text == null || text.isEmpty()) return;
        // 必须先注册过滤目标，再把输入交给 EDT，避免远端快速回显造成竞态。
        ttyConnector.suppressNextEcho(text);
        sendStringToTerminal(text);
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

    @Override
    public StringProperty cwdProperty() {
        return ttyConnector.cwdProperty();
    }

    /** 注册连接断开回调，回调在 JavaFX 应用线程执行。 */
    public void setOnDisconnected(Consumer<ShellTtyConnector.DisconnectReason> callback) {
        ttyConnector.setOnDisconnected(callback);
    }

    /** 返回是否已连接。 */
    public boolean isConnected() {
        return ttyConnector.isConnected();
    }
}
