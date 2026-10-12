package com.jlshell.ui.swing;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** 按 IslandsTabPainter 的背景/边框方式绘制；布局、滚动及键盘导航继续交给 FlatLaf。 */
final class IslandTabbedPane extends JTabbedPane {
    @Override
    public void updateUI() {
        setUI(new FlatTabbedPaneUI() {
            @Override
            protected void paintTabBackground(Graphics graphics, int placement, int index,
                                              int x, int y, int w, int h, boolean selected) {
                if (!selected && getRolloverTab() != index) return;
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    var rect = new RoundRectangle2D.Double(x + 2, y + 3, w - 4, h - 6, 6, 6);
                    g.setColor(selected ? UIManager.getColor("JLShell.tabSelection") : SwingTheme.color("border"));
                    g.fill(rect);
                    if (selected) {
                        g.setColor(UIManager.getColor("JLShell.tabBorder"));
                        g.draw(rect);
                    }
                } finally { g.dispose(); }
            }

            @Override
            protected void paintTabSelection(Graphics g, int placement, int index, int x, int y, int w, int h) {
                // Islands 的选中标记为圆角细边框，底部工具窗口标签另有下划线。
            }
        });
    }
}
