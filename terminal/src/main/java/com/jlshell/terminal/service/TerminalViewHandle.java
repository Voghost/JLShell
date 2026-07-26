package com.jlshell.terminal.service;

import java.util.concurrent.CompletableFuture;

import javax.swing.JComponent;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.SessionId;
import com.jlshell.terminal.model.TerminalColorScheme;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

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
     * UI 层会用它把 Swing 逻辑坐标映射到 SwingNode 的 JavaFX 局部坐标；
     * 这比假定两边永远 1:1 更能适应节点缩放和不同 DPI 的显示器。
     */
    default java.awt.Dimension getTerminalComponentSize() {
        JComponent terminalComponent = component();
        return terminalComponent == null
                ? new java.awt.Dimension(0, 0)
                : terminalComponent.getSize();
    }

    /**
     * 终端当前工作目录（通过 OSC 7 序列追踪）。
     * 可观察属性，目录变化时自动更新。
     */
    default StringProperty cwdProperty() {
        // 默认返回一个不可变的空属性
        return new SimpleStringProperty("");
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
