package com.jlshell.core.session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.TerminalSize;

/**
 * 交互式 Shell 抽象。
 * 终端模块会基于该接口接入 ANSI 终端模拟器，避免直接依赖 SSHJ 的 Shell 类型。
 */
public interface ShellChannel extends AutoCloseable {

    InputStream remoteOutput();

    OutputStream remoteInput();

    boolean isOpen();

    CompletableFuture<Void> resize(TerminalSize terminalSize);

    CompletableFuture<Void> closeAsync();

    default <T> Optional<T> unwrap(Class<T> type) {
        return Optional.empty();
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
