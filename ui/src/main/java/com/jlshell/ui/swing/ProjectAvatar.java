package com.jlshell.ui.swing;

import javax.swing.Icon;
import java.awt.*;

/** ProjectWidgetRenderer 的项目颜色/首字图标，用现有项目名称生成，不增加业务元数据。 */
public record ProjectAvatar(String name, String id, int size) implements Icon {
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] colors = {0x5966e8, 0x438ec5, 0x519d7e, 0xab78c7, 0xc59525, 0xc77556};
            g.setColor(new Color(colors[Math.floorMod(java.util.Objects.toString(id == null ? name : id, "JL").hashCode(), colors.length)]));
            g.fillRoundRect(x, y, size, size, 5, 5);
            String initials = name == null || name.isBlank() ? "JL" : name.codePoints().limit(name.charAt(0) > 127 ? 1 : 2)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().toUpperCase(java.util.Locale.ROOT);
            g.setFont(new Font("SansSerif", Font.BOLD, size / 2));
            g.setColor(Color.WHITE);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(initials, x + (size - metrics.stringWidth(initials)) / 2,
                    y + (size - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally { g.dispose(); }
    }
}
