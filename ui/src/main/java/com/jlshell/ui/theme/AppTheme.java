package com.jlshell.ui.theme;

/**
 * 应用主题定义。
 * 仅控制 JavaFX UI 样式表；终端配色方案独立管理。
 */
public enum AppTheme {
    DARK("/css/dark-theme.css"),
    LIGHT("/css/light-theme.css");

    private final String stylesheet;

    AppTheme(String stylesheet) {
        this.stylesheet = stylesheet;
    }

    public String stylesheet() {
        return stylesheet;
    }
}
