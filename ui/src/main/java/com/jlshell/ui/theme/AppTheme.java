package com.jlshell.ui.theme;

import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 应用主题定义。
 */
public enum AppTheme {
    DARK("/css/dark-theme.css", TerminalColorScheme.dark()),
    LIGHT("/css/light-theme.css", TerminalColorScheme.light());

    private final String stylesheet;
    private final TerminalColorScheme terminalColorScheme;

    AppTheme(String stylesheet, TerminalColorScheme terminalColorScheme) {
        this.stylesheet = stylesheet;
        this.terminalColorScheme = terminalColorScheme;
    }

    public String stylesheet() {
        return stylesheet;
    }

    public TerminalColorScheme terminalColorScheme() {
        return terminalColorScheme;
    }
}
