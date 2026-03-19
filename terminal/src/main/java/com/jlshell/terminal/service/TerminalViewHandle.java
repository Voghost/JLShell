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

    @Override
    default void close() {
        closeAsync().join();
    }
}
