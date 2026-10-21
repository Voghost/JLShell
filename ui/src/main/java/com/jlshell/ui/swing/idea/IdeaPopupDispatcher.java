// Copyright 2000-2023 JetBrains s.r.o. and contributors. Apache-2.0.
// Adapted from platform/platform-impl/src/com/intellij/ui/popup/PopupDispatcher.java.
package com.jlshell.ui.swing.idea;

import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.*;

/** IDEA 的活动根弹窗/子弹窗链，使用 AWT 事件队列替代 IdeEventQueue。 */
final class IdeaPopupDispatcher implements AWTEventListener, KeyEventDispatcher {
    private static final IdeaPopupDispatcher INSTANCE = new IdeaPopupDispatcher();
    private IdeaListPopup root;

    static void activate(IdeaListPopup popup) {
        if (INSTANCE.root != null && INSTANCE.root != popup) INSTANCE.root.setVisible(false);
        if (INSTANCE.root == popup) return;
        INSTANCE.root = popup;
        Toolkit.getDefaultToolkit().addAWTEventListener(INSTANCE,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK
                        | AWTEvent.COMPONENT_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(INSTANCE);
    }

    static void deactivate(IdeaListPopup popup) {
        if (INSTANCE.root != popup) return;
        INSTANCE.root = null;
        Toolkit.getDefaultToolkit().removeAWTEventListener(INSTANCE);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(INSTANCE);
    }

    @Override public void eventDispatched(AWTEvent event) {
        IdeaListPopup active = root;
        if (active == null) return;
        if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED) {
            // 先检查整条父子链的屏幕区域；列表内部点击完整交给列表，不能被 JMenu 处理吞掉。
            Point point = mouse.getLocationOnScreen();
            if (!active.containsScreen(point)) active.setVisible(false);
        } else if (event instanceof WindowEvent window
                && (window.getID() == WindowEvent.WINDOW_LOST_FOCUS || window.getID() == WindowEvent.WINDOW_DEACTIVATED)) {
            SwingUtilities.invokeLater(() -> {
                if (root != active) return;
                Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                if (!active.owns(focused) && focused != active.ownerWindow()) active.setVisible(false);
            });
        } else if (event.getSource() == active.ownerWindow()
                && (event.getID() == ComponentEvent.COMPONENT_MOVED || event.getID() == ComponentEvent.COMPONENT_RESIZED
                    || event.getID() == ComponentEvent.COMPONENT_HIDDEN || event.getID() == WindowEvent.WINDOW_CLOSING)) {
            active.setVisible(false);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (root == null || event.getID() != KeyEvent.KEY_PRESSED) return false;
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown()) return false;
        if (!root.dispatchKey(event.getKeyCode())) return false;
        event.consume();
        return true;
    }
}
