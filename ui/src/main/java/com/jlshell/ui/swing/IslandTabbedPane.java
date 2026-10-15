package com.jlshell.ui.swing;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import javax.swing.JButton;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/** 按 IslandsTabPainter 的背景/边框方式绘制；布局、滚动及键盘导航继续交给 FlatLaf。 */
final class IslandTabbedPane extends JTabbedPane {
    private IdeaListPopup overflowPopup;

    private static void paintNavigationHover(Graphics2D g, int width, int height, boolean pressed) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(SwingTheme.color(pressed ? "headerPressed" : "headerHover"));
        g.fillRoundRect(2, 3, width - 4, height - 6, 12, 12);
    }

    private void showOverflow(JButton button) {
        if (overflowPopup != null && overflowPopup.isShowing()) { overflowPopup.setVisible(false); return; }
        JViewport viewport = null;
        for (Component child : getComponents()) if (child instanceof JViewport candidate) { viewport = candidate; break; }
        if (viewport == null) return;
        Rectangle visible = SwingUtilities.convertRectangle(viewport, new Rectangle(viewport.getSize()), this);
        var rows = new java.util.ArrayList<IdeaListPopup.Row>();
        for (int index = 0; index < getTabCount(); index++) {
            if (visible.contains(getBoundsAt(index))) continue;
            Component content = getComponentAt(index);
            rows.add(new IdeaListPopup.Row(getTitleAt(index), null,
                    getIconAt(index) == null ? new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL) : getIconAt(index),
                    index == getSelectedIndex() ? "✓" : "", null, isEnabledAt(index), java.util.List.of(), () -> {
                        int target = indexOfComponent(content);
                        if (target >= 0 && isEnabledAt(target)) setSelectedIndex(target);
                    }));
        }
        if (rows.isEmpty()) return;
        overflowPopup = new IdeaListPopup(rows, 240, false, "");
        overflowPopup.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) button.repaint();
        });
        overflowPopup.setName("tabs.overflowPopup");
        Dimension size = overflowPopup.getPreferredSize();
        overflowPopup.setPreferredSize(new Dimension(Math.min(420, size.width), size.height));
        overflowPopup.show(button, button.getWidth() - overflowPopup.getPreferredSize().width, button.getHeight() + 4);
    }

    @Override public void removeNotify() {
        if (overflowPopup != null) overflowPopup.setVisible(false);
        super.removeNotify();
    }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getTabCount() == 0 && getClientProperty("JLShell.emptyGroupText") instanceof String message) {
            graphics.setColor(SwingTheme.color("secondary"));
            graphics.setFont(getFont());
            var metrics = graphics.getFontMetrics();
            graphics.drawString(message, Math.max(12, (getWidth() - metrics.stringWidth(message)) / 2), getHeight() / 2);
        }
    }
    @Override
    public void updateUI() {
        if (overflowPopup != null) overflowPopup.setVisible(false);
        setUI(new FlatTabbedPaneUI() {
            @Override protected JButton createMoreTabsButton() {
                return new FlatMoreTabsButton() {
                    { setName("tabs.overflowButton"); }
                    @Override public void actionPerformed(ActionEvent event) { showOverflow(this); }
                    @Override protected boolean isHover() { return super.isHover() || overflowPopup != null && overflowPopup.isShowing(); }
                    @Override protected void paintBackground(Graphics2D g) { paintNavigationHover(g, getWidth(), getHeight(), isPressed()); }
                };
            }
            @Override protected JButton createScrollButton(int direction) {
                return new FlatScrollableTabButton(direction) {
                    @Override protected void paintBackground(Graphics2D g) { paintNavigationHover(g, getWidth(), getHeight(), isPressed()); }
                };
            }
            @Override protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
                // 工作区标签与内容连为一体；不绘制 FlatLaf 默认的内容分隔线。
            }
            @Override protected java.awt.Insets getContentBorderInsets(int placement) {
                return new java.awt.Insets(0, 0, 0, 0);
            }
            @Override
            protected void paintTabBackground(Graphics graphics, int placement, int index,
                                              int x, int y, int w, int h, boolean selected) {
                if (!selected && getRolloverTab() != index) return;
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    var rect = new RoundRectangle2D.Double(x + 2, y + 3, w - 4, h - 6, 6, 6);
                    boolean active = !Boolean.FALSE.equals(tabPane.getClientProperty("JLShell.activeGroup"));
                    g.setColor(selected ? UIManager.getColor(active ? "JLShell.tabSelection" : "JLShell.tabInactiveSelection")
                            : SwingTheme.color("headerHover"));
                    g.fill(rect);
                    if (selected) {
                        g.setColor(UIManager.getColor(active ? "JLShell.tabBorder" : "JLShell.tabInactiveBorder"));
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
