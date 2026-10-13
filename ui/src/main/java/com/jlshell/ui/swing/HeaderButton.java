package com.jlshell.ui.swing;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** 对照 IDEA ToolbarComboWidgetUI：透明底色上绘制统一的圆角 hover。 */
class HeaderButton extends JButton {
    HeaderButton(String text) { super(text); configure(); }
    HeaderButton(Icon icon) { super(icon); configure(); }

    private void configure() {
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setRolloverEnabled(true);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || isSelected())) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color(getModel().isPressed() || isSelected() ? "headerPressed" : "headerHover"));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            } finally { g.dispose(); }
        }
        super.paintComponent(graphics);
    }
}
