package com.jlshell.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemInfo;
import javax.swing.*;
import java.awt.*;

/** 参考 IDEA DialogHeader：主题化标题区域，保留系统窗口操作与拖动。 */
final class SwingDialogChrome {
    private SwingDialogChrome() {}

    static JPanel configure(JDialog dialog) {
        JRootPane pane = dialog.getRootPane();
        pane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        pane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        pane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        if (SystemInfo.isMacOS) {
            pane.putClientProperty("apple.awt.fullWindowContent", true);
            pane.putClientProperty("apple.awt.transparentTitleBar", true);
            pane.putClientProperty("apple.awt.windowTitleVisible", false);
        }
        JPanel header = new JPanel(new BorderLayout(8, 0)) {
            protected void paintComponent(Graphics g) {
                setBackground(SwingTheme.color("frame"));
                super.paintComponent(g);
            }
        };
        header.setName("dialog.header");
        header.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        header.setPreferredSize(new Dimension(0, 34));
        JLabel title = new JLabel(dialog.getTitle());
        title.setName("dialog.title");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.putClientProperty("html.disable", true);
        title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 16));
        title.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        JPanel buttons = WindowHeaderSupport.buttonsPlaceholder(SystemInfo.isMacOS ? "mac" : "win", 12);
        header.add(title, BorderLayout.CENTER);
        header.add(buttons, SystemInfo.isMacOS ? BorderLayout.WEST : BorderLayout.EAST);
        if (!SystemInfo.isMacOS) pane.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, 34);
        WindowHeaderSupport.installDragSupport(dialog, header);
        dialog.add(header, BorderLayout.NORTH);
        return header;
    }

    static Window owner() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        // A non-focusable combo popup can briefly leave no active window on macOS.
        // Follow ownership to the visible dialog instead of falling back to its disabled frame.
        while (active instanceof JWindow) active = active.getOwner();
        if (active != null && active.isShowing()) return visibleDialogOwner(active);
        for (Frame frame : Frame.getFrames())
            if (frame.isShowing() && frame.getTitle().contains("JLShell")) return visibleDialogOwner(frame);
        return null;
    }

    private static Window visibleDialogOwner(Window window) {
        Window[] children = window.getOwnedWindows();
        for (int i = children.length - 1; i >= 0; i--)
            if (children[i] instanceof JDialog && children[i].isShowing()) return visibleDialogOwner(children[i]);
        return window;
    }
}
