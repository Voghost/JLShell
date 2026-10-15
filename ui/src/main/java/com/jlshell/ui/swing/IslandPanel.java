package com.jlshell.ui.swing;

import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** Islands 的独立内容面板。逻辑像素与 Swing HiDPI 缩放共用，不产生离屏副本。 */
final class IslandPanel extends JPanel {
    private final String color;

    IslandPanel(LayoutManager layout, String color) {
        super(layout);
        this.color = color;
        setOpaque(false);
    }

    // 子控件（终端、目录树、滚动视口）独立重绘时也必须经过本层的圆角裁剪。
    @Override protected boolean isPaintingOrigin() { return true; }

    @Override
    public void paint(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var shape = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 16, 16);
            g.setColor(SwingTheme.color(color));
            g.fill(shape);
            g.clip(shape);
            super.paint(g);
        } finally { g.dispose(); }
    }
}
