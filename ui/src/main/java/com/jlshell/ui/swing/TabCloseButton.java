package com.jlshell.ui.swing;

import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.IntConsumer;

/** IDEA tab close affordance: quiet glyph, circular hover target, and no native button bezel. */
final class TabCloseButton extends JButton {
    TabCloseButton(IntConsumer close) {
        super(new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE));
        setName("workspace.tab.close");
        getAccessibleContext().setAccessibleName("Close tab");
        setPreferredSize(new Dimension(20, 20));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        setFocusable(false);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        addActionListener(event -> close.accept(event.getModifiers()));
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed())) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color(getModel().isPressed() ? "headerPressed" : "headerHover"));
                int inset = 1;
                g.fillRoundRect(inset, inset, getWidth() - inset * 2, getHeight() - inset * 2, 18, 18);
            } finally {
                g.dispose();
            }
        }
        super.paintComponent(graphics);
    }
}
