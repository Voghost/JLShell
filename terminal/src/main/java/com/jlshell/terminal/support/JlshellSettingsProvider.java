package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;

/**
 * 可变终端设置提供器。
 * 后续字体或主题切换时，不必重建整个 SSH 会话。
 */
public class JlshellSettingsProvider extends DefaultSettingsProvider {

    private final AtomicReference<FontProfile> fontProfile;
    private final AtomicReference<TerminalColorScheme> colorScheme;

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme) {
        this.fontProfile = new AtomicReference<>(fontProfile);
        this.colorScheme = new AtomicReference<>(colorScheme);
    }

    public void updateFontProfile(FontProfile updatedFontProfile) {
        fontProfile.set(updatedFontProfile);
    }

    public void updateColorScheme(TerminalColorScheme updatedColorScheme) {
        colorScheme.set(updatedColorScheme);
    }

    public TextStyle defaultTextStyle() {
        return new TextStyle(toTerminalColor(colorScheme.get().foreground()), toTerminalColor(colorScheme.get().background()));
    }

    public Color backgroundColor() {
        return colorScheme.get().background();
    }

    public Color foregroundColor() {
        return colorScheme.get().foreground();
    }

    @Override
    public com.jediterm.terminal.TerminalColor getDefaultBackground() {
        return toTerminalColor(colorScheme.get().background());
    }

    @Override
    public com.jediterm.terminal.TerminalColor getDefaultForeground() {
        return toTerminalColor(colorScheme.get().foreground());
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return ColorPaletteImpl.XTERM_PALETTE;
    }

    @Override
    public Font getTerminalFont() {
        FontProfile profile = fontProfile.get();
        return new Font(profile.family(), Font.PLAIN, Math.max(1, (int) Math.round(profile.size())));
    }

    @Override
    public float getTerminalFontSize() {
        return (float) fontProfile.get().size();
    }

    @Override
    public float getLineSpacing() {
        return (float) fontProfile.get().lineSpacing();
    }

    @Override
    public TextStyle getSelectionColor() {
        TerminalColorScheme scheme = colorScheme.get();
        return new TextStyle(toTerminalColor(scheme.selectionForeground()), toTerminalColor(scheme.selectionBackground()));
    }

    @Override
    public TextStyle getFoundPatternColor() {
        TerminalColorScheme scheme = colorScheme.get();
        return new TextStyle(toTerminalColor(scheme.searchMatchForeground()), toTerminalColor(scheme.searchMatchBackground()));
    }

    @Override
    public TextStyle getHyperlinkColor() {
        TerminalColor foreground = toTerminalColor(colorScheme.get().hyperlinkColor());
        return new TextStyle(foreground, null);
    }

    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
        return HyperlinkStyle.HighlightMode.HOVER;
    }

    @Override
    public boolean altSendsEscape() {
        // macOS 上 Option 键不发送 Escape 前缀，避免干扰密码输入
        return false;
    }

    @Override
    public boolean DECCompatibilityMode() {
        return true;
    }

    @Override
    public boolean useAntialiasing() {
        return true;
    }

    @Override
    public int maxRefreshRate() {
        return 60;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return 10_000;
    }

    @Override
    public boolean audibleBell() {
        return false;
    }

    @Override
    public boolean copyOnSelect() {
        return false;
    }

    @Override
    public boolean pasteOnMiddleMouseClick() {
        return false;
    }

    @Override
    public boolean scrollToBottomOnTyping() {
        return true;
    }

    @Override
    public boolean enableMouseReporting() {
        return true;
    }

    private TerminalColor toTerminalColor(Color color) {
        return TerminalColor.rgb(color.getRed(), color.getGreen(), color.getBlue());
    }
}
