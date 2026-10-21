package com.jlshell.ui.swing.idea;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Rounded popup for interactive Swing content (trees must receive ordinary mouse events). */
public final class IdeaComponentPopup extends JPanel implements AutoCloseable {
    private JWindow window;
    private Component invoker;
    private Window owner;
    private final AWTEventListener outside = this::dispatch;
    private final KeyEventDispatcher escape = event -> {
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE && isShowing()) {
            close(); event.consume(); return true;
        }
        return false;
    };
    private final ComponentAdapter ownerChanges = new ComponentAdapter() {
        public void componentMoved(ComponentEvent event) { close(); }
        public void componentResized(ComponentEvent event) { close(); }
        public void componentHidden(ComponentEvent event) { close(); }
    };
    private final HierarchyListener invokerChanges = event -> {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && invoker != null && !invoker.isShowing()) close();
    };

    public IdeaComponentPopup(JComponent content) {
        super(new BorderLayout());
        setOpaque(false); setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(content); super.setVisible(false);
    }

    public void show(Component anchor, int x, int y) {
        close(); invoker = anchor; owner = SwingUtilities.getWindowAncestor(anchor);
        window = new JWindow(owner); window.setType(Window.Type.POPUP); window.setName("idea.componentPopupWindow");
        boolean alpha = IdeaPopupWindow.configure(window, false);
        super.setVisible(true);
        window.setContentPane(new IdeaPopupSurface(this, alpha)); window.pack();
        IdeaPopupWindow.decorate(window, false);
        Point position = new Point(x, y); SwingUtilities.convertPointToScreen(position, anchor);
        GraphicsConfiguration config = anchor.getGraphicsConfiguration(); Rectangle screen = new Rectangle(config.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        screen.x += insets.left; screen.y += insets.top; screen.width -= insets.left + insets.right; screen.height -= insets.top + insets.bottom;
        int margin = alpha ? IdeaPopupSurface.SHADOW : 0;
        int width = window.getWidth() - margin * 2, height = window.getHeight() - margin * 2;
        window.setLocation(Math.max(screen.x, Math.min(position.x, screen.x + screen.width - width)) - margin,
                Math.max(screen.y, Math.min(position.y, screen.y + screen.height - height)) - margin);
        Toolkit.getDefaultToolkit().addAWTEventListener(outside, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape);
        if (owner != null) owner.addComponentListener(ownerChanges);
        anchor.addHierarchyListener(invokerChanges);
        window.setVisible(true);
    }

    private void dispatch(AWTEvent event) {
        if (window == null) return;
        if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED) {
            Component source = mouse.getComponent();
            if (source != window && !SwingUtilities.isDescendingFrom(source, window)) close();
        } else if (event instanceof WindowEvent change) {
            if (change.getID() == WindowEvent.WINDOW_CLOSING && change.getWindow() == owner) close();
            else if (change.getID() == WindowEvent.WINDOW_DEACTIVATED || change.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
                // macOS may omit oppositeWindow while focus moves from the owner into its popup.
                JWindow expected = window;
                SwingUtilities.invokeLater(() -> {
                    if (window != expected) return;
                    Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                    if (focused != window && focused != owner) close();
                });
            }
        }
    }

    @Override public void close() {
        if (window == null) return;
        JWindow previous = window; window = null;
        Toolkit.getDefaultToolkit().removeAWTEventListener(outside);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape);
        if (owner != null) owner.removeComponentListener(ownerChanges);
        if (invoker != null) invoker.removeHierarchyListener(invokerChanges);
        super.setVisible(false); previous.dispose(); invoker = null; owner = null;
    }
}
