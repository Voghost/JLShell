// Copyright 2000-2021 JetBrains s.r.o. Apache-2.0.
// Java/Swing adaptation of platform/platform-api/src/com/intellij/ui/GroupHeaderSeparator.java.
package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.*;

/** 沿用 IDEA 的独立分隔线与标题几何，空标题只占 4+1+4 像素。 */
final class IdeaGroupHeaderSeparator extends JComponent {
    private final String caption;
    private final boolean hideLine;
    private final Insets labelInsets = new Insets(3, 16, 3, 16);
    private final Insets lineInsets = new Insets(4, 8, 4, 8);

    IdeaGroupHeaderSeparator(String caption, boolean hideLine, Font font) {
        this.caption = caption == null || caption.isBlank() ? null : caption;
        this.hideLine = hideLine;
        setFont(font.deriveFont(Font.BOLD, Math.max(11, font.getSize() - 1)));
    }
    @Override public Dimension getPreferredSize() {
        int height = hideLine ? 0 : lineInsets.top + lineInsets.bottom + 1;
        if (caption != null) height += getFontMetrics(getFont()).getHeight() + labelInsets.top + labelInsets.bottom;
        return new Dimension(0, height);
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Rectangle bounds = new Rectangle(getWidth(), getHeight());
            if (!hideLine) {
                g.setColor(SwingTheme.color("popupSeparator"));
                g.fillRect(lineInsets.left, lineInsets.top, getWidth() - lineInsets.left - lineInsets.right, 1);
                int lineHeight = lineInsets.top + lineInsets.bottom + 1;
                bounds.y += lineHeight; bounds.height -= lineHeight;
            }
            if (caption != null) {
                bounds.x += labelInsets.left; bounds.width -= labelInsets.left + labelInsets.right;
                bounds.y += labelInsets.top; bounds.height -= labelInsets.top + labelInsets.bottom;
                Rectangle icon = new Rectangle(), text = new Rectangle();
                FontMetrics metrics = g.getFontMetrics();
                String label = SwingUtilities.layoutCompoundLabel(metrics, caption, null,
                        SwingUtilities.CENTER, SwingUtilities.LEFT, SwingUtilities.CENTER, SwingUtilities.LEFT,
                        bounds, icon, text, 0);
                g.setColor(SwingTheme.color("secondary"));
                g.drawString(label, text.x, text.y + metrics.getAscent());
            }
        } finally { g.dispose(); }
    }
}
