package com.jlshell.core.model;

/**
 * 终端逻辑尺寸与像素尺寸。
 * JediTerm 调整窗口大小时会用到这两个维度。
 */
public record TerminalSize(
        int columns,
        int rows,
        int widthPixels,
        int heightPixels
) {

    public TerminalSize {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("columns and rows must be positive");
        }
        if (widthPixels < 0 || heightPixels < 0) {
            throw new IllegalArgumentException("pixel dimensions must not be negative");
        }
    }

    public static TerminalSize defaultSize() {
        return new TerminalSize(120, 40, 0, 0);
    }
}
