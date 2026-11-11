package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalColorScheme;
import javax.swing.JComponent;
import java.awt.*;

/** Native terminal sample with ANSI colors and an opaque text layer. */
public final class ColorSchemePreview extends JComponent {
    private TerminalColorScheme scheme = TerminalColorScheme.dark();
    public ColorSchemePreview() { setPreferredSize(new Dimension(420, 114)); }
    public void update(TerminalColorScheme value) { if (value != null) { scheme = value; repaint(); } }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive((float) scheme.opacity()));
            g.setColor(scheme.background()); g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g.setComposite(AlphaComposite.SrcOver);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            int x = fragment(g, "user@host ~ $ ", scheme.foreground(), 12, 22);
            fragment(g, "ls", scheme.green(), x, 22);
            x = fragment(g, "Documents/  ", scheme.blue(), 12, 40);
            fragment(g, "README.md", scheme.foreground(), x, 40);
            x = fragment(g, "INFO connected  ", scheme.cyan(), 12, 58);
            x = fragment(g, "WARN retry  ", scheme.yellow(), x, 58);
            fragment(g, "ERROR timeout", scheme.red(), x, 58);
            Color[] colors = scheme.ansiColors();
            for (int index = 0; index < colors.length; index++) {
                g.setColor(colors[index]); g.fillRect(12 + (index % 8) * 20, 70 + (index / 8) * 17, 17, 14);
            }
        } finally { g.dispose(); }
    }

    private static int fragment(Graphics2D g, String text, Color color, int x, int y) {
        g.setColor(color); g.drawString(text, x, y);
        return x + g.getFontMetrics().stringWidth(text);
    }
}
