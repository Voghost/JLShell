// Copyright 2000-2022 JetBrains s.r.o. and contributors. Apache-2.0.
// Java adaptation of platform/platform-api/src/com/intellij/ui/popup/list/SelectablePanel.kt.
package com.jlshell.ui.swing.idea;

import javax.swing.JPanel;
import java.awt.*;
import java.util.EnumSet;

/** IDEA 的选中区域绘制组件；移除 JBUI 依赖，逻辑坐标由 AWT HiDPI 变换缩放。 */
public class SelectablePanel extends JPanel {
    public enum Side { TOP, BOTTOM, LEFT, RIGHT }
    public Color selectionColor;
    public int selectionArc = 6;
    public Insets selectionInsets = new Insets(0, 0, 0, 0);
    public EnumSet<Side> selectionArcCorners = EnumSet.allOf(Side.class);
    public Integer preferredWidth;
    public Integer preferredHeight;

    public static SelectablePanel wrap(Component component, Color background) {
        var result = new SelectablePanel();
        result.setBackground(background);
        result.setLayout(new BorderLayout());
        result.add(component, BorderLayout.CENTER);
        return result;
    }

    @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(preferredWidth == null ? size.width : preferredWidth,
                preferredHeight == null ? size.height : preferredHeight);
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (isOpaque() && getBackground() != null) {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            }
            if (selectionColor == null || getBackground().equals(selectionColor)) return;
            g.setColor(selectionColor);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = selectionInsets.left, y = selectionInsets.top;
            int w = getWidth() - x - selectionInsets.right;
            int h = getHeight() - y - selectionInsets.bottom;
            if (selectionArc != 0 && selectionArcCorners.size() != Side.values().length) {
                g.clipRect(x, y, w, h);
                x -= selectionArc; y -= selectionArc;
                w += 2 * selectionArc; h += 2 * selectionArc;
                for (Side side : selectionArcCorners) {
                    switch (side) {
                        case TOP -> { y += selectionArc; h -= selectionArc; }
                        case BOTTOM -> h -= selectionArc;
                        case LEFT -> { x += selectionArc; w -= selectionArc; }
                        case RIGHT -> w -= selectionArc;
                    }
                }
            }
            g.fillRoundRect(x, y, w, h, selectionArc, selectionArc);
        } finally { g.dispose(); }
    }
}
