package com.jlshell.terminal.service;

import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.terminal.model.TerminalColorScheme;

import com.jlshell.plugin.api.lifecycle.Registration;
import java.util.function.Consumer;

/**
 * 已创建的终端视图句柄。
 * UI 层只应持有该句柄，而不应直接操作 JediTerm 实现细节。
 */
public interface TerminalViewHandle extends AutoCloseable {

    SessionId sessionId();

    String title();

    default String encodingName() { return "UTF-8"; }

    JComponent component();

    void requestFocus();

    CompletableFuture<Void> updateFontProfile(FontProfile fontProfile);

    CompletableFuture<Void> updateColorScheme(TerminalColorScheme colorScheme);

    CompletableFuture<Void> closeAsync();

    /**
     * 把字符串注入到终端的输入流，等价于用户键入这些字符。
     * 供输入法提交和宿主快捷操作使用。
     */
    default void sendStringToTerminal(String text) {
        // 默认 no-op
    }

    /**
     * 向终端发送程序内部命令，并隐藏该命令自身的 PTY 回显。
     * 命令执行产生的真实输出仍会正常显示；不支持的实现回退为普通发送。
     */
    default void sendStringToTerminalSilently(String text) {
        sendStringToTerminal(text);
    }

    /**
     * 返回终端光标在 Swing 组件内的像素坐标，用于 IME 候选窗定位。
     * 默认返回 (0,0)，不影响不支持的实现。
     */
    default java.awt.Point getCursorLocationInComponent() {
        return new java.awt.Point(0, 0);
    }

    /**
     * 返回 {@link #getCursorLocationInComponent()} 所使用的 Swing 组件尺寸。
     * UI 层可用它映射终端逻辑坐标，适应不同 DPI 的显示器。
     */
    default java.awt.Dimension getTerminalComponentSize() {
        JComponent terminalComponent = component();
        return terminalComponent == null
                ? new java.awt.Dimension(0, 0)
                : terminalComponent.getSize();
    }

    /** Current directory reported by OSC 7, or an empty string before the first report. */
    default String currentDirectory() {
        return "";
    }

    /**
     * Implementations with directory reporting deliver the current value and distinct changes on Swing EDT.
     * Closing the registration suppresses queued callbacks and is idempotent.
     */
    default Registration onDirectoryChanged(Consumer<String> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        return Registration.noop();
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
