package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * AbstractPopup / WindowRoundedCornersManager 的透明窗口表面适配。
 * IDEA 的 JBR 原生圆角半径为 8；OpenJDK 使用透明窗口和 Java2D 绘制同样的轮廓。
 */
final class IdeaPopupSurface extends JPanel {
    static final int SHADOW = 10;
    private static final int RADIUS = 8;
    private final boolean translucent;

    IdeaPopupSurface(JComponent content, boolean translucent) {
        super(new BorderLayout());
        this.translucent = translucent;
        setName("idea.popupSurface");
        setOpaque(!translucent);
        setBackground(SwingTheme.color("popup"));
        int margin = translucent ? SHADOW : 0;
        setBorder(BorderFactory.createEmptyBorder(margin + 1, margin + 1, margin + 1, margin + 1));
        add(content);
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = translucent ? SHADOW : 0;
            int width = getWidth() - inset * 2, height = getHeight() - inset * 2;
            if (translucent) {
                // 软件阴影只画在圆角轮廓之外，避免背景角落出现方形色块。
                for (int spread = SHADOW; spread > 0; spread--) {
                    g.setColor(new Color(0, 0, 0, 3 + (SHADOW - spread) / 2));
                    g.fill(new RoundRectangle2D.Double(inset - spread, inset - spread + 2,
                            width + spread * 2, height + spread * 2, (RADIUS + spread) * 2, (RADIUS + spread) * 2));
                }
            }
            g.setColor(SwingTheme.color("popupOutline"));
            g.fill(new RoundRectangle2D.Double(inset, inset, width, height, RADIUS * 2, RADIUS * 2));
            g.setColor(SwingTheme.color("popupBorder"));
            g.fill(new RoundRectangle2D.Double(inset + 1, inset + 1, width - 2, height - 2, RADIUS * 2 - 2, RADIUS * 2 - 2));
            g.setColor(getBackground());
            g.fill(new RoundRectangle2D.Double(inset + 2, inset + 2, width - 4, height - 4, RADIUS * 2 - 4, RADIUS * 2 - 4));
        } finally { g.dispose(); }
    }
    @Override protected void paintChildren(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int inset = (translucent ? SHADOW : 0) + 2;
            g.clip(new RoundRectangle2D.Double(inset, inset, getWidth() - inset * 2, getHeight() - inset * 2,
                    RADIUS * 2 - 4, RADIUS * 2 - 4));
            super.paintChildren(g);
        } finally { g.dispose(); }
    }
}
