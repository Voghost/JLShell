package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutRegistry;
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
    private final ShortcutRegistry shortcutRegistry;

    // 可变的 KeyStroke 列表引用，用于 refreshActions() 时原地更新
    private final List<KeyStroke> copyStrokes;
    private final List<KeyStroke> pasteStrokes;
    private final List<KeyStroke> clearBufferStrokes;
    private final List<KeyStroke> findStrokes;
    private final List<KeyStroke> selectAllStrokes;
    private final List<KeyStroke> pageUpStrokes;
    private final List<KeyStroke> pageDownStrokes;
    private final List<KeyStroke> lineUpStrokes;
    private final List<KeyStroke> lineDownStrokes;

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme) {
        this(fontProfile, colorScheme, TerminalRuntimeSettings.defaults(), null);
    }

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme,
                                   TerminalRuntimeSettings runtimeSettings) {
        this(fontProfile, colorScheme, runtimeSettings, null);
    }

    public JlshellSettingsProvider(FontProfile fontProfile, TerminalColorScheme colorScheme,
                                   TerminalRuntimeSettings runtimeSettings,
                                   ShortcutRegistry shortcutRegistry) {
        this.fontProfile = new AtomicReference<>(fontProfile);
        this.colorScheme = new AtomicReference<>(colorScheme);
        this.colorPalette = new AtomicReference<>(buildPalette(colorScheme));
        this.runtimeSettings = new AtomicReference<>(
                runtimeSettings == null ? TerminalRuntimeSettings.defaults() : runtimeSettings);
        this.shortcutRegistry = shortcutRegistry;

        // 初始化可变 KeyStroke 列表
        this.copyStrokes = buildKeyStrokes("terminal.copy");
        this.pasteStrokes = buildKeyStrokes("terminal.paste");
        this.clearBufferStrokes = buildKeyStrokes("terminal.clearBuffer");
        this.findStrokes = buildKeyStrokes("terminal.find");
        this.selectAllStrokes = buildKeyStrokes("terminal.selectAll");
        this.pageUpStrokes = buildKeyStrokes("terminal.pageUp");
        this.pageDownStrokes = buildKeyStrokes("terminal.pageDown");
        this.lineUpStrokes = buildKeyStrokes("terminal.lineUp");
        this.lineDownStrokes = buildKeyStrokes("terminal.lineDown");
    }

    /**
     * 从 ShortcutRegistry 刷新所有终端快捷键的 KeyStroke 列表。
     * 因为 TerminalActionPresentation 存储的是列表引用，原地修改即可生效。
     */
    public void refreshActions() {
        if (shortcutRegistry == null) return;
        refreshKeyStrokes(copyStrokes, "terminal.copy");
        refreshKeyStrokes(pasteStrokes, "terminal.paste");
        refreshKeyStrokes(clearBufferStrokes, "terminal.clearBuffer");
        refreshKeyStrokes(findStrokes, "terminal.find");
        refreshKeyStrokes(selectAllStrokes, "terminal.selectAll");
        refreshKeyStrokes(pageUpStrokes, "terminal.pageUp");
        refreshKeyStrokes(pageDownStrokes, "terminal.pageDown");
        refreshKeyStrokes(lineUpStrokes, "terminal.lineUp");
        refreshKeyStrokes(lineDownStrokes, "terminal.lineDown");
    }

    private List<KeyStroke> buildKeyStrokes(String id) {
        List<KeyStroke> strokes = new ArrayList<>();
        if (shortcutRegistry != null) {
            String primary = shortcutRegistry.getEffectivePrimary(id);
            String secondary = shortcutRegistry.getEffectiveSecondary(id);
            if (primary != null) strokes.add(ShortcutConverter.toKeyStroke(primary));
            if (secondary != null) strokes.add(ShortcutConverter.toKeyStroke(secondary));
        }
        return strokes;
    }

    private void refreshKeyStrokes(List<KeyStroke> target, String id) {
        target.clear();
        String primary = shortcutRegistry.getEffectivePrimary(id);
        String secondary = shortcutRegistry.getEffectiveSecondary(id);
        if (primary != null) target.add(ShortcutConverter.toKeyStroke(primary));
        if (secondary != null) target.add(ShortcutConverter.toKeyStroke(secondary));
    }

    // ── 快捷键覆写 ──

    @Override
    public TerminalActionPresentation getCopyActionPresentation() {
        if (shortcutRegistry == null) return super.getCopyActionPresentation();
        return new TerminalActionPresentation("Copy", copyStrokes);
    }

    @Override
    public TerminalActionPresentation getPasteActionPresentation() {
        if (shortcutRegistry == null) {
            // 保留原有 Shift+Insert 逻辑
            List<KeyStroke> strokes = new ArrayList<>(super.getPasteActionPresentation().getKeyStrokes());
            strokes.add(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK));
            return new TerminalActionPresentation("Paste", strokes);
        }
        return new TerminalActionPresentation("Paste", pasteStrokes);
    }

    @Override
    public TerminalActionPresentation getClearBufferActionPresentation() {
        if (shortcutRegistry == null) return super.getClearBufferActionPresentation();
        return new TerminalActionPresentation("Clear Buffer", clearBufferStrokes);
    }

    @Override
    public TerminalActionPresentation getFindActionPresentation() {
        if (shortcutRegistry == null) return super.getFindActionPresentation();
        return new TerminalActionPresentation("Find", findStrokes);
    }

    @Override
    public TerminalActionPresentation getSelectAllActionPresentation() {
        if (shortcutRegistry == null) return super.getSelectAllActionPresentation();
        return new TerminalActionPresentation("Select All", selectAllStrokes);
    }

    @Override
    public TerminalActionPresentation getPageUpActionPresentation() {
        if (shortcutRegistry == null) return super.getPageUpActionPresentation();
        return new TerminalActionPresentation("Page Up", pageUpStrokes);
    }

    @Override
    public TerminalActionPresentation getPageDownActionPresentation() {
        if (shortcutRegistry == null) return super.getPageDownActionPresentation();
        return new TerminalActionPresentation("Page Down", pageDownStrokes);
    }

    @Override
    public TerminalActionPresentation getLineUpActionPresentation() {
        if (shortcutRegistry == null) return super.getLineUpActionPresentation();
        return new TerminalActionPresentation("Line Up", lineUpStrokes);
    }

    @Override
    public TerminalActionPresentation getLineDownActionPresentation() {
        if (shortcutRegistry == null) return super.getLineDownActionPresentation();
        return new TerminalActionPresentation("Line Down", lineDownStrokes);
    }

    // ── 以下为原有方法，保持不变 ──

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
