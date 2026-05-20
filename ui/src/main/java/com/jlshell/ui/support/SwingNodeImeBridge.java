package com.jlshell.ui.support;

import com.jlshell.terminal.service.TerminalViewHandle;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;

/**
 * 让 JavaFX 接管 SwingNode 嵌入的 JediTerm 终端的输入法。
 *
 * 背景：JediTerm 在 AWT 层实现了 IME，但 JavaFX SwingNode 把 JComponent
 * 渲染到一个隐藏的离屏 JFrame 上，AWT 的 IME 链路（系统 → Component →
 * processInputMethodEvent）在这种结构下基本不工作 —— 候选窗位置错乱，
 * 提交事件也不会送达。
 *
 * 解决方案：直接在 SwingNode 上挂 JavaFX 的 IME 监听。JavaFX 自身的
 * IME 支持是健全的，commit 阶段我们把字符串通过 TerminalViewHandle 注入
 * 到 terminal；preedit 阶段返回光标的 FX 屏幕坐标，候选窗自然跟随。
 */
public final class SwingNodeImeBridge {

    private SwingNodeImeBridge() {}

    public static void attach(SwingNode swingNode, TerminalViewHandle handle) {
        swingNode.setOnInputMethodTextChanged(event -> handleEvent(event, handle));
        swingNode.setInputMethodRequests(new InputMethodRequests() {
            @Override
            public Point2D getTextLocation(int offset) {
                // 把候选窗锚定在 SwingNode 的左下角附近。理想情况是返回光标 cell
                // 的真实位置，但 JediTerm 没有公开"光标屏幕坐标"的 API，把锚定点
                // 放在 SwingNode 底部已经足够避免它跑到屏幕左上角。
                if (swingNode.getScene() == null || swingNode.getScene().getWindow() == null) {
                    return Point2D.ZERO;
                }
                Bounds bounds = swingNode.localToScreen(swingNode.getBoundsInLocal());
                if (bounds == null) return Point2D.ZERO;
                return new Point2D(bounds.getMinX(), bounds.getMaxY());
            }

            @Override
            public int getLocationOffset(int x, int y) {
                return 0;
            }

            @Override
            public void cancelLatestCommittedText() {
                // 终端不支持撤销已提交的字符，no-op
            }

            @Override
            public String getSelectedText() {
                return "";
            }
        });
    }

    private static void handleEvent(InputMethodEvent event, TerminalViewHandle handle) {
        String committed = event.getCommitted();
        if (committed != null && !committed.isEmpty()) {
            handle.sendStringToTerminal(committed);
        }
        // 未提交的 preedit 文本由系统候选窗自行显示，不写入终端
    }
}
