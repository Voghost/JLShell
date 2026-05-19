package com.jlshell.terminal.service;

import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 已创建的终端视图句柄。
 * UI 层只应持有该句柄，而不应直接操作 JediTerm 实现细节。
 */
public interface TerminalViewHandle extends AutoCloseable {

    SessionId sessionId();

    String title();

    JComponent component();

    void requestFocus();

    CompletableFuture<Void> updateFontProfile(FontProfile fontProfile);

    CompletableFuture<Void> updateColorScheme(TerminalColorScheme colorScheme);

    CompletableFuture<Void> closeAsync();

    /**
     * 注入"真实屏幕坐标提供器"。macOS 下 JavaFX SwingNode 把 JComponent
     * 挂在离屏 JFrame 上，Component#getLocationOnScreen 返回的是离屏坐标，
     * 导致 IME 候选窗落到屏幕左上角。UI 层在 SwingNode 已经挂到场景图上
     * 之后调用本方法，把 SwingNode 的 javafx 屏幕坐标转成 awt.Point 注入。
     */
    default void setScreenLocationSupplier(java.util.function.Supplier<java.awt.Point> supplier) {
        // 默认 no-op，避免破坏现有实现
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
