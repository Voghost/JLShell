package com.jlshell.terminal.model;

import java.awt.Color;

/**
 * 终端颜色方案。
 * 设计为普通模型而非枚举，便于后续接入自定义主题。
 * 包含 16 色 ANSI 调色板、光标色、特殊颜色和透明度。
 */
public record TerminalColorScheme(
        String name,
        Color background,
        Color foreground,
        Color cursorColor,
        Color selectionBackground,
        Color selectionForeground,
        Color hyperlinkColor,
        Color searchMatchBackground,
        Color searchMatchForeground,
        Color black,
        Color red,
        Color green,
        Color yellow,
        Color blue,
        Color purple,
        Color cyan,
        Color white,
        Color brightBlack,
        Color brightRed,
        Color brightGreen,
        Color brightYellow,
        Color brightBlue,
        Color brightPurple,
        Color brightCyan,
        Color brightWhite,
        double opacity
) {

    public TerminalColorScheme {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (background == null || foreground == null || cursorColor == null
                || selectionBackground == null || selectionForeground == null
                || hyperlinkColor == null || searchMatchBackground == null || searchMatchForeground == null
                || black == null || red == null || green == null || yellow == null
                || blue == null || purple == null || cyan == null || white == null
                || brightBlack == null || brightRed == null || brightGreen == null || brightYellow == null
                || brightBlue == null || brightPurple == null || brightCyan == null || brightWhite == null) {
            throw new IllegalArgumentException("terminal color scheme colors must not be null");
        }
        if (opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("opacity must be between 0.0 and 1.0");
        }
    }

    /**
     * 返回 16 色 ANSI 调色板，索引 0-15 对应标准 ANSI 顺序。
     */
    public Color[] ansiColors() {
        return new Color[]{
                black, red, green, yellow, blue, purple, cyan, white,
                brightBlack, brightRed, brightGreen, brightYellow,
                brightBlue, brightPurple, brightCyan, brightWhite
        };
    }

    public static TerminalColorScheme dark() {
        return new TerminalColorScheme(
                "dark",
                new Color(0x1e, 0x1f, 0x22),
                new Color(0xe8, 0xea, 0xed),
                new Color(0xe8, 0xea, 0xed),
                new Color(0x2d, 0x5f, 0xa3),
                new Color(0xff, 0xff, 0xff),
                new Color(0x4d, 0x9c, 0xf8),
                new Color(0xe0, 0xb1, 0x2d),
                new Color(0x1e, 0x1f, 0x22),
                // 16 ANSI colors — XTERM defaults
                new Color(0x00, 0x00, 0x00),
                new Color(0xcd, 0x00, 0x00),
                new Color(0x00, 0xcd, 0x00),
                new Color(0xcd, 0xcd, 0x00),
                new Color(0x1e, 0x90, 0xff),
                new Color(0xcd, 0x00, 0xcd),
                new Color(0x00, 0xcd, 0xcd),
                new Color(0xe5, 0xe5, 0xe5),
                new Color(0x4c, 0x4c, 0x4c),
                new Color(0xff, 0x00, 0x00),
                new Color(0x00, 0xff, 0x00),
                new Color(0xff, 0xff, 0x00),
                new Color(0x46, 0x82, 0xb4),
                new Color(0xff, 0x00, 0xff),
                new Color(0x00, 0xff, 0xff),
                new Color(0xff, 0xff, 0xff),
                1.0
        );
    }

    public static TerminalColorScheme light() {
        return new TerminalColorScheme(
                "light",
                new Color(0xf8, 0xfa, 0xfc),
                new Color(0x1f, 0x29, 0x37),
                new Color(0x1f, 0x29, 0x37),
                new Color(0xbf, 0xdb, 0xfe),
                new Color(0x11, 0x18, 0x27),
                new Color(0x03, 0x66, 0xd6),
                new Color(0xfd, 0xe0, 0x47),
                new Color(0x11, 0x18, 0x27),
                // 16 ANSI colors — XTERM defaults
                new Color(0x00, 0x00, 0x00),
                new Color(0xcd, 0x00, 0x00),
                new Color(0x00, 0xcd, 0x00),
                new Color(0xcd, 0xcd, 0x00),
                new Color(0x1e, 0x90, 0xff),
                new Color(0xcd, 0x00, 0xcd),
                new Color(0x00, 0xcd, 0xcd),
                new Color(0xe5, 0xe5, 0xe5),
                new Color(0x4c, 0x4c, 0x4c),
                new Color(0xff, 0x00, 0x00),
                new Color(0x00, 0xff, 0x00),
                new Color(0xff, 0xff, 0x00),
                new Color(0x46, 0x82, 0xb4),
                new Color(0xff, 0x00, 0xff),
                new Color(0x00, 0xff, 0xff),
                new Color(0xff, 0xff, 0xff),
                1.0
        );
    }
}
