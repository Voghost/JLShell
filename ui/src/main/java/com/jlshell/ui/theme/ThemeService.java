package com.jlshell.ui.theme;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.Parent;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * 主题管理服务。
 * UI 主题（CSS 样式表）与终端配色方案独立管理。
 *
 * 启动优化：构造时先用 {@link TerminalColorScheme#dark()} 占位，
 * 异步从 registry 解析保存的方案名；解析完成后在 FX 线程更新 property。
 * 这样 305 主题 JSON 的解析就不在启动关键路径上了。
 */
public class ThemeService {

    private final ObjectProperty<AppTheme> currentTheme = new SimpleObjectProperty<>(AppTheme.DARK);
    private final ObjectProperty<AccentColor> accentColor = new SimpleObjectProperty<>(AccentColor.SKY);
    private final ObjectProperty<TerminalColorScheme> activeColorScheme;
    private final AppSettingsService appSettings;
    private final ColorSchemeRegistry registry;
    private final Map<DialogPane, Boolean> dialogPanes = new WeakHashMap<>();
    private static final String COMPONENT_STYLESHEET = "/css/components.css";

    public ThemeService(AppSettingsService appSettings, ColorSchemeRegistry registry, ExecutorService executor) {
        this.appSettings = appSettings;
        this.registry = registry;
        String savedTheme = appSettings.get("ui.theme", "DARK");
        currentTheme.set("LIGHT".equals(savedTheme) ? AppTheme.LIGHT : AppTheme.DARK);
        accentColor.set(AccentColor.fromId(appSettings.get("ui.accentColor", AccentColor.SKY.id())));
        currentTheme.addListener((obs, oldValue, newValue) -> refreshDialogs());
        accentColor.addListener((obs, oldValue, newValue) -> refreshDialogs());

        // 占位：立即可用，避免启动时阻塞解析 305 主题 JSON
        this.activeColorScheme = new SimpleObjectProperty<>(TerminalColorScheme.dark());

        // 异步解析保存的方案名；失败则保持 dark()。
        // 启动时还没有 terminal，set 触发的 listener 体里 workspaceTabs 是空的，安全。
        String savedScheme = normalizeBuiltInSchemeName(appSettings.get("terminal.colorScheme.active", "dark"));
        CompletableFuture.supplyAsync(() -> registry.findByName(savedScheme).orElse(null), executor)
                .thenAcceptAsync(scheme -> {
                    if (scheme != null && !scheme.equals(activeColorScheme.get())) {
                        activeColorScheme.set(scheme);
                    }
                }, Platform::runLater);
    }

    public ObjectProperty<AppTheme> currentThemeProperty() {
        return currentTheme;
    }

    public AppTheme currentTheme() {
        return currentTheme.get();
    }

    public void setTheme(AppTheme theme) {
        if (theme == null) {
            return;
        }
        currentTheme.set(theme);
        appSettings.set("ui.theme", theme.name());
    }

    public ObjectProperty<AccentColor> accentColorProperty() {
        return accentColor;
    }

    public AccentColor accentColor() {
        return accentColor.get();
    }

    public void setAccentColor(AccentColor accent) {
        accentColor.set(accent);
        appSettings.set("ui.accentColor", accent.id());
    }

    public ObjectProperty<TerminalColorScheme> activeColorSchemeProperty() {
        return activeColorScheme;
    }

    public TerminalColorScheme activeColorScheme() {
        return activeColorScheme.get();
    }

    public void setActiveColorScheme(TerminalColorScheme scheme) {
        activeColorScheme.set(scheme);
        appSettings.set("terminal.colorScheme.active", scheme.name());
    }

    private String normalizeBuiltInSchemeName(String name) {
        if (name == null || name.isBlank() || "dark".equals(name)) {
            return TerminalColorScheme.dark().name();
        }
        return name;
    }

    public void apply(Scene scene) {
        applyStylesheets(scene.getStylesheets());
        applyAccent(scene.getRoot());
    }

    public void applyToDialog(DialogPane pane) {
        dialogPanes.put(pane, Boolean.TRUE);
        applyStylesheets(pane.getStylesheets());
        applyAccent(pane);
    }

    private void refreshDialogs() {
        List.copyOf(dialogPanes.keySet()).forEach(this::applyToDialog);
    }

    private void applyStylesheets(javafx.collections.ObservableList<String> stylesheets) {
        // 仅替换宿主主题，保留插件或视图自行添加的样式表。
        List<String> themes = List.of(stylesheet(AppTheme.DARK.stylesheet()),
                stylesheet(AppTheme.LIGHT.stylesheet()), stylesheet(COMPONENT_STYLESHEET));
        List<String> active = List.of(stylesheet(currentTheme().stylesheet()), stylesheet(COMPONENT_STYLESHEET));
        if (stylesheets.size() >= 2 && stylesheets.subList(0, 2).equals(active)) {
            return;
        }
        stylesheets.removeAll(themes);
        stylesheets.addAll(0, active);
    }

    private String stylesheet(String resource) {
        return java.util.Objects.requireNonNull(getClass().getResource(resource), resource).toExternalForm();
    }

    public void applyToDialog(Dialog<?> dialog) {
        applyToDialog(dialog.getDialogPane());
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> applyToDialog(dialog.getDialogPane()));
    }

    public ColorSchemeRegistry registry() {
        return registry;
    }

    private void applyAccent(Parent root) {
        root.setStyle(uiStyle());
    }

    public String accentStyle() {
        AccentColor accent = accentColor();
        return String.format(Locale.ROOT,
                "-jl-accent: %s; -jl-accent-hover: %s; -jl-accent-subtle: %s; -jl-accent-border: %s; "
                        + "-jl-tab-accent-bg: %s; -jl-tab-accent-border: %s;",
                accent.color(),
                accent.hoverColor(),
                accent.subtleColor(currentTheme()),
                withAlpha(accent.color(), 0.28),
                withAlpha(accent.color(), currentTheme() == AppTheme.LIGHT ? 0.18 : 0.14),
                withAlpha(accent.color(), currentTheme() == AppTheme.LIGHT ? 0.34 : 0.30));
    }

    private String withAlpha(String hexColor, double alpha) {
        if (hexColor == null || !hexColor.matches("#[0-9a-fA-F]{6}")) {
            return hexColor;
        }
        int red = Integer.parseInt(hexColor.substring(1, 3), 16);
        int green = Integer.parseInt(hexColor.substring(3, 5), 16);
        int blue = Integer.parseInt(hexColor.substring(5, 7), 16);
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.2f)", red, green, blue, alpha);
    }

    public String uiStyle() {
        StringBuilder style = new StringBuilder();
        String family = appSettings.get("ui.font.family", null);
        if (family == null || family.isBlank()) {
            family = defaultUiFontFamily();
        }
        if (!family.isBlank()) {
            style.append("-fx-font-family: \"")
                    .append(family.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\";");
        }
        style.append("-fx-font-size: ")
                .append(appSettings.get("ui.font.size", "13"))
                .append("px;");
        style.append(accentStyle());
        return style.toString();
    }

    public static String defaultUiFontFamily() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "System";
        if (os.contains("win")) return "Segoe UI";
        return "SansSerif";
    }
}
