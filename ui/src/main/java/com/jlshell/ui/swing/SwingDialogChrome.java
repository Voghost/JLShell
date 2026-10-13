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
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER,
                SystemInfo.isMacOS ? "mac horizontal" : "win horizontal");
        header.add(title, BorderLayout.CENTER);
        header.add(buttons, SystemInfo.isMacOS ? BorderLayout.WEST : BorderLayout.EAST);
        dialog.add(header, BorderLayout.NORTH);
        return header;
    }

    static Window owner() {
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null) return active;
        for (Frame frame : Frame.getFrames()) if (frame.isShowing() && frame.getTitle().contains("JLShell")) return frame;
        return null;
    }
}
