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
    public enum Kind { SERVER, FOLDER, ADD, RUN, REFRESH, SETTINGS, CLOSE, TERMINAL, PROJECT, EDIT, DELETE, SEARCH, MORE, HIDE, DOCK, MENU, MONITOR }
    public int getIconWidth() { return 16; }
    public int getIconHeight() { return 16; }

    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(SwingTheme.color(kind == Kind.RUN ? "green" : "icon"));
            if (IdeaIconShapes.paint(kind, g)) return;
            switch (kind) {
                case SEARCH -> { g.drawOval(2, 2, 9, 9); g.drawLine(10, 10, 14, 14); }
                case MORE -> { for (int i = 3; i <= 13; i += 5) g.fillOval(7, i - 1, 2, 2); }
                case HIDE -> g.drawLine(3, 8, 13, 8);
                case DOCK -> { g.drawRoundRect(2, 2, 12, 12, 2, 2); g.drawLine(2, 10, 14, 10); }
                case MENU -> { for (int i = 4; i <= 12; i += 4) g.drawLine(3, i, 13, i); }
                case MONITOR -> { g.drawRoundRect(1, 2, 14, 11, 2, 2); g.drawLine(4, 10, 4, 7); g.drawLine(8, 10, 8, 5); g.drawLine(12, 10, 12, 8); }
                case ADD -> { g.drawLine(8, 3, 8, 13); g.drawLine(3, 8, 13, 8); }
                case CLOSE -> { g.drawLine(4, 4, 12, 12); g.drawLine(12, 4, 4, 12); }
                case RUN -> { Path2D path = new Path2D.Double(); path.moveTo(4, 2); path.lineTo(13, 8); path.lineTo(4, 14); path.closePath(); g.draw(path); }
                case SERVER -> { g.drawRoundRect(2, 2, 12, 5, 2, 2); g.drawRoundRect(2, 9, 12, 5, 2, 2); g.fillOval(4, 4, 2, 2); g.fillOval(4, 11, 2, 2); }
                case FOLDER, PROJECT -> { g.drawLine(2, 3, 6, 3); g.drawLine(6, 3, 8, 5); g.drawLine(8, 5, 14, 5); g.drawLine(14, 5, 14, 13); g.drawLine(14, 13, 2, 13); g.drawLine(2, 13, 2, 3); }
                case TERMINAL -> { g.drawRoundRect(1, 2, 14, 12, 2, 2); g.drawLine(4, 5, 7, 8); g.drawLine(7, 8, 4, 11); g.drawLine(9, 11, 12, 11); }
                case REFRESH -> { g.drawArc(3, 3, 10, 10, 30, 290); g.drawLine(12, 2, 13, 6); g.drawLine(13, 6, 9, 6); }
                case SETTINGS -> { g.drawOval(3, 3, 10, 10); g.drawOval(6, 6, 4, 4); for (int i = 0; i < 8; i++) { g.drawLine(8, 1, 8, 3); g.rotate(Math.PI / 4, 8, 8); } }
                case EDIT -> { g.drawLine(3, 12, 12, 3); g.drawLine(5, 14, 14, 5); g.drawLine(3, 12, 2, 15); g.drawLine(2, 15, 5, 14); g.drawLine(12, 3, 14, 5); }
                case DELETE -> { g.drawLine(3, 4, 13, 4); g.drawLine(6, 2, 10, 2); g.drawRect(4, 5, 8, 9); g.drawLine(7, 7, 7, 12); g.drawLine(10, 7, 10, 12); }
            }
        } finally { g.dispose(); }
    }
}
