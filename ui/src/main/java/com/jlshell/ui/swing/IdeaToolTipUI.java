package com.jlshell.ui.swing;

import com.formdev.flatlaf.ui.FlatToolTipUI;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** FlatLaf text/layout with IDEA's rounded, softly outlined tooltip surface. */
public final class IdeaToolTipUI extends FlatToolTipUI {
    public static ComponentUI createUI(JComponent component) {
        return new IdeaToolTipUI();
    }

    @Override public void installUI(JComponent component) {
        super.installUI(component);
        component.setOpaque(false);
    }

    @Override public void update(Graphics graphics, JComponent component) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = javax.swing.UIManager.getInt("ToolTip.borderCornerRadius") * 2;
            g.setColor(SwingTheme.color("tooltip"));
            g.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, arc, arc);
            g.setColor(SwingTheme.color("tooltipBorder"));
            g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, arc, arc);
        } finally {
            g.dispose();
        }
        paint(graphics, component);
    }
}
