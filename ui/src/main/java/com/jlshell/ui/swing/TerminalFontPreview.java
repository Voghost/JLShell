package com.jlshell.ui.swing;

import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.support.TerminalFontResolver;
import javax.swing.*;
import java.awt.*;

/** Uses terminal cell metrics and the same CJK resolver instead of JTextArea's fixed line height. */
final class TerminalFontPreview extends JComponent {
    private FontProfile profile;
    private static final String[] LINES = {
            "JLShell · SSH / SFTP", "", "user@server ~ $ ls -la", "drwxr-xr-x  Documents 文档", "-rw-r--r--  README.md 说明",
            "", "abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "0123456789  {} [] ()", "", "你好，世界  中英文混排", "终端连接成功  →  =>  !=  <="
    };
    TerminalFontPreview(FontProfile profile) { setName("settings-terminal-preview"); setProfile(profile); setOpaque(false); }
    void setProfile(FontProfile profile) {
        this.profile = profile;
        putClientProperty("terminal.previewProfile", profile);
        putClientProperty("terminal.previewLineHeight", lineHeight());
        revalidate(); repaint();
    }
    private Font englishFont() { return new Font(profile.family(), Font.PLAIN, Math.max(1, (int)Math.round(profile.size()))); }
    private int lineHeight() { return Math.max(1, (int)Math.ceil(getFontMetrics(englishFont()).getHeight() * profile.lineSpacing())); }
    public Dimension getPreferredSize() { return new Dimension(getFontMetrics(englishFont()).charWidth('W') * 36, lineHeight() * LINES.length + 8); }
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(UIManager.getColor("Label.foreground"));
            Font base = englishFont();
            int width = getFontMetrics(base).charWidth('W'), height = lineHeight();
            for (int row = 0; row < LINES.length; row++) {
                int x = 0;
                for (int codePoint : LINES[row].codePoints().toArray()) {
                    Font font = TerminalFontResolver.resolve(profile, codePoint, Font.PLAIN);
                    g.setFont(font);
                    int baseline = (row + 1) * height - (height - getFontMetrics(base).getHeight()) / 2 - getFontMetrics(font).getDescent();
                    g.drawString(new String(Character.toChars(codePoint)), x, baseline);
                    x += width * (TerminalFontResolver.isChinese(codePoint) ? 2 : 1);
                }
            }
        } finally { g.dispose(); }
    }
}
