package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import javax.swing.KeyStroke;

/**
 * 可变终端设置提供器。
 * 后续字体或主题切换时，不必重建整个 SSH 会话。
 */
public class JlshellSettingsProvider extends DefaultSettingsProvider {

    private final AtomicReference<FontProfile> fontProfile;
    private final AtomicReference<TerminalColorScheme> colorScheme;
    private final AtomicReference<ColorPalette> colorPalette;
    private final AtomicReference<TerminalRuntimeSettings> runtimeSettings;

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme) {
        this(fontProfile, colorScheme, TerminalRuntimeSettings.defaults());
    }

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme,
                                   TerminalRuntimeSettings runtimeSettings) {
        this.fontProfile = new AtomicReference<>(fontProfile);
        this.colorScheme = new AtomicReference<>(colorScheme);
        this.colorPalette = new AtomicReference<>(buildPalette(colorScheme));
        this.runtimeSettings = new AtomicReference<>(
                runtimeSettings == null ? TerminalRuntimeSettings.defaults() : runtimeSettings);
    }

    public void updateFontProfile(FontProfile updatedFontProfile) {
        fontProfile.set(updatedFontProfile);
    }

    public void updateColorScheme(TerminalColorScheme updatedColorScheme) {
        colorScheme.set(updatedColorScheme);
        colorPalette.set(buildPalette(updatedColorScheme));
    }

    public Color backgroundColor() {
        return colorScheme.get().background();
    }

    public Color foregroundColor() {
        return colorScheme.get().foreground();
    }

    public double opacity() {
        return colorScheme.get().opacity();
    }

    public TextStyle defaultTextStyle() {
        return new TextStyle(toTerminalColor(colorScheme.get().foreground()), toTerminalColor(colorScheme.get().background()));
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
        return colorPalette.get();
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
    public TerminalActionPresentation getPasteActionPresentation() {
        List<KeyStroke> strokes = new java.util.ArrayList<>(super.getPasteActionPresentation().getKeyStrokes());
        strokes.add(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK));
        return new TerminalActionPresentation("Paste", strokes);
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
        return true;
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
        return runtimeSettings.get().scrollbackLines();
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

    private static ColorPalette buildPalette(TerminalColorScheme scheme) {
        java.awt.Color[] ansi = scheme.ansiColors();
        com.jediterm.core.Color[] jediColors = new com.jediterm.core.Color[16];
        for (int i = 0; i < 16; i++) {
            jediColors[i] = new com.jediterm.core.Color(ansi[i].getRed(), ansi[i].getGreen(), ansi[i].getBlue());
        }
        return new ColorPalette() {
            @Override
            protected com.jediterm.core.Color getForegroundByColorIndex(int colorIndex) {
                return jediColors[colorIndex];
            }

            @Override
            protected com.jediterm.core.Color getBackgroundByColorIndex(int colorIndex) {
                return jediColors[colorIndex];
            }
        };
    }
}
