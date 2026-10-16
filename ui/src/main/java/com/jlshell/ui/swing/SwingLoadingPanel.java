package com.jlshell.ui.swing;

import javax.swing.*;
import java.awt.*;

/** Lightweight loading state. The progress animation only runs while the component is showing. */
final class SwingLoadingPanel extends JPanel {
    SwingLoadingPanel(String title, String detail, WorkbenchIcon.Kind kind) {
        super(new GridBagLayout());
        setName("workspace.loading");
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        JLabel icon = new JLabel(new WorkbenchIcon(kind));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        JLabel heading = new JLabel(title);
        heading.putClientProperty("html.disable", true);
        heading.setName("workspace.loading.title");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 2));
        heading.setAlignmentX(CENTER_ALIGNMENT);
        JLabel subtitle = new JLabel(detail == null || detail.isBlank() ? " " : detail);
        subtitle.putClientProperty("html.disable", true);
        subtitle.setName("workspace.loading.detail");
        subtitle.setForeground(SwingTheme.color("secondary"));
        subtitle.putClientProperty("JLShell.secondaryForeground", true);
        subtitle.setAlignmentX(CENTER_ALIGNMENT);
        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setBorderPainted(false);
        progress.setPreferredSize(new Dimension(160, 3));
        progress.setMaximumSize(new Dimension(160, 3));
        progress.setAlignmentX(CENTER_ALIGNMENT);
        body.add(icon);
        body.add(Box.createVerticalStrut(14));
        body.add(heading);
        body.add(Box.createVerticalStrut(8));
        body.add(subtitle);
        body.add(Box.createVerticalStrut(20));
        body.add(progress);
        add(body);
    }
}
