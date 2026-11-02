package com.jlshell.ui.swing.idea;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Rich popup content using the same rounded surface and native corners as workbench menus. */
public final class IdeaContentPopup implements AutoCloseable, AWTEventListener, KeyEventDispatcher {
    private JWindow window;
    private Window owner;

    public void showAbove(JComponent anchor, JComponent content, int preferredWidth) {
        close();
        owner = SwingUtilities.getWindowAncestor(anchor);
        window = new JWindow(owner);
        window.setName("idea.contentPopup"); window.setType(Window.Type.POPUP);
        boolean alpha = IdeaPopupWindow.configure(window, false);
        content.setOpaque(false);
        var surface = new IdeaPopupSurface(content, alpha);
        window.setContentPane(surface);
        Rectangle screen = new Rectangle(anchor.getGraphicsConfiguration().getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(anchor.getGraphicsConfiguration());
        screen.x += insets.left; screen.y += insets.top;
        screen.width -= insets.left + insets.right; screen.height -= insets.top + insets.bottom;
        int margin = alpha ? IdeaPopupSurface.SHADOW : 0;
        int width = Math.min(preferredWidth, Math.min(screen.width - 24, owner.getWidth() - 24));
        Dimension size = content.getPreferredSize();
        content.setPreferredSize(new Dimension(Math.max(220, width), Math.min(size.height, screen.height - 70)));
        window.pack(); IdeaPopupWindow.decorate(window, false);
        Point location = anchor.getLocationOnScreen();
        int x = Math.max(screen.x, Math.min(location.x, screen.x + screen.width - window.getWidth()));
        int y = Math.max(screen.y, location.y - window.getHeight() - 4 + margin);
        window.setLocation(x, y);
        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        window.setVisible(true);
    }
    public boolean isShowing() { return window != null && window.isShowing(); }
    @Override public void eventDispatched(AWTEvent event) {
        if (window == null) return;
        if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED) {
            if (!new Rectangle(window.getLocationOnScreen(), window.getSize()).contains(mouse.getLocationOnScreen())) close();
        } else if (event instanceof WindowEvent focus && focus.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
            JWindow active = window;
            SwingUtilities.invokeLater(() -> {
                if (window != active) return;
                Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                if (focused != window && focused != owner) close();
            });
        } else if (event.getSource() == owner && (event.getID() == ComponentEvent.COMPONENT_MOVED
                || event.getID() == ComponentEvent.COMPONENT_RESIZED || event.getID() == ComponentEvent.COMPONENT_HIDDEN)) close();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (window == null || event.getID() != KeyEvent.KEY_PRESSED || event.getKeyCode() != KeyEvent.VK_ESCAPE) return false;
        close(); event.consume(); return true;
    }
    @Override public void close() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        if (window != null) { JWindow old = window; window = null; old.dispose(); }
        owner = null;
    }
}
