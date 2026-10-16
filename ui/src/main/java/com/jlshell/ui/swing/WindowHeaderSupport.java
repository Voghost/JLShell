package com.jlshell.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatNativeWindowBorder;
import com.formdev.flatlaf.util.SystemInfo;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Function;

/** Shared caption hit testing and control spacing for integrated window headers. */
final class WindowHeaderSupport {
    private WindowHeaderSupport() {}

    static JPanel buttonsPlaceholder(String platform, int gap) {
        JPanel placeholder = new JPanel() {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(size.width > 0 ? size.width + gap : 0, size.height);
            }
        };
        placeholder.setName("window.buttonsPlaceholder." + platform);
        placeholder.setOpaque(false);
        placeholder.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, platform + " horizontal");
        return placeholder;
    }

    static void installDragSupport(Window window, JComponent header) {
        // Returning null lets FlatLaf inspect children: toolbar buttons keep their mouse input,
        // while empty header space is a native caption (HTCAPTION on Windows).
        header.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION,
                (Function<Point, Boolean>) point -> null);
        if (SystemInfo.isWindows && FlatNativeWindowBorder.isSupported()) return;
        // Keep the existing move fallback on platforms without the Windows native caption path.
        MouseAdapter movement = new MouseAdapter() {
            private Point screenPoint;
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) screenPoint = event.getLocationOnScreen();
            }
            public void mouseDragged(MouseEvent event) {
                if (screenPoint == null || window instanceof JFrame frame && frame.getExtendedState() != JFrame.NORMAL) return;
                Point next = event.getLocationOnScreen();
                window.setLocation(window.getX() + next.x - screenPoint.x, window.getY() + next.y - screenPoint.y);
                screenPoint = next;
            }
            public void mouseReleased(MouseEvent event) { screenPoint = null; }
        };
        header.addMouseListener(movement); header.addMouseMotionListener(movement);
    }
}
