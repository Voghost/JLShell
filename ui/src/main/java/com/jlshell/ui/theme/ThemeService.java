package com.jlshell.ui.theme;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.core.support.MutableValue;
import com.jlshell.plugin.api.ui.ObservableValue;
import com.jlshell.ui.support.UiThread;
import java.util.Locale;

/**
 * 主题管理服务。
 * Swing 界面主题与终端配色方案独立管理。
 *
 * 启动优化：构造时先用 {@link TerminalColorScheme#dark()} 占位，
 * 异步从 registry 解析保存的方案名；解析完成后在 Swing EDT 发布状态。
 * 这样 305 主题 JSON 的解析就不在启动关键路径上了。
 */
public class ThemeService {

    public record UiFont(String family, int size) {}
    private final MutableValue<UiFont> uiFont = new MutableValue<>(null);

    private final MutableValue<AppTheme> currentTheme = new MutableValue<>(AppTheme.DARK);
    private final MutableValue<AccentColor> accentColor = new MutableValue<>(AccentColor.SKY);
    private final MutableValue<TerminalColorScheme> activeColorScheme;
    private final AppSettingsService appSettings;
    private final com.jlshell.terminal.service.TerminalHighlightService terminalHighlights;
    public com.jlshell.terminal.service.TerminalHighlightService terminalHighlights() { return terminalHighlights; }

    private final ColorSchemeRegistry registry;
    private long schemeRevision;

    public ThemeService(AppSettingsService appSettings, ColorSchemeRegistry registry, ExecutorService executor) {
        this.appSettings = appSettings;
        this.terminalHighlights = new com.jlshell.terminal.service.TerminalHighlightService(appSettings);
        refreshUiFont();
        this.registry = registry;
        String savedTheme = appSettings.get("ui.theme", "DARK");
        currentTheme.set("LIGHT".equals(savedTheme) ? AppTheme.LIGHT : AppTheme.DARK);
        accentColor.set(AccentColor.fromId(appSettings.get("ui.accentColor", AccentColor.SKY.id())));

        // 占位：立即可用，避免启动时阻塞解析 305 主题 JSON
        this.activeColorScheme = new MutableValue<>(currentTheme() == AppTheme.LIGHT ? TerminalColorScheme.light() : TerminalColorScheme.dark());

        // 异步解析保存的方案名；失败则保持 dark()。
        // 启动时还没有 terminal，set 触发的 listener 体里 workspaceTabs 是空的，安全。
        String savedScheme = normalizeBuiltInSchemeName(appSettings.get("terminal.colorScheme." + currentTheme().name(),
                appSettings.get("terminal.colorScheme.active", currentTheme() == AppTheme.LIGHT ? "light" : "dark")));
        CompletableFuture.supplyAsync(() -> registry.findByName(savedScheme).orElse(null), executor)
                .thenAcceptAsync(scheme -> {
                    if (schemeRevision == 0 && scheme != null && scheme.isLight() == (currentTheme() == AppTheme.LIGHT)
                            && !scheme.equals(activeColorScheme.get())) {
                        activeColorScheme.set(scheme);
                    }
                }, UiThread::run).exceptionally(error -> {
                    org.slf4j.LoggerFactory.getLogger(ThemeService.class).warn("Cannot restore terminal color scheme", error);
                    return null;
                });
    }

    public ObservableValue<AppTheme> currentThemeProperty() {
        return currentTheme;
    }

    public com.jlshell.plugin.api.lifecycle.Registration onThemeChanged(java.util.function.Consumer<AppTheme> listener) {
        return currentTheme.subscribe(listener);
    }

    public com.jlshell.plugin.api.lifecycle.Registration onAccentColorChanged(java.util.function.Consumer<AccentColor> listener) {
        return accentColor.subscribe(listener);
    }

    public AppTheme currentTheme() {
        return currentTheme.get();
    }

    public void setTheme(AppTheme theme) {
        if (theme == null) {
            return;
        }
        if (theme != currentTheme()) {
            TerminalColorScheme previous = activeColorScheme();
            appSettings.set("terminal.colorScheme." + (previous.isLight() ? "LIGHT" : "DARK"), previous.name());
            schemeRevision++;
            boolean light = theme == AppTheme.LIGHT;
            TerminalColorScheme fallback = light ? TerminalColorScheme.light() : TerminalColorScheme.dark();
            String remembered = appSettings.get("terminal.colorScheme." + theme.name(), fallback.name());
            TerminalColorScheme next = registry.findByName(remembered).filter(s -> s.isLight() == light).orElse(fallback);
            setActiveColorScheme(next);
        }
        currentTheme.set(theme);
        appSettings.set("ui.theme", theme.name());
    }

    public ObservableValue<AccentColor> accentColorProperty() {
        return accentColor;
    }

    public AccentColor accentColor() {
        return accentColor.get();
    }

    public void setAccentColor(AccentColor accent) {
        accentColor.set(accent);
        appSettings.set("ui.accentColor", accent.id());
    }

    public ObservableValue<TerminalColorScheme> activeColorSchemeProperty() {
        return activeColorScheme;
    }

    public com.jlshell.plugin.api.lifecycle.Registration onColorSchemeChanged(java.util.function.Consumer<TerminalColorScheme> listener) {
        return activeColorScheme.subscribe(listener);
    }

    public TerminalColorScheme activeColorScheme() {
        return activeColorScheme.get();
    }

    public void setActiveColorScheme(TerminalColorScheme scheme) {
        schemeRevision++;
        activeColorScheme.set(scheme);
        appSettings.set("terminal.colorScheme.active", scheme.name());
        appSettings.set("terminal.colorScheme." + (scheme.isLight() ? "LIGHT" : "DARK"), scheme.name());
    }

    private String normalizeBuiltInSchemeName(String name) {
        if (name == null || name.isBlank() || "dark".equals(name)) {
            return TerminalColorScheme.dark().name();
        }
        return name;
    }

    public ObservableValue<UiFont> uiFontProperty() {
        return uiFont;
    }

    public com.jlshell.plugin.api.lifecycle.Registration onUiFontChanged(java.util.function.Consumer<UiFont> listener) {
        return uiFont.subscribe(listener);
    }

    public UiFont uiFont() { return uiFont.get(); }

    /** Publish one complete preference after both family and size have been saved. Swing EDT. */
    public void refreshUiFont() {
        String family = appSettings.get("ui.font.family", "").strip();
        int size;
        try { size = Integer.parseInt(appSettings.get("ui.font.size", "13")); }
        catch (NumberFormatException invalid) { size = 13; }
        uiFont.set(new UiFont(family, Math.max(8, Math.min(24, size))));
    }

    /** 设置应用后立即刷新已有表单字体；订阅释放后不再持有插件页面。 */
    public void refreshUi() {
        UiThread.run(() -> {
            com.jlshell.ui.swing.SwingTheme.install(this);
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window.isDisplayable()) com.jlshell.ui.swing.SwingTheme.update(window);
            }
        });
    }
    public String uiFontFamily() { return appSettings.get("ui.font.family", null); }
    public int uiFontSize() {
        try { return Math.max(8, Math.min(24, Integer.parseInt(appSettings.get("ui.font.size", "13")))); }
        catch (NumberFormatException invalid) { return 13; }
    }

    public ColorSchemeRegistry registry() {
        return registry;
    }

    public static String defaultUiFontFamily() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "System";
        if (os.contains("win")) return "Segoe UI";
        return "SansSerif";
    }
}
