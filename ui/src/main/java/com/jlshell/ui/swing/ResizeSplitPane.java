package com.jlshell.ui.swing;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 分隔条悬停和拖动期间显式维护原生窗口光标，避免跨终端组件后保留文本/默认光标。 */
final class ResizeSplitPane extends JSplitPane {
    private Window cursorWindow;
    private Cursor previousCursor;

    ResizeSplitPane(int orientation) { super(orientation); }
    ResizeSplitPane(int orientation, Component first, Component second) { super(orientation, first, second); }

    @Override public void updateUI() {
        restoreWindowCursor();
        super.updateUI();
        if (!(getUI() instanceof BasicSplitPaneUI ui)) return;
        var divider = ui.getDivider();
        divider.setCursor(resizeCursor());
        MouseAdapter feedback = new MouseAdapter() {
            private boolean dragging;
            private void resize() {
                if (!isEnabled() || getDividerSize() == 0) return;
                divider.setCursor(resizeCursor());
                Window window = SwingUtilities.getWindowAncestor(ResizeSplitPane.this);
                if (window == null) return;
                if (cursorWindow != window) {
                    restoreWindowCursor(); cursorWindow = window; previousCursor = window.getCursor();
                }
                window.setCursor(resizeCursor());
            }
            @Override public void mouseEntered(MouseEvent event) { resize(); }
            @Override public void mouseMoved(MouseEvent event) { resize(); }
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) { dragging = true; resize(); }
            }
            @Override public void mouseDragged(MouseEvent event) { if (dragging) resize(); }
            @Override public void mouseExited(MouseEvent event) { if (!dragging) restoreWindowCursor(); }
            @Override public void mouseReleased(MouseEvent event) {
                dragging = false; restoreWindowCursor();
                if (divider.contains(event.getPoint())) resize();
            }
        };
        divider.addMouseListener(feedback); divider.addMouseMotionListener(feedback);
    }

    private Cursor resizeCursor() {
        return Cursor.getPredefinedCursor(getOrientation() == HORIZONTAL_SPLIT ? Cursor.E_RESIZE_CURSOR : Cursor.S_RESIZE_CURSOR);
    }
    private void restoreWindowCursor() {
        if (cursorWindow != null) cursorWindow.setCursor(previousCursor);
        cursorWindow = null; previousCursor = null;
    }
    @Override public void removeNotify() { restoreWindowCursor(); super.removeNotify(); }
}
