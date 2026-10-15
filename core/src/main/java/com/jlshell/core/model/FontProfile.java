package com.jlshell.core.model;

/**
 * 终端英文字体、中文字体、字号及行距配置。
 * family 继续作为终端网格的主字体；chineseFamily 为空表示按系统自动选择中文字体。
 * 保留四参数构造器，兼容既有调用与旧配置。
 */
public record FontProfile(
        String family,
        double size,
        boolean ligaturesEnabled,
        double lineSpacing,
        String chineseFamily
) {

    public FontProfile(String family, double size, boolean ligaturesEnabled, double lineSpacing) {
        this(family, size, ligaturesEnabled, lineSpacing, "");
    }

    public FontProfile {
        chineseFamily = chineseFamily == null ? "" : chineseFamily.strip();
        if (family == null || family.isBlank()) {
            throw new IllegalArgumentException("family must not be blank");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (lineSpacing < 0) {
            throw new IllegalArgumentException("lineSpacing must not be negative");
        }
    }
}
