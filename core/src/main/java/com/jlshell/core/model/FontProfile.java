package com.jlshell.core.model;

/**
 * 终端字体配置抽象。
 * 后续 UI/terminal 模块会基于该模型实现字体切换与持久化。
 */
public record FontProfile(
        String family,
        double size,
        boolean ligaturesEnabled,
        double lineSpacing
) {

    public FontProfile {
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
