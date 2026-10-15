package com.jlshell.ui.swing;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** 独立的 16px 线条图标，使用逻辑坐标，由 Swing 的 HiDPI 变换缩放。 */
public record WorkbenchIcon(Kind kind) implements Icon {
    public enum Kind { SERVER, SSH, FOLDER, PLUGIN, ADD, RUN, REFRESH, SETTINGS, CLOSE, CHECK, TERMINAL, PROJECT, EDIT, DELETE, SEARCH, MORE, HIDE, DOCK, PIN, MENU, MONITOR, USER, HELP, INFO, LAYOUT, SPLIT_RIGHT, SPLIT_DOWN, CHEVRON_DOWN, FOCUS, EXPAND_ALL, COLLAPSE_ALL }
    public int getIconWidth() { return 16; }
    public int getIconHeight() { return 16; }

    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(kind == Kind.CLOSE || kind == Kind.CHECK ? 1.5f : 1f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            boolean selectedRail = component instanceof javax.swing.AbstractButton button && button.isSelected()
                    && Boolean.TRUE.equals(button.getClientProperty("JLShell.selectedRail"));
            g.setColor(selectedRail ? java.awt.Color.WHITE : !component.isEnabled()
                    ? javax.swing.UIManager.getColor("Label.disabledForeground")
                    : SwingTheme.color(kind == Kind.RUN ? "green"
                    : kind == Kind.CHEVRON_DOWN || kind == Kind.CHECK
                    || kind == Kind.CLOSE && !(component instanceof javax.swing.AbstractButton button && button.getModel().isRollover())
                    ? "secondary" : "icon"));
            if (IdeaIconShapes.paint(kind, g)) return;
            switch (kind) {
                case INFO -> {
                    // 复用 resources/icons/info-circle.svg 的圆圈 + i 轮廓。
                    g.drawOval(1, 1, 14, 14);
                    g.fill(new java.awt.geom.Ellipse2D.Double(7.25, 4.5, 1.5, 1.5));
                    g.fill(new java.awt.geom.RoundRectangle2D.Double(7.375, 7, 1.25, 4.5, .25, .25));
                }
                case CHEVRON_DOWN -> {
                    // IDEA expui/general/chevronDown.svg：16px、1px 圆角描边。
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(4.5, 6.25); arrow.lineTo(8, 9.75); arrow.lineTo(11.5, 6.25);
                    g.draw(arrow);
                }
                case SEARCH -> { g.drawOval(2, 2, 9, 9); g.drawLine(10, 10, 14, 14); }
                case MORE -> { for (int i = 3; i <= 13; i += 5) g.fillOval(7, i - 1, 2, 2); }
                case HIDE -> g.drawLine(3, 8, 13, 8);
                case FOCUS -> { g.drawOval(2, 2, 12, 12); g.drawOval(6, 6, 4, 4); g.drawLine(8, 0, 8, 3); g.drawLine(8, 13, 8, 15); g.drawLine(0, 8, 3, 8); g.drawLine(13, 8, 15, 8); }
                case EXPAND_ALL -> { g.drawLine(8, 8, 8, 1); g.drawLine(5, 4, 8, 1); g.drawLine(11, 4, 8, 1); g.drawLine(8, 8, 8, 15); g.drawLine(5, 12, 8, 15); g.drawLine(11, 12, 8, 15); }
                case COLLAPSE_ALL -> { g.drawLine(8, 1, 8, 8); g.drawLine(5, 5, 8, 8); g.drawLine(11, 5, 8, 8); g.drawLine(8, 15, 8, 8); g.drawLine(5, 11, 8, 8); g.drawLine(11, 11, 8, 8); }
                case DOCK -> { g.drawRoundRect(2, 2, 12, 12, 2, 2); g.drawLine(2, 10, 14, 10); }
                case PIN -> {
                    // Lucide pin.svg (ISC)，按原 24px 几何缩放；出处及许可见 third-party/lucide-LICENSE.txt。
                    g.scale(2.0 / 3, 2.0 / 3);
                    g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    if (component instanceof javax.swing.AbstractButton button && button.isSelected() && component.isEnabled())
                        g.setColor(javax.swing.UIManager.getColor("Component.focusColor"));
                    Path2D pin = new Path2D.Double();
                    pin.moveTo(9, 10.76);
                    pin.curveTo(9, 11.52, 8.57, 12.21, 7.89, 12.55);
                    pin.lineTo(6.11, 13.45);
                    pin.curveTo(5.43, 13.79, 5, 14.48, 5, 15.24);
                    pin.lineTo(5, 16); pin.quadTo(5, 17, 6, 17);
                    pin.lineTo(18, 17); pin.quadTo(19, 17, 19, 16);
                    pin.lineTo(19, 15.24); pin.curveTo(19, 14.48, 18.57, 13.79, 17.89, 13.45);
                    pin.lineTo(16.11, 12.55); pin.curveTo(15.43, 12.21, 15, 11.52, 15, 10.76);
                    pin.lineTo(15, 7); pin.quadTo(15, 6, 16, 6);
                    pin.curveTo(17.105, 6, 18, 5.105, 18, 4);
                    pin.curveTo(18, 2.895, 17.105, 2, 16, 2);
                    pin.lineTo(8, 2); pin.curveTo(6.895, 2, 6, 2.895, 6, 4);
                    pin.curveTo(6, 5.105, 6.895, 6, 8, 6);
                    pin.quadTo(9, 6, 9, 7); pin.closePath();
                    g.draw(pin); g.drawLine(12, 17, 12, 22);
                }
                case MENU -> { for (int i = 4; i <= 12; i += 4) g.drawLine(3, i, 13, i); }
                case MONITOR -> { g.drawRoundRect(1, 2, 14, 11, 2, 2); g.drawLine(4, 10, 4, 7); g.drawLine(8, 10, 8, 5); g.drawLine(12, 10, 12, 8); }
                case SPLIT_RIGHT, SPLIT_DOWN -> {
                    // IDEA expui/general/splitVertically、splitHorizontally.svg 的 16px 几何。
                    g.draw(new java.awt.geom.RoundRectangle2D.Double(2.5, 2.5, 11, 11, 3, 3));
                    if (kind == Kind.SPLIT_RIGHT) g.drawLine(8, 3, 8, 13); else g.drawLine(3, 8, 13, 8);
                }
                case ADD -> { g.drawLine(8, 3, 8, 13); g.drawLine(3, 8, 13, 8); }
                case CLOSE -> { g.drawLine(4, 4, 12, 12); g.drawLine(12, 4, 4, 12); }
                case CHECK -> {
                    Path2D check = new Path2D.Double();
                    check.moveTo(2.5, 8.25); check.lineTo(6, 11.75); check.lineTo(13.5, 4.25);
                    g.draw(check);
                }
                case RUN -> { Path2D path = new Path2D.Double(); path.moveTo(4, 2); path.lineTo(13, 8); path.lineTo(4, 14); path.closePath(); g.draw(path); }
                case SERVER -> { g.drawRoundRect(2, 2, 12, 5, 2, 2); g.drawRoundRect(2, 9, 12, 5, 2, 2); g.fillOval(4, 4, 2, 2); g.fillOval(4, 11, 2, 2); }
                case FOLDER, PROJECT -> { g.drawLine(2, 3, 6, 3); g.drawLine(6, 3, 8, 5); g.drawLine(8, 5, 14, 5); g.drawLine(14, 5, 14, 13); g.drawLine(14, 13, 2, 13); g.drawLine(2, 13, 2, 3); }
                case PLUGIN -> {
                    // Outline of a plugin tile with two connector notches.
                    g.drawRoundRect(3, 3, 10, 10, 2, 2);
                    g.drawLine(6, 1, 6, 4); g.drawLine(10, 1, 10, 4);
                    g.drawLine(6, 12, 6, 15); g.drawLine(10, 12, 10, 15);
                    g.drawLine(1, 6, 4, 6); g.drawLine(1, 10, 4, 10);
                    g.drawLine(12, 6, 15, 6); g.drawLine(12, 10, 15, 10);
                }
                case TERMINAL -> { g.drawRoundRect(1, 2, 14, 12, 2, 2); g.drawLine(4, 5, 7, 8); g.drawLine(7, 8, 4, 11); g.drawLine(9, 11, 12, 11); }
                case REFRESH -> { g.drawArc(3, 3, 10, 10, 30, 290); g.drawLine(12, 2, 13, 6); g.drawLine(13, 6, 9, 6); }
                case SETTINGS -> { g.drawOval(3, 3, 10, 10); g.drawOval(6, 6, 4, 4); for (int i = 0; i < 8; i++) { g.drawLine(8, 1, 8, 3); g.rotate(Math.PI / 4, 8, 8); } }
                case EDIT -> { g.drawLine(3, 12, 12, 3); g.drawLine(5, 14, 14, 5); g.drawLine(3, 12, 2, 15); g.drawLine(2, 15, 5, 14); g.drawLine(12, 3, 14, 5); }
                case DELETE -> { g.drawLine(3, 4, 13, 4); g.drawLine(6, 2, 10, 2); g.drawRect(4, 5, 8, 9); g.drawLine(7, 7, 7, 12); g.drawLine(10, 7, 10, 12); }
            }
        } finally { g.dispose(); }
    }
}
