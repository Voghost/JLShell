package com.jlshell.terminal.support;

import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import java.util.function.Function;

/**
 * 对 JediTermWidget 的轻量扩展。
 * 主要用于暴露刷新字体/主题的入口。
 */
public class JlshellJediTermWidget extends JediTermWidget {

    private static final ThreadLocal<JlshellSettingsProvider> CONSTRUCTION_SETTINGS = new ThreadLocal<>();
    private static final ThreadLocal<Function<String, String>> CONSTRUCTION_I18N = new ThreadLocal<>();

    private final JlshellSettingsProvider settingsProvider;
    private RefreshableTerminalPanel terminalPanel;

    private JlshellJediTermWidget(int columns, int rows, JlshellSettingsProvider settingsProvider) {
        super(columns, rows, settingsProvider);
        this.settingsProvider = settingsProvider;
    }

    public static JlshellJediTermWidget create(int columns, int rows, JlshellSettingsProvider settingsProvider,
                                               Function<String, String> i18n) {
        CONSTRUCTION_SETTINGS.set(settingsProvider);
        CONSTRUCTION_I18N.set(i18n);
        try {
            return new JlshellJediTermWidget(columns, rows, settingsProvider);
        } finally {
            CONSTRUCTION_SETTINGS.remove();
            CONSTRUCTION_I18N.remove();
        }
    }

    @Override
    protected StyleState createDefaultStyle() {
        StyleState styleState = new StyleState();
        styleState.setDefaultStyle(activeSettingsProvider().defaultTextStyle());
        return styleState;
    }

    @Override
    protected TerminalPanel createTerminalPanel(
            SettingsProvider settingsProvider,
            StyleState styleState,
            TerminalTextBuffer terminalTextBuffer
    ) {
        JlshellSettingsProvider sp = CONSTRUCTION_SETTINGS.get();
        Function<String, String> i18n = CONSTRUCTION_I18N.get();
        this.terminalPanel = new RefreshableTerminalPanel(settingsProvider, terminalTextBuffer, styleState, sp, i18n);
        return terminalPanel;
    }

    @Override
    protected JediTerminal createTerminal(
            TerminalDisplay terminalDisplay,
            TerminalTextBuffer terminalTextBuffer,
            StyleState styleState
    ) {
        styleState.setDefaultStyle(activeSettingsProvider().defaultTextStyle());
        return super.createTerminal(terminalDisplay, terminalTextBuffer, styleState);
    }

    public void refreshVisuals() {
        java.awt.Color bg = settingsProvider.backgroundColor();
        java.awt.Color fg = settingsProvider.foregroundColor();
        // 设置 widget 自身背景，消除白色边框
        setBackground(bg);
        setOpaque(true);
        if (terminalPanel != null) {
            terminalPanel.setBackground(bg);
            terminalPanel.setForeground(fg);
            terminalPanel.setOpaque(true);
            terminalPanel.refreshVisuals();
        }
        getTerminalPanel().setBackground(bg);
        getTerminalPanel().setForeground(fg);
        getTerminalPanel().revalidate();
        getTerminalPanel().repaint();
        revalidate();
        repaint();
    }

    private JlshellSettingsProvider activeSettingsProvider() {
        if (settingsProvider != null) {
            return settingsProvider;
        }
        JlshellSettingsProvider constructingProvider = CONSTRUCTION_SETTINGS.get();
        if (constructingProvider != null) {
            return constructingProvider;
        }
        throw new IllegalStateException("JediTerm settings provider is not available during widget initialization");
    }
}
