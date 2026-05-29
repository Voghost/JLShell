package com.jlshell.ui.support;

import com.jlshell.terminal.service.TerminalViewHandle;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;

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
                Bounds bounds = swingNode.localToScreen(swingNode.getBoundsInLocal());
                if (bounds == null) return Point2D.ZERO;
                return new Point2D(bounds.getMinX(), bounds.getMaxY());
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

    private static void handleEvent(InputMethodEvent event, TerminalViewHandle handle) {
        String committed = event.getCommitted();
        if (committed != null && !committed.isEmpty()) {
            handle.sendStringToTerminal(committed);
        }
    }
}