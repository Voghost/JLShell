package com.jlshell.terminal.support;

import com.jlshell.core.model.FontProfile;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Set;

/** Shared by the terminal renderer and its preview so CJK selection follows the same rules. */
public final class TerminalFontResolver {
    private TerminalFontResolver() {}
    private static final Set<String> AVAILABLE = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    public static boolean isChinese(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.BOPOMOFO || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED;
    }
    public static Font resolve(FontProfile profile, int codePoint, int style) {
        int size = Math.max(1, (int) Math.round(profile.size()));
        String family = isChinese(codePoint) ? profile.chineseFamily().isBlank() ? automaticChineseFamily() : profile.chineseFamily() : profile.family();
        Font font = new Font(family, style, size);
        if (font.canDisplay(codePoint)) return font;
        Font fallback = new Font(automaticChineseFamily(), style, size);
        return fallback.canDisplay(codePoint) ? fallback : new Font(Font.DIALOG, style, size);
    }
    public static String automaticChineseFamily() {
        for (String family : new String[]{"PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", "WenQuanYi Micro Hei", "Droid Sans Fallback"})
            if (AVAILABLE.contains(family)) return family;
        return Font.DIALOG;
    }
}
