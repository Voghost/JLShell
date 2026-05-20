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
     * 把字符串注入到终端的输入流，等价于用户键入这些字符。
     * 用于 JavaFX IME commit：在 SwingNode 下 AWT IME 链路不工作，
     * UI 层接收 JavaFX 的 InputMethodEvent，commit 部分直接走这里。
     */
    default void sendStringToTerminal(String text) {
        // 默认 no-op
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
