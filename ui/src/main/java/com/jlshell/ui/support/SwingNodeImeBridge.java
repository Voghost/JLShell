package com.jlshell.ui.support;

import com.jlshell.terminal.service.TerminalViewHandle;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;

import java.awt.Dimension;
import java.awt.Point;

public final class SwingNodeImeBridge {

    private SwingNodeImeBridge() {}

    public static void attach(SwingNode swingNode, TerminalViewHandle handle) {
        swingNode.setOnInputMethodTextChanged(event -> handleEvent(event, handle));
        swingNode.setInputMethodRequests(new InputMethodRequests() {
            @Override
            public Point2D getTextLocation(int offset) {
                if (swingNode.getScene() == null || swingNode.getScene().getWindow() == null) {
                    return Point2D.ZERO;
                }
                // 光标位置是 Swing 组件逻辑坐标。先映射到 SwingNode 局部坐标，
                // 再让 JavaFX 的 localToScreen 统一处理父节点变换、HiDPI 和显示器缩放。
                java.awt.Point cursorInComponent = handle.getCursorLocationInComponent();
                Point2D cursorInNode = mapComponentToNode(
                        cursorInComponent,
                        handle.getTerminalComponentSize(),
                        swingNode.getBoundsInLocal()
                );
                Point2D cursorOnScreen = swingNode.localToScreen(cursorInNode);
                return cursorOnScreen == null ? Point2D.ZERO : cursorOnScreen;
            }

            @Override
            public int getLocationOffset(int x, int y) {
                return 0;
            }

            @Override
            public void cancelLatestCommittedText() {}

            @Override
            public String getSelectedText() {
                return "";
            }
        });
    }

    public static void detach(SwingNode swingNode) {
        if (swingNode == null) return;
        swingNode.setOnInputMethodTextChanged(null);
        swingNode.setInputMethodRequests(null);
    }

    private static void handleEvent(InputMethodEvent event, TerminalViewHandle handle) {
        String committed = event.getCommitted();
        if (committed != null && !committed.isEmpty()) {
            handle.sendStringToTerminal(committed);
        }
    }

    static Point2D mapComponentToNode(Point cursor, Dimension componentSize, Bounds nodeBounds) {
        if (cursor == null || nodeBounds == null) {
            return Point2D.ZERO;
        }
        double scaleX = componentSize != null && componentSize.width > 0
                ? nodeBounds.getWidth() / componentSize.width
                : 1.0;
        double scaleY = componentSize != null && componentSize.height > 0
                ? nodeBounds.getHeight() / componentSize.height
                : 1.0;
        return new Point2D(
                nodeBounds.getMinX() + cursor.x * scaleX,
                nodeBounds.getMinY() + cursor.y * scaleY
        );
    }
}
