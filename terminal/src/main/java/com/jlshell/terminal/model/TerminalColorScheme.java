package com.jlshell.terminal.model;

import java.awt.Color;

/**
 * 终端颜色方案。
 * 设计为普通模型而非枚举，便于后续接入自定义主题。
 */
public record TerminalColorScheme(
        String name,
        Color background,
        Color foreground,
        Color selectionBackground,
        Color selectionForeground,
        Color hyperlinkColor,
        Color searchMatchBackground,
        Color searchMatchForeground
) {

    public TerminalColorScheme {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (background == null || foreground == null || selectionBackground == null || selectionForeground == null
                || hyperlinkColor == null || searchMatchBackground == null || searchMatchForeground == null) {
            throw new IllegalArgumentException("terminal color scheme colors must not be null");
        }
    }

    public static TerminalColorScheme dark() {
        return new TerminalColorScheme(
                "dark",
                new Color(0x1e, 0x1f, 0x22),   // 与外部 UI #1e1f22 一致
                new Color(0xdf, 0xe1, 0xe5),   // #dfe1e5
                new Color(0x2d, 0x5f, 0xa3),   // #2d5fa3 选中背景
                new Color(0xff, 0xff, 0xff),   // 选中前景白色
                new Color(0x4d, 0x9c, 0xf8),   // #4d9cf8 超链接
                new Color(0xe0, 0xb1, 0x2d),   // 搜索高亮背景
                new Color(0x1e, 0x1f, 0x22)    // 搜索高亮前景
        );
    }

    public static TerminalColorScheme light() {
        return new TerminalColorScheme(
                "light",
                new Color(0xf8, 0xfa, 0xfc),
                new Color(0x1f, 0x29, 0x37),
                new Color(0xbf, 0xdb, 0xfe),
                new Color(0x11, 0x18, 0x27),
                new Color(0x03, 0x66, 0xd6),
                new Color(0xfd, 0xe0, 0x47),
                new Color(0x11, 0x18, 0x27)
        );
    }
}
