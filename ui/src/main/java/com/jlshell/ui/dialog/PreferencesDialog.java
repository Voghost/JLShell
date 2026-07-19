package com.jlshell.ui.dialog;

import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutDefinition;
import com.jlshell.core.shortcut.ShortcutRegistry;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.loader.store.PluginInstaller;
import com.jlshell.plugin.loader.store.PluginStoreClient;
import com.jlshell.plugin.loader.store.PluginStoreDetail;
import com.jlshell.plugin.loader.store.PluginStoreListing;
import com.jlshell.plugin.loader.store.PluginStoreSearch;
import com.jlshell.plugin.loader.store.PluginStoreUpdate;
import com.jlshell.plugin.loader.store.PluginStoreVersion;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiDefinition;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.MemoryReclaimService;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.service.update.UpdateService;
import com.jlshell.ui.shortcut.FxShortcutConverter;
import com.jlshell.ui.theme.AccentColor;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.beans.binding.Bindings;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.awt.Canvas;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * 偏好设置对话框。
 * 采用 TabPane 结构，每个 Tab 对应一类配置。
 */
public class PreferencesDialog {

    public static final int TAB_GENERAL = 0;
    public static final int TAB_ACCOUNT = 1;
    public static final int TAB_TERMINAL = 2;
    public static final int TAB_IMPORT = 3;
    public static final int TAB_API = 4;
    public static final int TAB_PLUGINS = 5;
    public static final int TAB_SHORTCUTS = 6;
    public static final int TAB_ABOUT = 7;

    private static final List<String> PREFERRED_MONO = List.of(
            "JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Fira Code",
            "Source Code Pro", "Hack", "Inconsolata", "Menlo", "Monaco",
            "Consolas", "Courier New", "SF Mono", "Ubuntu Mono"
    );

    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();
    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("zh_CN", "中文 (简体)");
    }

    /** 从 MANIFEST.MF 或 pom.properties 读取实际构建版本号 */
    private static String readVersion() {
        // 优先从 META-INF/MANIFEST.MF 读取 Implementation-Version（jpackage 打包时写入）
        try {
            var res = PreferencesDialog.class.getResource("/META-INF/MANIFEST.MF");
            if (res != null) {
                String content = new String(res.openStream().readAllBytes());
                for (String line : content.split("\n")) {
                    if (line.startsWith("Implementation-Version:")) {
                        return line.substring("Implementation-Version:".length()).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        // 回退到 Maven 生成的 pom.properties（ui 模块）
        try {
            var res = PreferencesDialog.class.getResource("/META-INF/maven/com.jlshell/ui/pom.properties");
            if (res != null) {
                var props = new java.util.Properties();
                props.load(res.openStream());
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) {
                    // 去掉 Maven 的 -SNAPSHOT 后缀，只保留语义版本号
                    return v.replace("-SNAPSHOT", "");
                }
            }
        } catch (Exception ignored) {}
        return "0.1.0";
    }

    private static final String VERSION = readVersion();

    /** 返回当前构建版本号，供 AboutDialog 等外部使用 */
    public static String getVersion() { return VERSION; }

    private PreferencesDialog() {}

    /** 打开偏好设置对话框，默认选中"通用"Tab。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer) {
        show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, 0);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            int initialTabIndex) {
        show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            CapabilityBus capabilityBus,
                            int initialTabIndex) {
        show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus,
                null, null, null, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            CapabilityBus capabilityBus,
                            ProgramPluginManager programPluginManager,
                            PluginManager pluginManager,
                            String selectedSessionId,
                            MemoryReclaimService memoryReclaimService,
                            int initialTabIndex) {
        show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager,
                pluginManager, selectedSessionId, memoryReclaimService, null, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            CapabilityBus capabilityBus,
                            ProgramPluginManager programPluginManager,
                            PluginManager pluginManager,
                            String selectedSessionId,
                            MemoryReclaimService memoryReclaimService,
                            AccountService accountService,
                            ShortcutRegistry shortcutRegistry,
                            int initialTabIndex) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("preferences.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(1040, 760);
        dialog.getDialogPane().setMinSize(860, 620);

        FontProfile[] pending = { fontProfileService.activeProfile() };
        String[] pendingLang = { appSettings.get("ui.language", null) };
        String[] pendingTheme = { appSettings.get("ui.theme", "DARK") };
        AccentColor[] pendingAccent = { themeService.accentColor() };
        String[] pendingConnTimeout = { appSettings.get("connection.timeout", "10") };
        String[] pendingKeepAlive = { appSettings.get("connection.keepAliveInterval", "60") };
        String[] pendingHoverExpand = { appSettings.get("ui.topbar.hoverExpand", "false") };
        TerminalColorScheme[] pendingScheme = { themeService.activeColorScheme() };
        String[] pendingApiEnabled = { appSettings.get("api.enabled", "false") };
        String[] pendingApiPort = { appSettings.get("api.port", "0") };
        String[] pendingUiFontFamily = { appSettings.get("ui.font.family", null) };
        String[] pendingUiFontSize = { appSettings.get("ui.font.size", "13") };
        String[] pendingScrollbackLines = { appSettings.get("terminal.scrollback.lines",
                String.valueOf(TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES)) };
        String[] pendingUpdateAutoCheck = { appSettings.get(UpdateService.SETTINGS_AUTO_CHECK, "true") };
        String[] pendingUpdateChannel = { appSettings.get(UpdateService.SETTINGS_CHANNEL, "stable") };
        String[] pendingUpdateBaseUrl = { UpdateService.configuredBaseUrl(appSettings) };
        String[] pendingAccountSyncEnabled = { appSettings.get(AccountService.SETTINGS_SYNC_ENABLED, "false") };
        String[] pendingAccountBaseUrl = { appSettings.get(AccountService.SETTINGS_BASE_URL, pendingUpdateBaseUrl[0]) };
        Runnable[] updateApplyState = new Runnable[1];
        Runnable preferenceChanged = () -> {
            if (updateApplyState[0] != null) updateApplyState[0].run();
        };

        TabPane tabs = buildTabPane(fontProfileService, appSettings, i18n, themeService,
                pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                pendingHoverExpand, pendingScheme,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager, pluginManager,
                selectedSessionId,
                pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                pendingAccountSyncEnabled, pendingAccountBaseUrl,
                accountService, shortcutRegistry, owner,
                memoryReclaimService, preferenceChanged);
        // 选中指定的初始 Tab（如从终端字体按钮打开时选中"终端"Tab）
        if (initialTabIndex >= 0 && initialTabIndex < tabs.getTabs().size()) {
            tabs.getSelectionModel().select(initialTabIndex);
        }
        dialog.getDialogPane().setContent(tabs);

        // Apply 按钮：用 APPLY 类型让 ButtonBar 自动放左侧
        ButtonType applyBtnType = new ButtonType(i18n.get("preferences.apply"), ButtonBar.ButtonData.APPLY);
        dialog.getDialogPane().getButtonTypes().addAll(applyBtnType, ButtonType.CANCEL, ButtonType.OK);

        // Apply 按钮：拦截默认关闭行为，只应用设置
        Button applyButton = (Button) dialog.getDialogPane().lookupButton(applyBtnType);
        PreferencesSnapshot[] lastApplied = { snapshotOf(pending, pendingLang, pendingTheme, pendingAccent,
                pendingConnTimeout, pendingKeepAlive, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry) };
        updateApplyState[0] = () -> applyButton.setDisable(!hasPendingSettingsChanges(lastApplied[0],
                snapshotOf(pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                        pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                        pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines,
                        pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                        pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry)));
        updateApplyState[0].run();
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); // 阻止 Dialog 默认的关闭逻辑
            boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending,
                    pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                    pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort, pendingUiFontFamily,
                    pendingUiFontSize, pendingScrollbackLines, pendingUpdateAutoCheck, pendingUpdateChannel,
                    pendingUpdateBaseUrl, pendingAccountSyncEnabled, pendingAccountBaseUrl);
            lastApplied[0] = snapshotOf(pending, pendingLang, pendingTheme, pendingAccent,
                    pendingConnTimeout, pendingKeepAlive, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                    pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines,
                    pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                    pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry);
            updateApplyState[0].run();
            if (needRestart) showRestartPrompt(owner, i18n);
        });

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending,
                        pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                        pendingHoverExpand, pendingScheme,
                        pendingApiEnabled, pendingApiPort, pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines,
                        pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                        pendingAccountSyncEnabled, pendingAccountBaseUrl);
                if (needRestart) showRestartPrompt(owner, i18n);
            }
            return null;
        });

        // DialogPane 的 minSize 不会约束原生窗口，必须设置到实际 Stage 上。
        dialog.setOnShown(event -> {
            if (dialog.getDialogPane().getScene().getWindow() instanceof Stage dialogStage) {
                dialogStage.setMinWidth(900);
                dialogStage.setMinHeight(640);
            }
        });

        dialog.showAndWait();
    }

    private static void showRestartPrompt(Stage owner, I18nService i18n) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                i18n.get("preferences.general.restartRequired"),
                ButtonType.OK);
        alert.setTitle(i18n.get("preferences.title"));
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        alert.showAndWait();
        com.jlshell.ui.support.RestartHelper.scheduleRestart();
    }

    private static boolean applyPendingSettings(FontProfileService fontProfileService, AppSettingsService appSettings,
                                              ThemeService themeService, FontProfile[] pending, String[] pendingLang,
                                              String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                              String[] pendingKeepAlive,
                                              String[] pendingHoverExpand,
                                              TerminalColorScheme[] pendingScheme,
                                              String[] pendingApiEnabled, String[] pendingApiPort,
                                              String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                              String[] pendingScrollbackLines,
                                              String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel,
                                              String[] pendingUpdateBaseUrl,
                                              String[] pendingAccountSyncEnabled, String[] pendingAccountBaseUrl) {
        String prevLang = appSettings.get("ui.language", null);
        fontProfileService.updateActiveProfile(pending[0]);
        appSettings.set("ui.language", pendingLang[0]);

        String prevTheme = appSettings.get("ui.theme", "DARK");
        appSettings.set("ui.theme", pendingTheme[0]);
        if (!prevTheme.equals(pendingTheme[0])) {
            AppTheme newTheme = "LIGHT".equals(pendingTheme[0]) ? AppTheme.LIGHT : AppTheme.DARK;
            themeService.currentThemeProperty().set(newTheme);
        }
        themeService.setAccentColor(pendingAccent[0]);

        appSettings.set("connection.timeout", pendingConnTimeout[0]);
        appSettings.set("connection.keepAliveInterval", pendingKeepAlive[0]);
        appSettings.set("ui.topbar.hoverExpand", pendingHoverExpand[0]);

        if (pendingScheme[0] != null) {
            themeService.setActiveColorScheme(pendingScheme[0]);
        }

        // API settings
        String prevApiEnabled = appSettings.get("api.enabled", "false");
        String prevApiPort = appSettings.get("api.port", "0");
        appSettings.set("api.enabled", pendingApiEnabled[0]);
        appSettings.set("api.port", pendingApiPort[0]);
        boolean apiChanged = !prevApiEnabled.equalsIgnoreCase(pendingApiEnabled[0])
                || !prevApiPort.equals(pendingApiPort[0]);

        // UI 字体设置
        appSettings.set("ui.font.family", pendingUiFontFamily[0] != null ? pendingUiFontFamily[0] : "");
        appSettings.set("ui.font.size", pendingUiFontSize[0]);
        appSettings.set("terminal.scrollback.lines", pendingScrollbackLines[0]);
        appSettings.set(UpdateService.SETTINGS_AUTO_CHECK, pendingUpdateAutoCheck[0]);
        appSettings.set(UpdateService.SETTINGS_CHANNEL, pendingUpdateChannel[0]);
        appSettings.set(UpdateService.SETTINGS_BASE_URL, pendingUpdateBaseUrl[0]);
        appSettings.set(AccountService.SETTINGS_SYNC_ENABLED, pendingAccountSyncEnabled[0]);
        appSettings.set(AccountService.SETTINGS_BASE_URL, pendingAccountBaseUrl[0]);

        boolean langChanged = !Objects.equals(prevLang, pendingLang[0]);
        return langChanged || apiChanged;
    }

    private record PreferencesSnapshot(
            FontProfile fontProfile,
            String language,
            String theme,
            AccentColor accent,
            String connectionTimeout,
            String keepAliveInterval,
            String hoverExpand,
            TerminalColorScheme colorScheme,
            String apiEnabled,
            String apiPort,
            String uiFontFamily,
            String uiFontSize,
            String scrollbackLines,
            String updateAutoCheck,
            String updateChannel,
            String updateBaseUrl,
            String accountSyncEnabled,
            String accountBaseUrl,
            Map<String, String> shortcutBindings
    ) {}

    private static PreferencesSnapshot snapshotOf(FontProfile[] pending, String[] pendingLang,
                                                  String[] pendingTheme, AccentColor[] pendingAccent,
                                                  String[] pendingConnTimeout, String[] pendingKeepAlive,
                                                  String[] pendingHoverExpand,
                                                  TerminalColorScheme[] pendingScheme,
                                                  String[] pendingApiEnabled, String[] pendingApiPort,
                                                  String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                                  String[] pendingScrollbackLines,
                                                  String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel,
                                                  String[] pendingUpdateBaseUrl,
                                                  String[] pendingAccountSyncEnabled, String[] pendingAccountBaseUrl,
                                                  ShortcutRegistry shortcutRegistry) {
        return new PreferencesSnapshot(
                pending[0],
                pendingLang[0],
                pendingTheme[0],
                pendingAccent[0],
                pendingConnTimeout[0],
                pendingKeepAlive[0],
                pendingHoverExpand[0],
                pendingScheme[0],
                pendingApiEnabled[0],
                pendingApiPort[0],
                normalizeNullableBlank(pendingUiFontFamily[0]),
                pendingUiFontSize[0],
                pendingScrollbackLines[0],
                pendingUpdateAutoCheck[0],
                pendingUpdateChannel[0],
                pendingUpdateBaseUrl[0],
                pendingAccountSyncEnabled[0],
                pendingAccountBaseUrl[0],
                shortcutBindingsOf(shortcutRegistry)
        );
    }

    private static boolean hasPendingSettingsChanges(PreferencesSnapshot lastApplied, PreferencesSnapshot current) {
        return !Objects.equals(lastApplied, current);
    }

    private static String normalizeNullableBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> shortcutBindingsOf(ShortcutRegistry shortcutRegistry) {
        if (shortcutRegistry == null) {
            return Map.of();
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        for (ShortcutDefinition definition : shortcutRegistry.definitions()) {
            bindings.put(definition.id() + ".primary",
                    normalizeShortcutBinding(shortcutRegistry.getEffectivePrimary(definition.id())));
            bindings.put(definition.id() + ".secondary",
                    normalizeShortcutBinding(shortcutRegistry.getEffectiveSecondary(definition.id())));
        }
        return Map.copyOf(bindings);
    }

    private static String normalizeShortcutBinding(String binding) {
        return binding == null ? "" : binding.strip();
    }

    private static TabPane buildTabPane(FontProfileService fontProfileService, AppSettingsService appSettings,
                                         I18nService i18n, ThemeService themeService,
                                         FontProfile[] pending, String[] pendingLang,
                                         String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                         String[] pendingKeepAlive,
                                         String[] pendingHoverExpand,
                                         TerminalColorScheme[] pendingScheme,
                                         com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                                         String activeProjectId,
                                         com.jlshell.api.server.ApiServer apiServer,
                                         CapabilityBus capabilityBus,
                                         ProgramPluginManager programPluginManager,
                                         PluginManager pluginManager,
                                         String selectedSessionId,
                                         String[] pendingApiEnabled, String[] pendingApiPort,
                                         String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                         String[] pendingScrollbackLines,
                                         String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel,
                                         String[] pendingUpdateBaseUrl,
                                         String[] pendingAccountSyncEnabled, String[] pendingAccountBaseUrl,
                                         AccountService accountService, ShortcutRegistry shortcutRegistry, Stage owner,
                                         MemoryReclaimService memoryReclaimService,
                                         Runnable preferenceChanged) {
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("preferences-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab generalTab = new Tab(i18n.get("preferences.tab.general"));
        generalTab.setContent(buildGeneralPane(appSettings, i18n, themeService, pendingLang, pendingTheme,
                pendingAccent, pendingConnTimeout, pendingKeepAlive, pendingHoverExpand,
                pendingUiFontFamily, pendingUiFontSize,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                memoryReclaimService, preferenceChanged));
        tabPane.getTabs().add(generalTab);

        Tab accountTab = new Tab(i18n.get("preferences.tab.account"));
        accountTab.setContent(buildAccountPane(i18n, themeService, accountService, owner,
                pendingAccountSyncEnabled, pendingAccountBaseUrl, preferenceChanged));
        tabPane.getTabs().add(accountTab);

        Tab terminalTab = new Tab(i18n.get("preferences.tab.terminal"));
        terminalTab.setContent(buildTerminalPane(appSettings, fontProfileService.activeProfile(), i18n, themeService,
                pending, pendingScheme, pendingScrollbackLines, preferenceChanged));
        tabPane.getTabs().add(terminalTab);

        Tab importTab = new Tab(i18n.get("preferences.tab.import"));
        importTab.setContent(buildImportPane(i18n, connectionProfileService, activeProjectId));
        tabPane.getTabs().add(importTab);

        Tab apiTab = new Tab(i18n.get("preferences.tab.api"));
        apiTab.setContent(buildApiPane(appSettings, i18n, apiServer, capabilityBus, selectedSessionId,
                pendingApiEnabled, pendingApiPort, preferenceChanged));
        tabPane.getTabs().add(apiTab);

        Tab pluginsTab = new Tab(i18n.get("preferences.tab.plugins"));
        pluginsTab.setContent(buildPluginsPane(appSettings, i18n, themeService, owner,
                programPluginManager, pluginManager));
        tabPane.getTabs().add(pluginsTab);

        Tab shortcutsTab = new Tab(i18n.get("preferences.tab.shortcuts"));
        shortcutsTab.setContent(buildShortcutsPane(shortcutRegistry, i18n, themeService, owner, preferenceChanged));
        tabPane.getTabs().add(shortcutsTab);

        Tab aboutTab = new Tab(i18n.get("preferences.tab.about"));
        aboutTab.setContent(buildAboutPane(i18n));
        tabPane.getTabs().add(aboutTab);

        return tabPane;
    }

    // ── Account Tab ────────────────────────────────────────────────────────

    private static VBox buildAccountPane(I18nService i18n, ThemeService themeService,
                                         AccountService accountService, Stage owner,
                                         String[] pendingAccountSyncEnabled,
                                         String[] pendingAccountBaseUrl,
                                         Runnable preferenceChanged) {
        Label statusValue = new Label();
        Label usernameValue = new Label();
        Label emailValue = new Label();
        Label accountIdValue = new Label();
        Label deviceCountValue = new Label();
        statusValue.getStyleClass().add("account-status-badge");
        accountIdValue.setWrapText(true);

        TextField baseUrl = new TextField(pendingAccountBaseUrl[0]);
        baseUrl.setPrefWidth(280);
        baseUrl.textProperty().addListener((o, ov, nv) -> {
            pendingAccountBaseUrl[0] = nv == null || nv.isBlank() ? "https://jlshell.com" : nv.strip();
            preferenceChanged.run();
        });

        CheckBox syncEnabled = new CheckBox(i18n.get("preferences.account.syncEnabled"));
        syncEnabled.setSelected(Boolean.parseBoolean(pendingAccountSyncEnabled[0]));
        syncEnabled.selectedProperty().addListener((o, ov, nv) -> {
            pendingAccountSyncEnabled[0] = String.valueOf(nv);
            preferenceChanged.run();
        });

        Label syncHint = new Label(i18n.get("preferences.account.syncHint"));
        syncHint.setWrapText(true);
        syncHint.setMaxWidth(Double.MAX_VALUE);
        syncHint.getStyleClass().add("settings-card-description");

        Button loginButton = new Button(i18n.get("account.login.action"));
        Button registerButton = new Button(i18n.get("account.register.action"));
        Button logoutButton = new Button(i18n.get("account.logout"));
        Button changePasswordButton = new Button(i18n.get("account.changePassword"));

        Runnable refreshAccountState = () -> {
            if (accountService == null || accountService.currentSession().isEmpty()) {
                statusValue.setText(i18n.get("preferences.account.signedOut"));
                usernameValue.setText("-");
                emailValue.setText("-");
                accountIdValue.setText("-");
                deviceCountValue.setText("-");
                loginButton.setVisible(true);
                loginButton.setManaged(true);
                registerButton.setVisible(true);
                registerButton.setManaged(true);
                logoutButton.setVisible(false);
                logoutButton.setManaged(false);
                changePasswordButton.setVisible(false);
                changePasswordButton.setManaged(false);
                return;
            }
            AccountService.AccountSession session = accountService.currentSession().orElseThrow();
            statusValue.setText(i18n.get("preferences.account.signedIn"));
            usernameValue.setText(session.username().isBlank() ? "-" : session.username());
            emailValue.setText(session.email().isBlank() ? "-" : session.email());
            accountIdValue.setText(session.id().isBlank() ? "-" : session.id());
            deviceCountValue.setText(String.valueOf(session.historicalDeviceCount()));
            loginButton.setVisible(false);
            loginButton.setManaged(false);
            registerButton.setVisible(false);
            registerButton.setManaged(false);
            logoutButton.setVisible(true);
            logoutButton.setManaged(true);
            changePasswordButton.setVisible(true);
            changePasswordButton.setManaged(true);
        };

        loginButton.setDisable(accountService == null);
        registerButton.setDisable(accountService == null);
        logoutButton.setDisable(accountService == null);
        loginButton.setOnAction(event -> {
            AccountDialog.showLogin(owner, i18n, themeService, accountService);
            refreshAccountState.run();
        });
        registerButton.setOnAction(event -> {
            AccountDialog.showRegister(owner, i18n, themeService, accountService);
            refreshAccountState.run();
        });
        logoutButton.setOnAction(event -> {
            logoutButton.setDisable(true);
            accountService.logout().whenComplete((v, error) -> javafx.application.Platform.runLater(() -> {
                refreshAccountState.run();
                themedAlert(Alert.AlertType.INFORMATION, i18n.get("account.logout.success"), owner, i18n, themeService);
            }));
        });
        changePasswordButton.setDisable(accountService == null);
        changePasswordButton.setOnAction(event -> {
            showChangePasswordDialog(owner, i18n, themeService, accountService);
            refreshAccountState.run();
        });

        HBox actions = new HBox(8, loginButton, registerButton, logoutButton, changePasswordButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        GridPane identityForm = settingsForm();
        addSettingsRow(identityForm, 0, i18n.get("preferences.account.status"), statusValue);
        addSettingsRow(identityForm, 1, i18n.get("account.username"), usernameValue);
        addSettingsRow(identityForm, 2, i18n.get("account.email"), emailValue);
        addSettingsRow(identityForm, 3, i18n.get("preferences.account.accountId"), accountIdValue);
        addSettingsRow(identityForm, 4, i18n.get("preferences.account.deviceCount"), deviceCountValue);
        addSettingsRow(identityForm, 5, "", actions);

        GridPane syncForm = settingsForm();
        baseUrl.setMaxWidth(Double.MAX_VALUE);
        addSettingsRow(syncForm, 0, i18n.get("preferences.account.baseUrl"), baseUrl);
        addSettingsRow(syncForm, 1, "", syncEnabled);
        addSettingsRow(syncForm, 2, "", syncHint);

        VBox identityCard = settingsCard(i18n.get("preferences.account.profileSection"),
                i18n.get("preferences.account.profileSection.description"), identityForm);
        VBox syncCard = settingsCard(i18n.get("preferences.account.syncSection"),
                i18n.get("preferences.account.syncSection.description"), syncForm);

        GridPane cards = new GridPane();
        cards.getStyleClass().add("settings-card-grid");
        cards.setHgap(14);
        cards.setVgap(14);
        javafx.scene.layout.ColumnConstraints profileColumn = new javafx.scene.layout.ColumnConstraints();
        profileColumn.setPercentWidth(54);
        profileColumn.setHgrow(Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints syncColumn = new javafx.scene.layout.ColumnConstraints();
        syncColumn.setPercentWidth(46);
        syncColumn.setHgrow(Priority.ALWAYS);
        cards.getColumnConstraints().addAll(profileColumn, syncColumn);
        cards.add(identityCard, 0, 0);
        cards.add(syncCard, 1, 0);

        refreshAccountState.run();

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll-pane");
        VBox pane = new VBox(scroll);
        pane.getStyleClass().add("settings-page");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return pane;
    }

    /** 修改密码对话框。 */
    private static void showChangePasswordDialog(Stage owner, I18nService i18n,
                                                  ThemeService themeService,
                                                  AccountService accountService) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("account.changePassword.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);

        PasswordField oldPassword = new PasswordField();
        PasswordField newPassword = new PasswordField();
        PasswordField confirmPassword = new PasswordField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 20, 8, 20));
        grid.add(new Label(i18n.get("account.changePassword.oldPassword")), 0, 0);
        grid.add(oldPassword, 1, 0);
        grid.add(new Label(i18n.get("account.changePassword.newPassword")), 0, 1);
        grid.add(newPassword, 1, 1);
        grid.add(new Label(i18n.get("account.changePassword.confirmPassword")), 0, 2);
        grid.add(confirmPassword, 1, 2);
        dialog.getDialogPane().setContent(grid);

        ButtonType submitType = new ButtonType(i18n.get("account.changePassword"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        Button submit = (Button) dialog.getDialogPane().lookupButton(submitType);
        submit.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            String oldPwd = oldPassword.getText();
            String newPwd = newPassword.getText();
            String confirmPwd = confirmPassword.getText();
            if (oldPwd == null || oldPwd.isBlank() || newPwd == null || newPwd.isBlank()) {
                themedAlert(Alert.AlertType.ERROR, i18n.get("account.error.missingFields"), owner, i18n, themeService);
                return;
            }
            if (newPwd.length() < 8) {
                themedAlert(Alert.AlertType.ERROR, i18n.get("account.changePassword.error.minLength"), owner, i18n, themeService);
                return;
            }
            if (!newPwd.equals(confirmPwd)) {
                themedAlert(Alert.AlertType.ERROR, i18n.get("account.changePassword.error.mismatch"), owner, i18n, themeService);
                return;
            }
            submit.setDisable(true);
            accountService.changePassword(oldPwd, newPwd).whenComplete((session, error) -> {
                javafx.application.Platform.runLater(() -> {
                    submit.setDisable(false);
                    if (error != null) {
                        String msg;
                        if (error instanceof AccountService.AccountHttpException httpEx && httpEx.statusCode() == 401) {
                            msg = i18n.get("account.changePassword.error.wrongOld");
                        } else {
                            msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                        }
                        themedAlert(Alert.AlertType.ERROR, msg, owner, i18n, themeService);
                        return;
                    }
                    dialog.close();
                    themedAlert(Alert.AlertType.INFORMATION, i18n.get("account.changePassword.success"), owner, i18n, themeService);
                });
            });
        });

        dialog.showAndWait();
    }

    /** 创建带主题的 Alert 并 showAndWait。 */
    private static void themedAlert(Alert.AlertType type, String message, Stage owner,
                                    I18nService i18n, ThemeService themeService) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(i18n.get("account.title"));
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        themeService.applyToDialog(alert);
        alert.showAndWait();
    }

    // ── General Tab ────────────────────────────────────────────────────────

    private static VBox buildGeneralPane(AppSettingsService appSettings, I18nService i18n,
                                          ThemeService themeService, String[] pendingLang, String[] pendingTheme,
                                          AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                          String[] pendingKeepAlive, String[] pendingHoverExpand,
                                          String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                          String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel,
                                          String[] pendingUpdateBaseUrl,
                                          MemoryReclaimService memoryReclaimService,
                                          Runnable preferenceChanged) {
        // 首次启动：未设置语言时根据系统语言环境推断
        String currentLang = appSettings.get("ui.language", null);
        if (currentLang == null) {
            Locale sys = Locale.getDefault();
            currentLang = "zh".equals(sys.getLanguage()) ? "zh_CN" : "en";
            pendingLang[0] = currentLang; // 同步到 pending，确保 Apply/OK 能保存
        }
        String currentTheme = appSettings.get("ui.theme", "DARK");

        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(LANGUAGES.values());
        langCombo.setValue(LANGUAGES.getOrDefault(currentLang, "English"));
        langCombo.setPrefWidth(200);
        langCombo.valueProperty().addListener((o, ov, nv) -> {
            LANGUAGES.entrySet().stream()
                    .filter(e -> e.getValue().equals(nv))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(code -> {
                        pendingLang[0] = code;
                        preferenceChanged.run();
                    });
        });

        ComboBox<String> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll("Dark", "Light");
        themeCombo.setValue("LIGHT".equals(currentTheme) ? "Light" : "Dark");
        themeCombo.setPrefWidth(200);
        themeCombo.valueProperty().addListener((o, ov, nv) -> {
            pendingTheme[0] = "Light".equals(nv) ? "LIGHT" : "DARK";
            preferenceChanged.run();
        });

        ComboBox<AccentColor> accentCombo = new ComboBox<>();
        accentCombo.getItems().addAll(AccentColor.values());
        accentCombo.setValue(pendingAccent[0]);
        accentCombo.setPrefWidth(200);
        accentCombo.setButtonCell(accentCell());
        accentCombo.setCellFactory(list -> accentCell());
        accentCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                pendingAccent[0] = nv;
                preferenceChanged.run();
            }
        });

        CheckBox hoverExpandCheck = new CheckBox(i18n.get("preferences.general.hoverExpand"));
        hoverExpandCheck.setSelected("true".equals(pendingHoverExpand[0]));
        hoverExpandCheck.selectedProperty().addListener((o, ov, nv) -> {
            pendingHoverExpand[0] = String.valueOf(nv);
            preferenceChanged.run();
        });

        CheckBox updateAutoCheck = new CheckBox(i18n.get("preferences.updates.autoCheck"));
        updateAutoCheck.setSelected(Boolean.parseBoolean(pendingUpdateAutoCheck[0]));
        updateAutoCheck.selectedProperty().addListener((o, ov, nv) -> {
            pendingUpdateAutoCheck[0] = String.valueOf(nv);
            preferenceChanged.run();
        });

        ComboBox<String> updateChannel = new ComboBox<>();
        updateChannel.getItems().addAll("stable", "beta");
        updateChannel.setValue(pendingUpdateChannel[0] == null || pendingUpdateChannel[0].isBlank()
                ? "stable" : pendingUpdateChannel[0]);
        updateChannel.setPrefWidth(120);
        updateChannel.valueProperty().addListener((o, ov, nv) -> {
            pendingUpdateChannel[0] = nv == null ? "stable" : nv;
            preferenceChanged.run();
        });

        TextField updateBaseUrl = new TextField(pendingUpdateBaseUrl[0]);
        updateBaseUrl.setPrefWidth(260);
        updateBaseUrl.textProperty().addListener((o, ov, nv) -> {
            pendingUpdateBaseUrl[0] = nv == null || nv.isBlank() ? UpdateService.DEFAULT_BASE_URL : nv.strip();
            preferenceChanged.run();
        });

        // ── UI 字体设置 ──
        String currentUiFontFamily = appSettings.get("ui.font.family", null);
        ComboBox<String> uiFontCombo = new ComboBox<>();
        String[] allFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> sortedFonts = Arrays.stream(allFonts).sorted().collect(Collectors.toCollection(ArrayList::new));
        String defaultFontLabel = i18n.get("preferences.general.uiFont.default");
        sortedFonts.add(0, defaultFontLabel);
        ObservableList<String> uiFonts = FXCollections.observableArrayList(sortedFonts);
        uiFontCombo.setItems(uiFonts);
        if (currentUiFontFamily != null && !currentUiFontFamily.isBlank()) {
            uiFontCombo.setValue(currentUiFontFamily);
        } else {
            uiFontCombo.setValue(defaultFontLabel);
        }
        uiFontCombo.setPrefWidth(220);
        uiFontCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null || nv.equals(defaultFontLabel)) {
                pendingUiFontFamily[0] = null;
            } else if (uiFonts.contains(nv)) {
                pendingUiFontFamily[0] = nv;
            }
            preferenceChanged.run();
        });

        int currentUiFontSize = 13;
        try { currentUiFontSize = Integer.parseInt(appSettings.get("ui.font.size", "13")); }
        catch (NumberFormatException ignored) {}
        Slider uiFontSizeSlider = new Slider(8, 24, currentUiFontSize);
        uiFontSizeSlider.setPrefWidth(140);
        TextField uiFontSizeField = new TextField(String.valueOf(currentUiFontSize));
        uiFontSizeField.setPrefWidth(44);
        uiFontSizeSlider.valueProperty().addListener((o, ov, nv) ->
                uiFontSizeField.setText(String.valueOf(nv.intValue())));
        uiFontSizeField.textProperty().addListener((o, ov, nv) -> {
            try { int v = Integer.parseInt(nv); if (v >= 8 && v <= 24) uiFontSizeSlider.setValue(v); }
            catch (NumberFormatException ignored) {}
        });

        Text uiFontPreview = new Text("Hello World  Settings  设置  AaBbCc 0123");
        String previewFg = themeService.currentThemeProperty().get() == AppTheme.LIGHT ? "#0f172a" : "#dfe1e5";
        uiFontPreview.setStyle("-fx-fill: " + previewFg + ";");
        uiFontCombo.valueProperty().addListener((o, ov, nv) -> {
            String fam = (nv == null || nv.equals(defaultFontLabel) || !uiFonts.contains(nv))
                    ? "System" : nv;
            updatePreview(uiFontPreview, fam, uiFontSizeSlider.getValue());
        });
        uiFontSizeSlider.valueProperty().addListener((o, ov, nv) -> {
            String selectedFont = uiFontCombo.getValue();
            String fam = (selectedFont == null || selectedFont.equals(defaultFontLabel) || !uiFonts.contains(selectedFont))
                    ? "System" : uiFontCombo.getValue();
            updatePreview(uiFontPreview, fam, nv.doubleValue());
        });
        updatePreview(uiFontPreview, currentUiFontFamily != null ? currentUiFontFamily : "System", currentUiFontSize);

        uiFontCombo.valueProperty().addListener((o, ov, nv) -> {
            pendingUiFontSize[0] = uiFontSizeField.getText();
            preferenceChanged.run();
        });
        uiFontSizeSlider.valueProperty().addListener((o, ov, nv) -> {
            pendingUiFontSize[0] = uiFontSizeField.getText();
            preferenceChanged.run();
        });

        HBox uiFontSizeRow = new HBox(8, uiFontSizeSlider, uiFontSizeField);
        uiFontSizeRow.setAlignment(Pos.CENTER_LEFT);

        Button gcButton = new Button(i18n.get("preferences.general.memoryGc"));
        Label memoryStatus = new Label("");
        gcButton.setDisable(memoryReclaimService == null);
        gcButton.setOnAction(event -> {
            if (memoryReclaimService == null) return;
            gcButton.setDisable(true);
            memoryStatus.setText(i18n.get("preferences.general.memoryGc.running"));
            memoryReclaimService.requestNowWithStatus(result -> {
                memoryStatus.setText(i18n.get("preferences.general.memoryGc.done", result));
                gcButton.setDisable(false);
            });
        });
        HBox memoryRow = new HBox(8, gcButton, memoryStatus);
        memoryRow.setAlignment(Pos.CENTER_LEFT);

        TextField timeoutField = new TextField(pendingConnTimeout[0]);
        timeoutField.setPrefWidth(72);
        timeoutField.setMaxWidth(72);
        timeoutField.textProperty().addListener((o, ov, nv) -> {
            try {
                int value = Integer.parseInt(nv.trim());
                if (value > 0) {
                    pendingConnTimeout[0] = String.valueOf(value);
                    preferenceChanged.run();
                }
            } catch (NumberFormatException ignored) {}
        });
        HBox timeoutRow = new HBox(8, timeoutField,
                new Label(i18n.get("preferences.connection.timeoutUnit")));
        timeoutRow.setAlignment(Pos.CENTER_LEFT);

        TextField keepAliveField = new TextField(pendingKeepAlive[0]);
        keepAliveField.setPrefWidth(72);
        keepAliveField.setMaxWidth(72);
        keepAliveField.textProperty().addListener((o, ov, nv) -> {
            try {
                int value = Integer.parseInt(nv.trim());
                if (value >= 0) {
                    pendingKeepAlive[0] = String.valueOf(value);
                    preferenceChanged.run();
                }
            } catch (NumberFormatException ignored) {}
        });
        HBox keepAliveRow = new HBox(8, keepAliveField,
                new Label(i18n.get("preferences.connection.keepAliveUnit")));
        keepAliveRow.setAlignment(Pos.CENTER_LEFT);

        GridPane appearanceForm = settingsForm();
        addSettingsRow(appearanceForm, 0, i18n.get("preferences.general.language"), langCombo);
        addSettingsRow(appearanceForm, 1, i18n.get("preferences.general.theme"), themeCombo);
        addSettingsRow(appearanceForm, 2, i18n.get("preferences.general.accentColor"), accentCombo);
        addSettingsRow(appearanceForm, 3, "", hoverExpandCheck);

        GridPane fontForm = settingsForm();
        addSettingsRow(fontForm, 0, i18n.get("preferences.general.uiFontFamily"), uiFontCombo);
        addSettingsRow(fontForm, 1, i18n.get("preferences.general.uiFontSize"), uiFontSizeRow);
        StackPane previewBox = new StackPane(uiFontPreview);
        previewBox.getStyleClass().add("settings-preview-box");
        previewBox.setAlignment(Pos.CENTER_LEFT);
        addSettingsRow(fontForm, 2, i18n.get("preferences.general.uiFontPreview"), previewBox);

        GridPane systemForm = settingsForm();
        addSettingsRow(systemForm, 0, i18n.get("preferences.general.memory"), memoryRow);

        GridPane connectionForm = settingsForm();
        addSettingsRow(connectionForm, 0, i18n.get("preferences.connection.timeout"), timeoutRow);
        addSettingsRow(connectionForm, 1, i18n.get("preferences.connection.keepAlive"), keepAliveRow);

        GridPane updatesForm = settingsForm();
        updateBaseUrl.setMaxWidth(Double.MAX_VALUE);
        addSettingsRow(updatesForm, 0, "", updateAutoCheck);
        addSettingsRow(updatesForm, 1, i18n.get("preferences.updates.channel"), updateChannel);
        addSettingsRow(updatesForm, 2, i18n.get("preferences.updates.baseUrl"), updateBaseUrl);

        VBox appearanceCard = settingsCard(i18n.get("preferences.general.appearance"),
                i18n.get("preferences.general.appearance.description"), appearanceForm);
        VBox fontCard = settingsCard(i18n.get("preferences.general.fontSection"),
                i18n.get("preferences.general.fontSection.description"), fontForm);
        VBox connectionCard = settingsCard(i18n.get("preferences.connection.section"),
                i18n.get("preferences.connection.section.description"), connectionForm);
        VBox systemCard = settingsCard(i18n.get("preferences.general.systemSection"),
                i18n.get("preferences.general.systemSection.description"), systemForm);
        VBox updatesCard = settingsCard(i18n.get("preferences.updates.title"),
                i18n.get("preferences.updates.description"), updatesForm);

        GridPane cards = new GridPane();
        cards.getStyleClass().add("settings-card-grid");
        cards.setHgap(14);
        cards.setVgap(14);
        javafx.scene.layout.ColumnConstraints leftColumn = new javafx.scene.layout.ColumnConstraints();
        leftColumn.setPercentWidth(50);
        leftColumn.setHgrow(Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints rightColumn = new javafx.scene.layout.ColumnConstraints();
        rightColumn.setPercentWidth(50);
        rightColumn.setHgrow(Priority.ALWAYS);
        cards.getColumnConstraints().addAll(leftColumn, rightColumn);
        cards.add(appearanceCard, 0, 0);
        cards.add(fontCard, 1, 0);
        cards.add(connectionCard, 0, 1);
        cards.add(systemCard, 1, 1);
        cards.add(updatesCard, 0, 2, 2, 1);

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll-pane");
        VBox pane = new VBox(scroll);
        pane.getStyleClass().add("settings-page");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return pane;
    }

    private static GridPane settingsForm() {
        GridPane form = new GridPane();
        form.getStyleClass().add("settings-form");
        form.setHgap(14);
        form.setVgap(12);
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(95);
        labels.setPrefWidth(115);
        javafx.scene.layout.ColumnConstraints values = new javafx.scene.layout.ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        form.getColumnConstraints().addAll(labels, values);
        return form;
    }

    private static void addSettingsRow(GridPane form, int row, String label, javafx.scene.Node value) {
        Label key = new Label(label);
        key.getStyleClass().add("settings-field-label");
        key.setVisible(!label.isBlank());
        key.setManaged(!label.isBlank());
        form.add(key, 0, row);
        form.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
        if (value instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }

    private static VBox settingsCard(String title, String description, javafx.scene.Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("settings-card-description");
        descriptionLabel.setWrapText(true);
        VBox card = new VBox(5, titleLabel, descriptionLabel, new Separator(), content);
        card.getStyleClass().add("settings-card");
        card.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(content, Priority.ALWAYS);
        GridPane.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private record SettingsSectionLink(String title, javafx.scene.Node target) {}

    private static VBox settingsSectionDirectory(I18nService i18n, ScrollPane scrollPane, VBox content,
                                                 List<SettingsSectionLink> sections) {
        Label title = new Label(i18n.get("settings.directory.title"));
        title.getStyleClass().add("settings-directory-title");
        Label hint = new Label(i18n.get("settings.directory.hint"));
        hint.getStyleClass().add("settings-directory-hint");
        hint.setWrapText(true);

        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        VBox items = new VBox(5);
        for (SettingsSectionLink section : sections) {
            javafx.scene.control.ToggleButton item = new javafx.scene.control.ToggleButton(section.title());
            item.getStyleClass().add("settings-directory-item");
            item.setToggleGroup(group);
            item.setUserData(section);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            items.getChildren().add(item);
        }
        if (!items.getChildren().isEmpty()) {
            ((javafx.scene.control.ToggleButton) items.getChildren().get(0)).setSelected(true);
        }
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) oldToggle.setSelected(true);
                return;
            }
            if (newToggle.getUserData() instanceof SettingsSectionLink section) {
                Platform.runLater(() -> scrollSettingsSection(scrollPane, content, section.target()));
            }
        });

        VBox directory = new VBox(8, title, hint, new Separator(), items);
        directory.getStyleClass().add("settings-directory");
        directory.setMinWidth(138);
        directory.setPrefWidth(154);
        directory.setMaxWidth(168);
        return directory;
    }

    private static void scrollSettingsSection(ScrollPane scrollPane, VBox content, javafx.scene.Node target) {
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double scrollableHeight = Math.max(1, contentHeight - viewportHeight);
        scrollPane.setVvalue(Math.max(0, Math.min(1,
                target.getBoundsInParent().getMinY() / scrollableHeight)));
    }

    private static ListCell<AccentColor> accentCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AccentColor item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Region swatch = new Region();
                swatch.setMinSize(14, 14);
                swatch.setPrefSize(14, 14);
                swatch.setMaxSize(14, 14);
                swatch.setStyle("-fx-background-color: " + item.color() + "; -fx-background-radius: 7;");
                HBox row = new HBox(8, swatch, new Label(item.displayName()));
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        };
    }

    // ── Terminal Tab ───────────────────────────────────────────────────────

    private static VBox buildTerminalPane(AppSettingsService appSettings,
                                           FontProfile current, I18nService i18n,
                                           ThemeService themeService,
                                           FontProfile[] pending, TerminalColorScheme[] pendingScheme,
                                           String[] pendingScrollbackLines,
                                           Runnable preferenceChanged) {
        // ── Font section ──
        List<String> monoFonts = loadMonospacedFonts();

        ComboBox<String> fontCombo = new ComboBox<>();
        fontCombo.getItems().addAll(monoFonts);
        fontCombo.setValue(monoFonts.contains(current.family()) ? current.family() : monoFonts.get(0));
        fontCombo.setPrefWidth(220);

        Slider sizeSlider = new Slider(8, 32, current.size());
        sizeSlider.setPrefWidth(180);
        TextField sizeField = new TextField(fmt0(current.size()));
        sizeField.setPrefWidth(44);
        sizeSlider.valueProperty().addListener((o, ov, nv) -> sizeField.setText(fmt0(nv.doubleValue())));
        sizeField.textProperty().addListener((o, ov, nv) -> {
            try { double v = Double.parseDouble(nv); if (v >= 8 && v <= 32) sizeSlider.setValue(v); }
            catch (NumberFormatException ignored) {}
        });

        Slider spacingSlider = new Slider(0.8, 2.0, current.lineSpacing());
        spacingSlider.setPrefWidth(180);
        TextField spacingField = new TextField(fmt1(current.lineSpacing()));
        spacingField.setPrefWidth(44);
        spacingSlider.valueProperty().addListener((o, ov, nv) -> spacingField.setText(fmt1(nv.doubleValue())));
        spacingField.textProperty().addListener((o, ov, nv) -> {
            try { double v = Double.parseDouble(nv); if (v >= 0.8 && v <= 2.0) spacingSlider.setValue(v); }
            catch (NumberFormatException ignored) {}
        });

        CheckBox ligaturesCheck = new CheckBox(i18n.get("preferences.terminal.ligatures"));
        ligaturesCheck.setSelected(current.ligaturesEnabled());

        Text preview = new Text("Hello World  你好世界  AaBbCc 0123  -> => !=");
        // 字体预览强制使用主题前景色，避免与对话框背景对比度不足
        String previewFg = themeService.currentThemeProperty().get() == AppTheme.LIGHT ? "#0f172a" : "#dfe1e5";
        preview.setStyle("-fx-fill: " + previewFg + ";");
        fontCombo.valueProperty().addListener((o, ov, nv) -> updatePreview(preview, nv, sizeSlider.getValue()));
        sizeSlider.valueProperty().addListener((o, ov, nv) -> updatePreview(preview, fontCombo.getValue(), nv.doubleValue()));
        updatePreview(preview, fontCombo.getValue(), sizeSlider.getValue());

        Runnable sync = () -> {
            double size    = parseDouble(sizeField.getText(), current.size());
            double spacing = parseDouble(spacingField.getText(), current.lineSpacing());
            pending[0] = new FontProfile(fontCombo.getValue(), size, ligaturesCheck.isSelected(), spacing);
            preferenceChanged.run();
        };
        fontCombo.valueProperty().addListener((o, ov, nv) -> sync.run());
        sizeSlider.valueProperty().addListener((o, ov, nv) -> sync.run());
        spacingSlider.valueProperty().addListener((o, ov, nv) -> sync.run());
        ligaturesCheck.selectedProperty().addListener((o, ov, nv) -> sync.run());

        GridPane fontGrid = new GridPane();
        fontGrid.setHgap(12);
        fontGrid.setVgap(10);

        fontGrid.add(new Label(i18n.get("preferences.terminal.fontFamily")), 0, 0);
        fontGrid.add(fontCombo, 1, 0, 2, 1);

        fontGrid.add(new Label(i18n.get("preferences.terminal.fontSize")), 0, 1);
        HBox sizeRow = new HBox(8, sizeSlider, sizeField);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        fontGrid.add(sizeRow, 1, 1, 2, 1);

        fontGrid.add(new Label(i18n.get("preferences.terminal.lineSpacing")), 0, 2);
        HBox spacingRow = new HBox(8, spacingSlider, spacingField);
        spacingRow.setAlignment(Pos.CENTER_LEFT);
        fontGrid.add(spacingRow, 1, 2, 2, 1);

        fontGrid.add(ligaturesCheck, 1, 3, 2, 1);

        fontGrid.add(new Label(i18n.get("preferences.terminal.preview")), 0, 4);
        fontGrid.add(preview, 1, 4, 2, 1);

        int currentScrollback = parseScrollbackLines(appSettings.get(
                "terminal.scrollback.lines",
                String.valueOf(TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES)));
        Slider scrollbackSlider = new Slider(
                TerminalRuntimeSettings.MIN_SCROLLBACK_LINES,
                TerminalRuntimeSettings.MAX_SCROLLBACK_LINES,
                currentScrollback);
        scrollbackSlider.setMajorTickUnit(10_000);
        scrollbackSlider.setBlockIncrement(500);
        scrollbackSlider.setSnapToTicks(true);
        scrollbackSlider.setPrefWidth(180);
        TextField scrollbackField = new TextField(String.valueOf(currentScrollback));
        scrollbackField.setPrefWidth(64);
        Label scrollbackUnit = new Label(i18n.get("preferences.terminal.scrollbackLinesUnit"));

        scrollbackSlider.valueProperty().addListener((o, ov, nv) -> {
            int value = TerminalRuntimeSettings.clampScrollback((int) Math.round(nv.doubleValue() / 500.0) * 500);
            scrollbackField.setText(String.valueOf(value));
            pendingScrollbackLines[0] = String.valueOf(value);
            preferenceChanged.run();
        });
        scrollbackField.textProperty().addListener((o, ov, nv) -> {
            try {
                int value = TerminalRuntimeSettings.clampScrollback(Integer.parseInt(nv.trim()));
                if ((int) scrollbackSlider.getValue() != value) {
                    scrollbackSlider.setValue(value);
                }
                pendingScrollbackLines[0] = String.valueOf(value);
                preferenceChanged.run();
            } catch (NumberFormatException ignored) {}
        });

        fontGrid.add(new Label(i18n.get("preferences.terminal.scrollbackLines")), 0, 5);
        HBox scrollbackRow = new HBox(8, scrollbackSlider, scrollbackField, scrollbackUnit);
        scrollbackRow.setAlignment(Pos.CENTER_LEFT);
        fontGrid.add(scrollbackRow, 1, 5, 2, 1);

        // ── Color scheme section ──
        ColorSchemeRegistry registry = themeService.registry();

        ObservableList<TerminalColorScheme> allSchemes = FXCollections.observableArrayList(registry.allSchemes());
        FilteredList<TerminalColorScheme> filteredSchemes = new FilteredList<>(allSchemes);

        TextField filterField = new TextField();
        filterField.setPromptText(i18n.get("preferences.terminal.colorScheme.filter"));
        filterField.setPrefWidth(200);
        filterField.textProperty().addListener((o, ov, nv) -> {
            String lower = nv.toLowerCase();
            filteredSchemes.setPredicate(s -> lower.isBlank() || s.name().toLowerCase().contains(lower));
        });

        ListView<TerminalColorScheme> schemeList = new ListView<>(filteredSchemes);
        schemeList.setPrefHeight(200);
        schemeList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TerminalColorScheme item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    boolean builtIn = registry.isBuiltIn(item.name());
                    setText(item.name() + (builtIn ? " " + i18n.get("preferences.terminal.colorScheme.builtin")
                            : " " + i18n.get("preferences.terminal.colorScheme.custom")));
                }
            }
        });

        // Select current scheme
        String activeName = themeService.activeColorScheme().name();
        for (int i = 0; i < filteredSchemes.size(); i++) {
            if (filteredSchemes.get(i).name().equals(activeName)) {
                schemeList.getSelectionModel().select(i);
                break;
            }
        }

        // Preview
        ColorSchemePreview schemePreview = new ColorSchemePreview();
        schemePreview.update(themeService.activeColorScheme());
        schemeList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                schemePreview.update(nv);
                pendingScheme[0] = nv;
                preferenceChanged.run();
            }
        });

        // Opacity slider for selected scheme
        Slider opacitySlider = new Slider(0.0, 1.0, themeService.activeColorScheme().opacity());
        opacitySlider.setPrefWidth(140);
        Label opacityValue = new Label(String.format("%.0f%%", themeService.activeColorScheme().opacity() * 100));
        opacitySlider.valueProperty().addListener((o, ov, nv) -> {
            opacityValue.setText(String.format("%.0f%%", nv.doubleValue() * 100));
            TerminalColorScheme selected = schemeList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                TerminalColorScheme adjusted = withOpacity(selected, nv.doubleValue());
                pendingScheme[0] = adjusted;
                schemePreview.update(adjusted);
                preferenceChanged.run();
            }
        });

        // Action buttons
        Button newBtn = new Button(i18n.get("preferences.terminal.colorScheme.new"));
        Button dupBtn = new Button(i18n.get("preferences.terminal.colorScheme.duplicate"));
        Button editBtn = new Button(i18n.get("preferences.terminal.colorScheme.edit"));
        Button deleteBtn = new Button(i18n.get("preferences.terminal.colorScheme.delete"));

        editBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    TerminalColorScheme sel = schemeList.getSelectionModel().getSelectedItem();
                    return sel == null || registry.isBuiltIn(sel.name());
                },
                schemeList.getSelectionModel().selectedItemProperty()));

        deleteBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> {
                    TerminalColorScheme sel = schemeList.getSelectionModel().getSelectedItem();
                    return sel == null || registry.isBuiltIn(sel.name());
                },
                schemeList.getSelectionModel().selectedItemProperty()));

        dupBtn.disableProperty().bind(Bindings.isNull(schemeList.getSelectionModel().selectedItemProperty()));

        newBtn.setOnAction(e -> {
            TerminalColorScheme created = ColorSchemeEditDialog.show(
                    (Stage) schemeList.getScene().getWindow(), themeService, null, true);
            if (created != null) {
                registry.saveCustomScheme(created);
                refreshList(allSchemes, filteredSchemes, registry, filterField.getText());
                selectByName(schemeList, created.name());
            }
        });

        dupBtn.setOnAction(e -> {
            TerminalColorScheme sel = schemeList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            String newName = sel.name() + " " + i18n.get("preferences.terminal.colorScheme.duplicateName").replace("{0}", "").trim();
            // Ensure unique name
            int suffix = 1;
            String tryName = newName;
            while (registry.findByName(tryName).isPresent()) {
                tryName = newName + " " + suffix++;
            }
            TerminalColorScheme copy = registry.copyScheme(sel, tryName);
            TerminalColorScheme edited = ColorSchemeEditDialog.show(
                    (Stage) schemeList.getScene().getWindow(), themeService, copy, true);
            if (edited != null) {
                registry.saveCustomScheme(edited);
                refreshList(allSchemes, filteredSchemes, registry, filterField.getText());
                selectByName(schemeList, edited.name());
            }
        });

        editBtn.setOnAction(e -> {
            TerminalColorScheme sel = schemeList.getSelectionModel().getSelectedItem();
            if (sel == null || registry.isBuiltIn(sel.name())) return;
            TerminalColorScheme edited = ColorSchemeEditDialog.show(
                    (Stage) schemeList.getScene().getWindow(), themeService, sel, false);
            if (edited != null) {
                registry.saveCustomScheme(edited);
                refreshList(allSchemes, filteredSchemes, registry, filterField.getText());
                selectByName(schemeList, edited.name());
            }
        });

        deleteBtn.setOnAction(e -> {
            TerminalColorScheme sel = schemeList.getSelectionModel().getSelectedItem();
            if (sel == null || registry.isBuiltIn(sel.name())) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("preferences.terminal.colorScheme.deleteConfirm", sel.name()),
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle(i18n.get("preferences.terminal.colorScheme.delete"));
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                registry.deleteCustomScheme(sel.name());
                refreshList(allSchemes, filteredSchemes, registry, filterField.getText());
            }
        });

        HBox actionBtns = new HBox(6, newBtn, dupBtn, editBtn, deleteBtn);
        actionBtns.setAlignment(Pos.CENTER_LEFT);

        VBox schemeSection = new VBox(8);

        schemeSection.getChildren().addAll(
                filterField,
                schemeList,
                schemePreview,
                new HBox(8, new Label(i18n.get("preferences.terminal.colorScheme.opacity")), opacitySlider, opacityValue),
                actionBtns);

        // ── Combine ──
        VBox typographyCard = settingsCard(i18n.get("preferences.terminal.typographySection"),
                i18n.get("preferences.terminal.typographySection.description"), fontGrid);
        VBox colorsCard = settingsCard(i18n.get("preferences.terminal.colorsSection"),
                i18n.get("preferences.terminal.colorsSection.description"), schemeSection);
        VBox cards = new VBox(14, typographyCard, colorsCard);
        cards.getStyleClass().add("settings-card-grid");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll-pane");
        VBox directory = settingsSectionDirectory(i18n, scroll, cards, List.of(
                new SettingsSectionLink(i18n.get("preferences.terminal.typographySection"), typographyCard),
                new SettingsSectionLink(i18n.get("preferences.terminal.colorsSection"), colorsCard)));
        BorderPane workspace = new BorderPane();
        workspace.setLeft(directory);
        workspace.setCenter(scroll);
        workspace.getStyleClass().add("settings-directory-workspace");
        BorderPane.setMargin(directory, new Insets(16, 0, 16, 18));
        VBox pane = new VBox(workspace);
        pane.getStyleClass().add("settings-page");
        VBox.setVgrow(workspace, Priority.ALWAYS);
        return pane;
    }

    private static int parseScrollbackLines(String raw) {
        try {
            return TerminalRuntimeSettings.clampScrollback(Integer.parseInt(raw.trim()));
        } catch (Exception ignored) {
            return TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES;
        }
    }

    private static void refreshList(ObservableList<TerminalColorScheme> allSchemes,
                                    FilteredList<TerminalColorScheme> filtered,
                                    ColorSchemeRegistry registry, String filter) {
        allSchemes.setAll(registry.allSchemes());
        String lower = filter.toLowerCase();
        filtered.setPredicate(s -> lower.isBlank() || s.name().toLowerCase().contains(lower));
    }

    private static void selectByName(ListView<TerminalColorScheme> list, String name) {
        for (int i = 0; i < list.getItems().size(); i++) {
            if (list.getItems().get(i).name().equals(name)) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
    }

    private static TerminalColorScheme withOpacity(TerminalColorScheme scheme, double opacity) {
        return new TerminalColorScheme(
                scheme.name(),
                scheme.background(), scheme.foreground(), scheme.cursorColor(),
                scheme.selectionBackground(), scheme.selectionForeground(),
                scheme.hyperlinkColor(), scheme.searchMatchBackground(), scheme.searchMatchForeground(),
                scheme.black(), scheme.red(), scheme.green(), scheme.yellow(),
                scheme.blue(), scheme.purple(), scheme.cyan(), scheme.white(),
                scheme.brightBlack(), scheme.brightRed(), scheme.brightGreen(), scheme.brightYellow(),
                scheme.brightBlue(), scheme.brightPurple(), scheme.brightCyan(), scheme.brightWhite(),
                opacity
        );
    }

    // ── API Tab ────────────────────────────────────────────────────────────

    private static VBox buildApiPane(AppSettingsService appSettings, I18nService i18n,
                                     com.jlshell.api.server.ApiServer apiServer,
                                     CapabilityBus capabilityBus,
                                     String selectedSessionId,
                                     String[] pendingApiEnabled, String[] pendingApiPort,
                                     Runnable preferenceChanged) {
        CheckBox enableCb = new CheckBox(i18n.get("api.enabled"));
        enableCb.setSelected("true".equalsIgnoreCase(pendingApiEnabled[0]));
        enableCb.selectedProperty().addListener((o, ov, nv) -> {
            pendingApiEnabled[0] = String.valueOf(nv);
            preferenceChanged.run();
        });

        TextField portField = new TextField(pendingApiPort[0]);
        portField.setPrefWidth(80);
        portField.setPromptText(i18n.get("api.port.hint"));
        portField.disableProperty().bind(enableCb.selectedProperty().not());
        portField.textProperty().addListener((o, ov, nv) -> {
            pendingApiPort[0] = nv;
            preferenceChanged.run();
        });

        String current = apiServer != null && apiServer.enabled()
                ? i18n.get("api.current", String.valueOf(apiServer.port()))
                : i18n.get("api.disabled");
        Label currentLabel = new Label(current);
        currentLabel.setWrapText(true);

        Label tokenHint = new Label(i18n.get("api.tokenHint"));
        tokenHint.setWrapText(true);
        tokenHint.getStyleClass().add("api-config-hint");

        Button copyToken = new Button(i18n.get("api.copyToken"));
        copyToken.getStyleClass().add("api-copy-token-button");
        copyToken.setDisable(apiServer == null || apiServer.token() == null || apiServer.token().isEmpty());
        copyToken.setOnAction(e -> {
            String t = apiServer == null ? "" : apiServer.token();
            copyWithFeedback(copyToken, t, i18n);
        });

        Label restart = new Label(i18n.get("api.restartRequired"));
        restart.getStyleClass().add("api-config-hint");
        restart.setWrapText(true);

        Label portLabel = new Label(i18n.get("api.port"));
        portLabel.getStyleClass().add("api-config-label");
        HBox portControl = new HBox(7, portLabel, portField);
        portControl.setAlignment(Pos.CENTER_LEFT);
        Label statusLabel = new Label(i18n.get("api.status"));
        statusLabel.getStyleClass().add("api-config-label");
        HBox statusControl = new HBox(7, statusLabel, currentLabel);
        statusControl.setAlignment(Pos.CENTER_LEFT);
        Region configSpacer = new Region();
        HBox.setHgrow(configSpacer, Priority.ALWAYS);
        HBox configRow = new HBox(18, enableCb, portControl, statusControl, configSpacer, copyToken);
        configRow.getStyleClass().add("api-config-row");
        configRow.setAlignment(Pos.CENTER_LEFT);
        Label metaSeparator = new Label("•");
        metaSeparator.getStyleClass().add("api-config-hint");
        HBox metaRow = new HBox(8, tokenHint, metaSeparator, restart);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        VBox configContent = new VBox(8, configRow, metaRow);
        VBox configCard = settingsCard(i18n.get("api.config.title"),
                i18n.get("api.config.description"), configContent);
        VBox.setVgrow(configCard, Priority.NEVER);

        VBox apiBrowser = buildApiBrowser(i18n, apiServer, capabilityBus, selectedSessionId);
        VBox.setVgrow(apiBrowser, Priority.ALWAYS);
        VBox docsCard = settingsCard(i18n.get("api.docs.title"),
                i18n.get("api.docs.section.description"), apiBrowser);
        docsCard.setPrefHeight(500);
        VBox.setVgrow(docsCard, Priority.ALWAYS);

        VBox cards = new VBox(14, configCard, docsCard);
        cards.getStyleClass().add("settings-card-grid");
        ScrollPane pageScroll = new ScrollPane(cards);
        pageScroll.setFitToWidth(true);
        pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pageScroll.getStyleClass().addAll("settings-scroll-pane", "api-page-scroll");
        VBox pane = new VBox(pageScroll);
        pane.getStyleClass().add("settings-page");
        VBox.setVgrow(pageScroll, Priority.ALWAYS);
        return pane;
    }

    private static VBox buildApiBrowser(I18nService i18n, com.jlshell.api.server.ApiServer apiServer,
                                        CapabilityBus capabilityBus, String selectedSessionId) {
        ObservableList<ApiDocEntry> entries = FXCollections.observableArrayList(apiDocEntries(capabilityBus, selectedSessionId));
        FilteredList<ApiDocEntry> filtered = new FilteredList<>(entries, e -> true);

        TextField search = new TextField();
        search.setPromptText(i18n.get("api.docs.search"));
        search.textProperty().addListener((obs, oldValue, newValue) -> {
            String q = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(entry -> q.isBlank()
                    || entry.name().toLowerCase(Locale.ROOT).contains(q)
                    || entry.typeLabel(i18n).toLowerCase(Locale.ROOT).contains(q)
                    || entry.description().toLowerCase(Locale.ROOT).contains(q));
        });

        ListView<ApiDocEntry> list = new ListView<>(filtered);
        list.setPrefWidth(230);
        list.setPrefHeight(220);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ApiDocEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.name());
                name.setStyle("-fx-font-weight: bold;");
                Label type = new Label(item.typeLabel(i18n));
                type.setStyle("-fx-font-size: 0.82em; -fx-text-fill: gray;");
                VBox row = new VBox(2, name, type);
                setText(null);
                setGraphic(row);
            }
        });

        javafx.scene.text.TextFlow detailFlow = new javafx.scene.text.TextFlow();
        detailFlow.getStyleClass().add("api-code-flow");
        detailFlow.setLineSpacing(2);
        ScrollPane detailScroll = new ScrollPane(detailFlow);
        detailScroll.setFitToWidth(true);
        detailScroll.setPannable(false);
        detailScroll.getStyleClass().add("api-code-scroll");
        detailFlow.prefWidthProperty().bind(detailScroll.widthProperty().subtract(28));

        Label formatBadge = new Label("JSON-RPC");
        formatBadge.getStyleClass().add("api-code-format-badge");
        Button copyDetail = new Button(i18n.get("api.docs.copy"));
        copyDetail.getStyleClass().add("api-code-copy-button");
        String[] currentDetail = {""};
        copyDetail.setOnAction(event -> copyWithFeedback(copyDetail, currentDetail[0], i18n));
        Region codeHeaderSpacer = new Region();
        HBox.setHgrow(codeHeaderSpacer, Priority.ALWAYS);
        HBox codeHeader = new HBox(8, formatBadge, codeHeaderSpacer, copyDetail);
        codeHeader.setAlignment(Pos.CENTER_LEFT);
        VBox detail = new VBox(8, codeHeader, detailScroll);
        detail.getStyleClass().add("api-code-pane");
        detail.setMinWidth(460);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        java.util.function.Consumer<ApiDocEntry> updateDetail = entry -> {
            currentDetail[0] = entry == null ? "" : entry.detailText(i18n, apiServer);
            detailFlow.getChildren().setAll(entry == null
                    ? List.of()
                    : apiDetailNodes(i18n, entry, apiServer));
            detailScroll.setHvalue(0);
            detailScroll.setVvalue(0);
            copyDetail.setDisable(currentDetail[0].isBlank());
        };
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) ->
                updateDetail.accept(entry));
        if (!filtered.isEmpty()) {
            list.getSelectionModel().select(0);
        }

        SplitPane split = new SplitPane(list, detail);
        split.setDividerPositions(0.30);
        split.setPrefHeight(350);
        VBox.setVgrow(split, Priority.ALWAYS);

        Label hint = new Label(i18n.get("api.docs.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.85em;");

        VBox pane = new VBox(8, search, split, hint);
        return pane;
    }

    private static List<Text> highlightApiDetail(String content) {
        List<Text> nodes = new ArrayList<>();
        String source = content == null ? "" : content;
        java.util.regex.Pattern tokenPattern = java.util.regex.Pattern.compile(
                "(https?://[^\\s]+)|(\"(?:\\\\.|[^\"\\\\])*\")|\\b(true|false|null)\\b|"
                        + "(?<![\\w.])(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b|([{}\\[\\],:])");
        java.util.regex.Matcher matcher = tokenPattern.matcher(source);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                nodes.add(apiCodeText(source.substring(cursor, matcher.start()), "api-code-text"));
            }
            String styleClass;
            if (matcher.group(1) != null) styleClass = "api-code-url";
            else if (matcher.group(2) != null) styleClass = "api-code-string";
            else if (matcher.group(3) != null) styleClass = "api-code-keyword";
            else if (matcher.group(4) != null) styleClass = "api-code-number";
            else styleClass = "api-code-punctuation";
            nodes.add(apiCodeText(matcher.group(), styleClass));
            cursor = matcher.end();
        }
        if (cursor < source.length()) {
            nodes.add(apiCodeText(source.substring(cursor), "api-code-text"));
        }
        return nodes;
    }

    private static List<javafx.scene.Node> apiDetailNodes(
            I18nService i18n, ApiDocEntry entry, com.jlshell.api.server.ApiServer apiServer) {
        String detail = entry.detailText(i18n, apiServer);
        String methodMarker = i18n.get("api.docs.method") + ": " + entry.method();
        String endpointHeading = i18n.get("api.docs.endpoint") + ":";
        String requestHeading = i18n.get("api.docs.request") + ":";
        String schemaHeading = i18n.get("api.docs.inputSchema") + ":";
        List<javafx.scene.Node> nodes = new ArrayList<>();
        int cursor = 0;
        cursor = appendApiDetailCopy(nodes, detail, cursor, methodMarker,
                i18n, i18n.get("api.docs.copyMethod"), entry.method());
        cursor = appendApiDetailCopy(nodes, detail, cursor, endpointHeading,
                i18n, i18n.get("api.docs.copyEndpoint"), entry.endpoint(apiServer));
        cursor = appendApiDetailCopy(nodes, detail, cursor, requestHeading,
                i18n, i18n.get("api.docs.copyRequest"), entry.requestExample());
        cursor = appendApiDetailCopy(nodes, detail, cursor, schemaHeading,
                i18n, i18n.get("api.docs.copySchema"), entry.schemaText(i18n));
        nodes.addAll(highlightApiDetail(detail.substring(cursor)));
        return nodes;
    }

    private static int appendApiDetailCopy(List<javafx.scene.Node> nodes, String detail, int cursor,
                                           String marker, I18nService i18n, String tooltip, String value) {
        int markerStart = detail.indexOf(marker, cursor);
        if (markerStart < 0) return cursor;
        int markerEnd = markerStart + marker.length();
        nodes.addAll(highlightApiDetail(detail.substring(cursor, markerEnd)));
        nodes.add(apiCodeText("  ", "api-code-text"));
        nodes.add(apiInlineCopyButton(i18n, tooltip, value));
        return markerEnd;
    }

    private static Button apiInlineCopyButton(I18nService i18n, String tooltip, String value) {
        Button button = new Button(i18n.get("api.docs.copySection"));
        button.getStyleClass().add("api-inline-copy-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> copyWithFeedback(button, value, i18n));
        return button;
    }

    private static Text apiCodeText(String value, String styleClass) {
        Text text = new Text(value);
        text.getStyleClass().add("api-code-text");
        if (!"api-code-text".equals(styleClass)) text.getStyleClass().add(styleClass);
        return text;
    }

    private static void copyToClipboard(String value) {
        javafx.scene.input.ClipboardContent clipboardContent = new javafx.scene.input.ClipboardContent();
        clipboardContent.putString(value == null ? "" : value);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboardContent);
    }

    private static void copyWithFeedback(Button button, String value, I18nService i18n) {
        copyToClipboard(value);
        String originalText = button.getText();
        button.setText(i18n.get("api.copy.copied"));
        // 不使用 disable，避免禁用态透明度变化，也不会覆盖按钮原本的禁用绑定。
        button.setMouseTransparent(true);

        PauseTransition restore = new PauseTransition(Duration.seconds(1.4));
        restore.setOnFinished(ignored -> {
            button.setText(originalText);
            button.setMouseTransparent(false);
        });
        restore.play();
    }

    private static List<ApiDocEntry> apiDocEntries(CapabilityBus capabilityBus, String selectedSessionId) {
        List<ApiDocEntry> entries = new ArrayList<>();
        ProgramApiCatalog.definitions().stream()
                .map(PreferencesDialog::systemApiEntry)
                .forEach(entries::add);

        if (capabilityBus != null) {
            Map<String, Capability> capabilities = new LinkedHashMap<>();
            capabilityBus.listRegisteredCapabilities(null)
                    .forEach(capability -> capabilities.put(capabilityKey(capability), capability));
            if (selectedSessionId != null && !selectedSessionId.isBlank()) {
                capabilityBus.listRegisteredCapabilities(selectedSessionId)
                        .forEach(capability -> capabilities.put(capabilityKey(capability), capability));
            }
            capabilities.values().forEach(capability -> entries.add(pluginApiEntry(capability)));
        }
        return entries;
    }

    private static ApiDocEntry systemApiEntry(ProgramApiDefinition definition) {
        return new ApiDocEntry(ApiDocType.SYSTEM,
                definition.method(),
                definition.description(),
                definition.requiresSession(),
                definition.inputSchema(),
                definition.resultHint(),
                definition.paramsExample());
    }

    private static String capabilityKey(Capability capability) {
        return capability.pluginId() + "/" + capability.spec().name();
    }

    private static ApiDocEntry pluginApiEntry(Capability capability) {
        CapabilitySpec spec = capability.spec();
        return new ApiDocEntry(ApiDocType.PLUGIN,
                capability.pluginId() + "/" + spec.name(),
                spec.description() == null || spec.description().isBlank()
                        ? "Plugin capability" : spec.description(),
                spec.requiresSession(),
                spec.inputSchema(),
                "plugin result",
                pluginArgsExample(capability));
    }

    private static String pluginArgsExample(Capability capability) {
        CapabilitySpec spec = capability.spec();
        String args = "{}";
        return "{\"sessionId\":\"" + (spec.requiresSession() ? "<session-id>" : "<optional-session-id>")
                + "\",\"pluginId\":\"" + capability.pluginId()
                + "\",\"capability\":\"" + spec.name()
                + "\",\"args\":" + args + "}";
    }

    private enum ApiDocType {
        SYSTEM, PLUGIN
    }

    private record ApiDocEntry(ApiDocType type, String name, String description, boolean requiresSession,
                               com.google.gson.JsonObject inputSchema, String resultHint, String paramsExample) {
        String typeLabel(I18nService i18n) {
            return type == ApiDocType.SYSTEM ? i18n.get("api.docs.system") : i18n.get("api.docs.plugin");
        }

        String detailText(I18nService i18n, com.jlshell.api.server.ApiServer apiServer) {
            return i18n.get("api.docs.type") + ": " + typeLabel(i18n) + "\n"
                    + i18n.get("api.docs.name") + ": " + name + "\n"
                    + i18n.get("api.docs.method") + ": " + method() + "\n"
                    + i18n.get("api.docs.requiresSession") + ": " + (requiresSession ? i18n.get("api.docs.yes") : i18n.get("api.docs.no")) + "\n\n"
                    + i18n.get("api.docs.description") + ":\n" + description + "\n\n"
                    + i18n.get("api.docs.endpoint") + ":\n" + endpoint(apiServer) + "\n\n"
                    + i18n.get("api.docs.headers") + ":\nAuthorization: Bearer <token>\nContent-Type: application/json\n\n"
                    + i18n.get("api.docs.request") + ":\n" + requestExample() + "\n\n"
                    + i18n.get("api.docs.inputSchema") + ":\n"
                    + (inputSchema == null ? i18n.get("api.docs.noSchema") : prettyJson(inputSchema.toString())) + "\n\n"
                    + i18n.get("api.docs.result") + ":\n" + resultHint;
        }

        String endpoint(com.jlshell.api.server.ApiServer apiServer) {
            return apiServer != null && apiServer.enabled() && apiServer.port() > 0
                    ? "http://127.0.0.1:" + apiServer.port() + "/rpc"
                    : "http://127.0.0.1:<port>/rpc";
        }

        String requestExample() {
            String params = prettyJson(paramsExample).replace("\n", "\n  ");
            return "{\n"
                    + "  \"jsonrpc\": \"2.0\",\n"
                    + "  \"id\": 1,\n"
                    + "  \"method\": \"" + method() + "\",\n"
                    + "  \"params\": " + params + "\n"
                    + "}";
        }

        String schemaText(I18nService i18n) {
            return inputSchema == null ? i18n.get("api.docs.noSchema") : prettyJson(inputSchema.toString());
        }

        private String method() {
            return type == ApiDocType.PLUGIN ? "capability.invoke" : name;
        }
    }

    private static String prettyJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(com.google.gson.JsonParser.parseString(json));
        } catch (RuntimeException ignored) {
            return json.trim();
        }
    }

    // ── Plugins Tab ────────────────────────────────────────────────────────

    private static VBox buildPluginsPane(AppSettingsService appSettings, I18nService i18n,
                                         ThemeService themeService, Stage owner,
                                         ProgramPluginManager programPluginManager, PluginManager pluginManager) {
        VBox pane = new VBox();
        pane.getStyleClass().add("plugin-manager-pane");

        ObservableList<PluginDocEntry> installedEntries = FXCollections.observableArrayList(
                pluginDocEntries(programPluginManager, pluginManager));
        TabPane pluginTabs = new TabPane();
        pluginTabs.getStyleClass().add("plugin-manager-tabs");
        pluginTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab marketplace = new Tab(i18n.get("plugins.store.marketplace"));
        marketplace.setContent(buildPluginStorePane(appSettings, i18n, themeService, owner,
                programPluginManager, pluginManager, installedEntries));
        Tab installed = new Tab(i18n.get("plugins.store.installed", installedEntries.size()));
        installed.setContent(buildInstalledPluginsPane(appSettings, i18n, themeService, owner,
                programPluginManager, pluginManager, installedEntries));
        installedEntries.addListener((javafx.collections.ListChangeListener<PluginDocEntry>) change ->
                installed.setText(i18n.get("plugins.store.installed", installedEntries.size())));

        pluginTabs.getTabs().addAll(marketplace, installed);
        VBox.setVgrow(pluginTabs, Priority.ALWAYS);
        pane.getChildren().add(pluginTabs);
        return pane;
    }

    private static BorderPane buildPluginStorePane(AppSettingsService appSettings, I18nService i18n,
                                                    ThemeService themeService, Stage owner,
                                                    ProgramPluginManager programPluginManager, PluginManager pluginManager,
                                                    ObservableList<PluginDocEntry> installedEntries) {
        String home = System.getProperty("user.home");
        PluginStoreClient client = new PluginStoreClient(UpdateService.configuredBaseUrl(appSettings), ForkJoinPool.commonPool());
        PluginInstaller installer = new PluginInstaller(client,
                Path.of(home, ".jlshell", "program-plugins"), Path.of(home, ".jlshell", "plugins"));

        TextField query = new TextField();
        query.setPromptText(i18n.get("plugins.store.search"));
        query.getStyleClass().add("plugin-search-field");
        ComboBox<String> scope = new ComboBox<>(FXCollections.observableArrayList(
                i18n.get("plugins.filter.all"), i18n.get("plugins.filter.program"), i18n.get("plugins.filter.session")));
        scope.getSelectionModel().select(0);
        scope.setMinWidth(130);
        Button search = new Button(i18n.get("plugins.store.searchButton"));
        Label status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("plugin-status");
        ProgressBar installProgress = new ProgressBar(0);
        installProgress.setMaxWidth(Double.MAX_VALUE);
        installProgress.getStyleClass().add("plugin-install-progress");
        installProgress.setVisible(false);
        installProgress.setManaged(false);

        ObservableList<PluginStoreListing> listings = FXCollections.observableArrayList();
        ListView<PluginStoreListing> list = new ListView<>(listings);
        list.getStyleClass().add("plugin-manager-list");
        list.setMinWidth(330);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(PluginStoreListing item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.displayName());
                name.getStyleClass().add("plugin-item-name");
                Label description = new Label(item.description());
                description.getStyleClass().add("plugin-item-description");
                description.setMaxWidth(230);
                description.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                Label meta = new Label(item.scope().name() + "  ·  " + item.latestVersion()
                        + "  ·  ↓ " + compactCount(item.downloads()));
                meta.getStyleClass().add("plugin-item-meta");
                HBox metaRow = new HBox(7, meta);
                metaRow.setAlignment(Pos.CENTER_LEFT);
                if (isMarketplacePluginInstalled(item, installer, installedEntries)) {
                    metaRow.getChildren().add(pluginStateBadge(
                            i18n.get("plugins.store.installedBadge"), "plugin-state-installed"));
                }
                VBox text = new VBox(3, name, description, metaRow);
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = new HBox(12, pluginAvatar(item.displayName()), text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("plugin-list-cell-content");
                setText(null);
                setGraphic(row);
            }
        });

        VBox detailHost = new VBox();
        detailHost.getStyleClass().add("plugin-detail-pane");
        detailHost.getChildren().add(pluginEmptyState(i18n.get("plugins.store.selectHint")));
        ScrollPane detailScroll = new ScrollPane(detailHost);
        detailScroll.setFitToWidth(true);
        detailScroll.setFitToHeight(true);
        detailScroll.getStyleClass().add("plugin-detail-scroll");

        Button install = new Button(i18n.get("plugins.store.install"));
        install.getStyleClass().add("plugin-install-button");
        install.setDisable(true);
        PluginStoreVersion[] selectedVersion = new PluginStoreVersion[1];

        Runnable runSearch = () -> {
            search.setDisable(true);
            status.setText(i18n.get("plugins.store.loading"));
            com.jlshell.plugin.api.PluginScope requestedScope = scope.getValue().equals(i18n.get("plugins.filter.program"))
                    ? com.jlshell.plugin.api.PluginScope.PROGRAM
                    : scope.getValue().equals(i18n.get("plugins.filter.session"))
                    ? com.jlshell.plugin.api.PluginScope.SESSION : null;
            client.search(new PluginStoreSearch(query.getText(), requestedScope, getVersion(), i18n.getLocale(), 0, 20,
                            PluginStoreSearch.Sort.UPDATED))
                    .whenComplete((page, error) -> Platform.runLater(() -> {
                        search.setDisable(false);
                        if (error != null) {
                            status.setText(i18n.get("plugins.store.error", userMessage(error)));
                            return;
                        }
                        listings.setAll(page.content());
                        status.setText(i18n.get("plugins.store.results", page.totalElements()));
                        if (!listings.isEmpty()) list.getSelectionModel().select(0);
                    }));
        };
        search.setOnAction(event -> runSearch.run());
        query.setOnAction(event -> runSearch.run());

        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            selectedVersion[0] = null;
            install.setDisable(true);
            detailHost.getChildren().setAll(pluginEmptyState(i18n.get("plugins.store.loading")));
            if (selected == null) return;
            client.detail(selected.pluginId(), i18n.getLocale(), getVersion())
                    .whenComplete((storeDetail, error) -> Platform.runLater(() -> {
                        if (list.getSelectionModel().getSelectedItem() != selected) return;
                        if (error != null) {
                            detailHost.getChildren().setAll(pluginEmptyState(
                                    i18n.get("plugins.store.error", userMessage(error))));
                            return;
                        }
                        selectedVersion[0] = approvedVersion(storeDetail, selected.latestVersion());
                        detailHost.getChildren().setAll(marketplaceDetail(
                                i18n, selected, selectedVersion[0], install,
                                isMarketplacePluginInstalled(selected, installer, installedEntries)));
                        install.setDisable(selectedVersion[0] == null);
                    }));
        });

        installedEntries.addListener((javafx.collections.ListChangeListener<PluginDocEntry>) change -> {
            list.refresh();
            PluginStoreListing selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && selectedVersion[0] != null) {
                detailHost.getChildren().setAll(marketplaceDetail(
                        i18n, selected, selectedVersion[0], install,
                        isMarketplacePluginInstalled(selected, installer, installedEntries)));
            }
        });

        install.setOnAction(event -> {
            PluginStoreListing selected = list.getSelectionModel().getSelectedItem();
            PluginStoreVersion version = selectedVersion[0];
            if (selected == null || version == null) return;
            List<PluginInstaller.InstalledArtifact> existing = installer.findInstalled(selected.pluginId());
            boolean loadedLocally = installedEntries.stream()
                    .anyMatch(entry -> entry.metadata().id().equals(selected.pluginId()));
            if (loadedLocally && existing.isEmpty()) {
                status.setText(i18n.get("plugins.store.bundledConflict"));
                return;
            }
            if (existing.stream().anyMatch(artifact -> artifact.scope() != selected.scope())) {
                status.setText(i18n.get("plugins.store.scopeConflict"));
                return;
            }
            if (!existing.isEmpty() && !confirmPluginAction(
                    Alert.AlertType.CONFIRMATION,
                    i18n.get("plugins.store.replaceTitle"),
                    i18n.get("plugins.store.replaceMessage", selected.displayName(),
                            existing.stream().map(PluginInstaller.InstalledArtifact::version)
                                    .filter(value -> value != null && !value.isBlank()).distinct()
                                    .collect(Collectors.joining(", ")),
                            version.version()),
                    i18n.get("plugins.store.replace"), owner, i18n, themeService)) {
                return;
            }
            install.setDisable(true);
            status.setText(i18n.get("plugins.store.installing"));
            installProgress.setProgress(0);
            installProgress.setManaged(true);
            installProgress.setVisible(true);
            Runnable beforeReplace = selected.scope() == com.jlshell.plugin.api.PluginScope.SESSION && pluginManager != null
                    ? uiThreadAction(() -> pluginManager.deactivatePlugin(selected.pluginId())) : () -> { };
            java.util.function.DoubleConsumer progressListener = progress -> Platform.runLater(() -> {
                installProgress.setProgress(progress);
                status.setText(i18n.get("plugins.store.installProgress", Math.round(progress * 100)));
            });
            java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> installer.install(selected, version, beforeReplace, progressListener),
                            ForkJoinPool.commonPool())
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        installProgress.setVisible(false);
                        installProgress.setManaged(false);
                        if (error != null) {
                            status.setText(i18n.get("plugins.store.error", userMessage(error)));
                            install.setDisable(false);
                            return;
                        }
                        if (result.scope() == com.jlshell.plugin.api.PluginScope.SESSION && pluginManager != null) {
                            pluginManager.reloadPlugins();
                            installedEntries.setAll(pluginDocEntries(programPluginManager, pluginManager));
                            status.setText(i18n.get("plugins.store.installedSession"));
                        } else {
                            status.setText(i18n.get("plugins.store.installedProgram"));
                        }
                        list.refresh();
                        detailHost.getChildren().setAll(marketplaceDetail(
                                i18n, selected, version, install, true));
                        install.setDisable(false);
                    }));
        });

        HBox controls = new HBox(8, query, scope, search);
        controls.getStyleClass().add("plugin-toolbar");
        HBox.setHgrow(query, Priority.ALWAYS);
        VBox left = new VBox(8, controls, list, installProgress, status);
        left.getStyleClass().add("plugin-list-pane");
        VBox.setVgrow(list, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, detailScroll);
        split.getStyleClass().add("plugin-content-split");
        split.setDividerPositions(0.38);
        BorderPane root = new BorderPane(split);
        root.getStyleClass().add("plugin-marketplace-root");
        Platform.runLater(runSearch);
        return root;
    }

    private static BorderPane buildInstalledPluginsPane(AppSettingsService appSettings, I18nService i18n,
                                                        ThemeService themeService, Stage owner,
                                                        ProgramPluginManager programPluginManager,
                                                        PluginManager pluginManager,
                                                        ObservableList<PluginDocEntry> entries) {
        String home = System.getProperty("user.home");
        PluginStoreClient storeClient = new PluginStoreClient(
                UpdateService.configuredBaseUrl(appSettings), ForkJoinPool.commonPool());
        PluginInstaller installer = new PluginInstaller(storeClient,
                Path.of(home, ".jlshell", "program-plugins"), Path.of(home, ".jlshell", "plugins"));
        FilteredList<PluginDocEntry> filtered = new FilteredList<>(entries, item -> true);
        TextField search = new TextField();
        search.setPromptText(i18n.get("plugins.store.searchInstalled"));
        ComboBox<String> scope = new ComboBox<>(FXCollections.observableArrayList(
                i18n.get("plugins.filter.all"), i18n.get("plugins.filter.program"), i18n.get("plugins.filter.session")));
        scope.getSelectionModel().select(0);
        scope.setMinWidth(130);
        Button updates = new Button(i18n.get("plugins.store.checkUpdates"));
        Label status = new Label();
        status.getStyleClass().add("plugin-status");
        status.setWrapText(true);

        Runnable applyFilter = () -> {
            String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
            String selectedScope = scope.getValue();
            filtered.setPredicate(item -> (q.isBlank()
                    || item.metadata().displayName().toLowerCase(Locale.ROOT).contains(q)
                    || item.metadata().id().toLowerCase(Locale.ROOT).contains(q))
                    && (selectedScope == null || selectedScope.equals(i18n.get("plugins.filter.all"))
                    || (selectedScope.equals(i18n.get("plugins.filter.program"))
                    && item.scope() == com.jlshell.plugin.api.PluginScope.PROGRAM)
                    || (selectedScope.equals(i18n.get("plugins.filter.session"))
                    && item.scope() == com.jlshell.plugin.api.PluginScope.SESSION)));
        };
        search.textProperty().addListener((obs, oldValue, newValue) -> applyFilter.run());
        scope.valueProperty().addListener((obs, oldValue, newValue) -> applyFilter.run());

        ListView<PluginDocEntry> list = new ListView<>(filtered);
        list.getStyleClass().add("plugin-manager-list");
        list.setMinWidth(330);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(PluginDocEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.metadata().displayName());
                name.getStyleClass().add("plugin-item-name");
                Label id = new Label(item.metadata().id());
                id.getStyleClass().add("plugin-item-description");
                id.setMaxWidth(230);
                id.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                Label badge = new Label(item.scopeLabel(i18n));
                badge.getStyleClass().addAll("plugin-scope-badge",
                        item.scope() == com.jlshell.plugin.api.PluginScope.PROGRAM
                                ? "plugin-scope-program" : "plugin-scope-session");
                Label version = new Label(item.metadata().version());
                version.getStyleClass().add("plugin-version-label");
                Label state = pluginStateBadge(
                        i18n.get(item.enabled() ? "plugins.store.enabled" : "plugins.store.disabled"),
                        item.enabled() ? "plugin-state-enabled" : "plugin-state-disabled");
                HBox meta = new HBox(7, badge, state, version);
                meta.setAlignment(Pos.CENTER_LEFT);
                VBox text = new VBox(3, name, id, meta);
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = new HBox(12, pluginAvatar(item.metadata().displayName()), text);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("plugin-list-cell-content");
                setText(null);
                setGraphic(row);
            }
        });

        VBox detailHost = new VBox();
        detailHost.getStyleClass().add("plugin-detail-pane");
        detailHost.getChildren().add(pluginEmptyState(i18n.get("plugins.store.selectInstalledHint")));
        ScrollPane detailScroll = new ScrollPane(detailHost);
        detailScroll.setFitToWidth(true);
        detailScroll.setFitToHeight(true);
        detailScroll.getStyleClass().add("plugin-detail-scroll");

        java.util.function.Consumer<PluginDocEntry> uninstallEntry = entry -> {
            if (entry == null) return;
            if (!confirmPluginAction(
                    Alert.AlertType.CONFIRMATION,
                    i18n.get("plugins.store.uninstallTitle"),
                    i18n.get("plugins.store.uninstallMessage", entry.metadata().displayName()),
                    i18n.get("plugins.store.uninstall"), owner, i18n, themeService)) {
                return;
            }
            status.setText(i18n.get("plugins.store.uninstalling"));
            Runnable beforeDelete = entry.scope() == com.jlshell.plugin.api.PluginScope.SESSION && pluginManager != null
                    ? uiThreadAction(() -> pluginManager.deactivatePlugin(entry.metadata().id())) : () -> { };
            java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> installer.uninstall(entry.metadata().id(), entry.scope(), beforeDelete),
                            ForkJoinPool.commonPool())
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            status.setText(i18n.get("plugins.store.error", userMessage(error)));
                            return;
                        }
                        if (!result.removed()) {
                            status.setText(i18n.get("plugins.store.notRemovable"));
                            return;
                        }
                        if (entry.scope() == com.jlshell.plugin.api.PluginScope.SESSION && pluginManager != null) {
                            pluginManager.reloadPlugins();
                            status.setText(i18n.get("plugins.store.uninstalledSession"));
                        } else {
                            status.setText(i18n.get("plugins.store.uninstalledProgram"));
                        }
                        entries.removeIf(item -> item.scope() == entry.scope()
                                && item.metadata().id().equals(entry.metadata().id()));
                        list.getSelectionModel().clearSelection();
                        detailHost.getChildren().setAll(
                                pluginEmptyState(i18n.get("plugins.store.selectInstalledHint")));
                    }));
        };
        java.util.function.Consumer<PluginDocEntry> toggleEntry = entry -> {
            if (entry == null) return;
            boolean enable = !entry.enabled();
            if (!enable && !confirmPluginAction(
                    Alert.AlertType.CONFIRMATION,
                    i18n.get("plugins.store.disableTitle"),
                    i18n.get("plugins.store.disableMessage", entry.metadata().displayName()),
                    i18n.get("plugins.store.disable"), owner, i18n, themeService)) {
                return;
            }
            try {
                if (entry.scope() == com.jlshell.plugin.api.PluginScope.SESSION) {
                    if (pluginManager == null) return;
                    pluginManager.setPluginEnabled(entry.metadata().id(), enable);
                } else {
                    if (programPluginManager == null) return;
                    programPluginManager.setPluginEnabled(entry.metadata().id(), enable);
                }
                entries.setAll(pluginDocEntries(programPluginManager, pluginManager));
                entries.stream()
                        .filter(item -> item.scope() == entry.scope()
                                && item.metadata().id().equals(entry.metadata().id()))
                        .findFirst().ifPresent(item -> list.getSelectionModel().select(item));
                status.setText(i18n.get(enable
                        ? "plugins.store.enabledMessage" : "plugins.store.disabledMessage",
                        entry.metadata().displayName()));
            } catch (RuntimeException error) {
                status.setText(i18n.get("plugins.store.error", userMessage(error)));
            }
        };
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) -> {
            if (entry == null) {
                detailHost.getChildren().setAll(
                        pluginEmptyState(i18n.get("plugins.store.selectInstalledHint")));
                return;
            }
            boolean removable = installer.findInstalled(entry.metadata().id()).stream()
                    .anyMatch(artifact -> artifact.scope() == entry.scope());
            detailHost.getChildren().setAll(installedPluginDetail(
                    i18n, entry, removable, () -> uninstallEntry.accept(entry),
                    () -> toggleEntry.accept(entry)));
        });
        if (!filtered.isEmpty()) list.getSelectionModel().select(0);

        updates.setOnAction(event -> {
            PluginStoreClient client = new PluginStoreClient(UpdateService.configuredBaseUrl(appSettings), ForkJoinPool.commonPool());
            checkPluginUpdates(client, i18n, programPluginManager, pluginManager, status);
        });
        HBox controls = new HBox(8, search, scope, updates);
        controls.getStyleClass().add("plugin-toolbar");
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox left = new VBox(8, controls, list, status);
        left.getStyleClass().add("plugin-list-pane");
        VBox.setVgrow(list, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, detailScroll);
        split.getStyleClass().add("plugin-content-split");
        split.setDividerPositions(0.38);
        BorderPane root = new BorderPane(split);
        root.getStyleClass().add("plugin-marketplace-root");
        return root;
    }

    private static VBox marketplaceDetail(I18nService i18n, PluginStoreListing plugin,
                                          PluginStoreVersion version, Button install, boolean installed) {
        Label name = new Label(plugin.displayName());
        name.getStyleClass().add("plugin-detail-title");
        Label author = new Label(plugin.author() + "  ·  " + plugin.scope().name()
                + "  ·  ↓ " + compactCount(plugin.downloads()));
        author.getStyleClass().add("plugin-detail-author");
        VBox headingText = new VBox(4, name, author);
        HBox.setHgrow(headingText, Priority.ALWAYS);
        HBox heading = new HBox(12, pluginAvatar(plugin.displayName()), headingText);
        if (installed) {
            heading.getChildren().add(pluginStateBadge(
                    i18n.get("plugins.store.installedBadge"), "plugin-state-installed"));
        }
        heading.getChildren().add(install);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(plugin.description());
        description.setWrapText(true);
        description.getStyleClass().add("plugin-detail-description");
        Label versionTitle = new Label(i18n.get("plugins.field.version"));
        versionTitle.getStyleClass().add("plugin-detail-section-title");
        Label versionText = new Label(version == null ? i18n.get("plugins.store.noApprovedVersion")
                : version.version() + "  ·  " + humanSize(version.size()));
        versionText.getStyleClass().add("plugin-detail-meta");
        Label notesTitle = new Label(i18n.get("plugins.store.releaseNotes"));
        notesTitle.getStyleClass().add("plugin-detail-section-title");
        Label notes = new Label(version == null || isBlank(version.releaseNotes()) ? "—" : version.releaseNotes());
        notes.setWrapText(true);
        notes.getStyleClass().add("plugin-detail-description");
        Label id = new Label(plugin.pluginId());
        id.getStyleClass().add("plugin-detail-id");
        return new VBox(18, heading, new Separator(), description, versionTitle, versionText,
                notesTitle, notes, new Separator(), id);
    }

    private static VBox installedPluginDetail(I18nService i18n, PluginDocEntry entry,
                                              boolean removable, Runnable uninstallAction,
                                              Runnable toggleAction) {
        Label name = new Label(entry.metadata().displayName());
        name.getStyleClass().add("plugin-detail-title");
        Label author = new Label(entry.metadata().author() + "  ·  " + entry.scopeLabel(i18n));
        author.getStyleClass().add("plugin-detail-author");
        VBox headingText = new VBox(4, name, author);
        HBox.setHgrow(headingText, Priority.ALWAYS);
        Label state = pluginStateBadge(
                i18n.get(entry.enabled() ? "plugins.store.enabled" : "plugins.store.disabled"),
                entry.enabled() ? "plugin-state-enabled" : "plugin-state-disabled");
        Button toggle = new Button(i18n.get(entry.enabled()
                ? "plugins.store.disable" : "plugins.store.enable"));
        toggle.getStyleClass().add(entry.enabled() ? "plugin-disable-button" : "plugin-enable-button");
        toggle.setOnAction(event -> toggleAction.run());
        Button uninstall = new Button(i18n.get("plugins.store.uninstall"));
        uninstall.getStyleClass().add("plugin-uninstall-button");
        uninstall.setDisable(!removable);
        uninstall.setOnAction(event -> uninstallAction.run());
        HBox heading = new HBox(10, pluginAvatar(entry.metadata().displayName()), headingText,
                state, toggle, uninstall);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(entry.metadata().description());
        description.setWrapText(true);
        description.getStyleClass().add("plugin-detail-description");
        GridPane metadata = new GridPane();
        metadata.setHgap(16);
        metadata.setVgap(9);
        addPluginMetadataRow(metadata, 0, i18n.get("plugins.field.id"), entry.metadata().id());
        addPluginMetadataRow(metadata, 1, i18n.get("plugins.field.version"), entry.metadata().version());
        addPluginMetadataRow(metadata, 2, i18n.get("plugins.field.hostRange"), entry.hostRange(i18n));
        addPluginMetadataRow(metadata, 3, i18n.get("plugins.field.status"), entry.metadata().compatibilityStatus().name());
        addPluginMetadataRow(metadata, 4, i18n.get("plugins.store.runtimeState"),
                i18n.get(entry.enabled() ? "plugins.store.enabled" : "plugins.store.disabled"));

        VBox result = new VBox(18, heading, new Separator(), description, metadata);
        if (!isBlank(entry.metadata().compatibilityWarning())) {
            Label warning = new Label(entry.metadata().compatibilityWarning());
            warning.setWrapText(true);
            warning.getStyleClass().add("plugin-detail-warning");
            result.getChildren().add(warning);
        }
        if (entry.settingsNode() != null) {
            Label settingsTitle = new Label(i18n.get("plugins.settings"));
            settingsTitle.getStyleClass().add("plugin-detail-section-title");
            result.getChildren().addAll(new Separator(), settingsTitle, entry.settingsNode());
        }
        return result;
    }

    private static void addPluginMetadataRow(GridPane grid, int row, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("plugin-detail-meta-key");
        Label valueLabel = new Label(value == null ? "" : value);
        valueLabel.setWrapText(true);
        valueLabel.getStyleClass().add("plugin-detail-meta-value");
        grid.addRow(row, keyLabel, valueLabel);
    }

    private static boolean isMarketplacePluginInstalled(PluginStoreListing plugin,
                                                        PluginInstaller installer,
                                                        List<PluginDocEntry> installedEntries) {
        boolean loadedOrBundled = installedEntries.stream().anyMatch(entry ->
                entry.scope() == plugin.scope() && entry.metadata().id().equals(plugin.pluginId()));
        if (loadedOrBundled) return true;
        return installer.findInstalled(plugin.pluginId()).stream()
                .anyMatch(artifact -> artifact.scope() == plugin.scope());
    }

    private static Label pluginStateBadge(String text, String stateStyleClass) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("plugin-state-badge", stateStyleClass);
        return badge;
    }

    private static StackPane pluginAvatar(String name) {
        String text = name == null || name.isBlank() ? "P" : name.strip().substring(0, 1).toUpperCase(Locale.ROOT);
        Label initial = new Label(text);
        initial.getStyleClass().add("plugin-avatar-text");
        StackPane avatar = new StackPane(initial);
        avatar.getStyleClass().add("plugin-avatar");
        return avatar;
    }

    private static StackPane pluginEmptyState(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("plugin-empty-state");
        StackPane pane = new StackPane(label);
        pane.setMinHeight(360);
        return pane;
    }

    private static String compactCount(long value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fK", value / 1_000.0);
        return Long.toString(value);
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PluginStoreVersion approvedVersion(PluginStoreDetail detail, String latestVersion) {
        if (detail == null) return null;
        return detail.versions().stream()
                .filter(PluginStoreVersion::approved)
                .filter(version -> version.version().equals(latestVersion))
                .findFirst().orElse(null);
    }

    private static boolean confirmPluginAction(Alert.AlertType type, String title, String message,
                                               String confirmText, Stage owner, I18nService i18n,
                                               ThemeService themeService) {
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(i18n.get("plugins.store.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(type, message, confirm, cancel);
        alert.setTitle(title);
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        themeService.applyToDialog(alert);
        return alert.showAndWait().orElse(cancel) == confirm;
    }

    private static Runnable uiThreadAction(Runnable action) {
        return () -> {
            if (Platform.isFxApplicationThread()) {
                action.run();
                return;
            }
            CountDownLatch completed = new CountDownLatch(1);
            Platform.runLater(() -> {
                try { action.run(); } finally { completed.countDown(); }
            });
            try {
                completed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("插件停用操作被中断", e);
            }
        };
    }

    private static void checkPluginUpdates(PluginStoreClient client, I18nService i18n,
                                           ProgramPluginManager programPluginManager, PluginManager pluginManager,
                                           Label status) {
        List<com.jlshell.plugin.api.PluginMetadata> installed = new ArrayList<>();
        if (programPluginManager != null) programPluginManager.getAvailablePlugins().forEach(p -> installed.add(p.metadata()));
        if (pluginManager != null) pluginManager.getAvailablePlugins().forEach(p -> installed.add(p.metadata()));
        if (installed.isEmpty()) {
            status.setText(i18n.get("plugins.store.noInstalled"));
            return;
        }
        status.setText(i18n.get("plugins.store.checkingUpdates"));
        List<java.util.concurrent.CompletableFuture<PluginStoreUpdate>> checks = installed.stream()
                .map(metadata -> client.latestUpdate(metadata.id(), metadata.version(), getVersion())
                        .exceptionally(error -> null))
                .toList();
        java.util.concurrent.CompletableFuture.allOf(checks.toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    long count = checks.stream()
                            .map(java.util.concurrent.CompletableFuture::join)
                            .filter(update -> update != null && update.updateAvailable())
                            .count();
                    status.setText(count == 0 ? i18n.get("plugins.store.upToDate")
                            : i18n.get("plugins.store.updatesAvailable", count));
                }));
    }

    private static String userMessage(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static List<PluginDocEntry> pluginDocEntries(ProgramPluginManager programPluginManager,
                                                         PluginManager pluginManager) {
        List<PluginDocEntry> entries = new ArrayList<>();
        if (programPluginManager != null) {
            programPluginManager.getInstalledPlugins().forEach(desc -> {
                boolean enabled = programPluginManager.isPluginEnabled(desc.id());
                javafx.scene.Node settings = enabled ? desc.instance().settingsView(desc.context()) : null;
                entries.add(new PluginDocEntry(desc.metadata(), settings, enabled));
            });
        }
        if (pluginManager != null) {
            pluginManager.getInstalledPlugins().forEach(desc -> entries.add(
                    new PluginDocEntry(desc.metadata(), null, pluginManager.isPluginEnabled(desc.id()))));
        }
        return entries;
    }

    private record PluginDocEntry(com.jlshell.plugin.api.PluginMetadata metadata,
                                  javafx.scene.Node settingsNode, boolean enabled) {
        com.jlshell.plugin.api.PluginScope scope() {
            return metadata.scope();
        }

        String scopeLabel(I18nService i18n) {
            return scope() == com.jlshell.plugin.api.PluginScope.PROGRAM
                    ? i18n.get("plugins.scope.program")
                    : i18n.get("plugins.scope.session");
        }

        String hostRange(I18nService i18n) {
            return (blank(metadata.minHostVersionInclusive()) && blank(metadata.maxHostVersionInclusive()))
                    ? i18n.get("plugins.compat.undeclared")
                    : (blank(metadata.minHostVersionInclusive()) ? "*" : metadata.minHostVersionInclusive())
                    + " - "
                    + (blank(metadata.maxHostVersionInclusive()) ? "*" : metadata.maxHostVersionInclusive());
        }

        String detailText(I18nService i18n) {
            String range = hostRange(i18n);
            String warning = blank(metadata.compatibilityWarning()) ? "" : "\n\n" + i18n.get("plugins.warning")
                    + ":\n" + metadata.compatibilityWarning();
            return i18n.get("plugins.field.id") + ": " + metadata.id() + "\n"
                    + i18n.get("plugins.field.name") + ": " + metadata.displayName() + "\n"
                    + i18n.get("plugins.field.version") + ": " + metadata.version() + "\n"
                    + i18n.get("plugins.field.author") + ": " + metadata.author() + "\n"
                    + i18n.get("plugins.field.scope") + ": " + scopeLabel(i18n) + "\n"
                    + i18n.get("plugins.field.hostRange") + ": " + range + "\n"
                    + i18n.get("plugins.field.status") + ": " + metadata.compatibilityStatus().name() + "\n\n"
                    + i18n.get("plugins.field.description") + ":\n" + metadata.description()
                    + warning;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    // ── Import Tab ─────────────────────────────────────────────────────────

    private static VBox buildImportPane(I18nService i18n,
                                        com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                                        String activeProjectId) {
        VBox pane = new VBox(16);
        pane.getStyleClass().add("settings-page");

        // 三个 section 共享一个结果标签，显示最近一次导入结果
        Label globalResult = new Label();
        globalResult.setStyle("-fx-text-fill: #38bdf8; -fx-font-size: 0.92em;");

        if (connectionProfileService == null) {
            // 从终端 Tab 打开的偏好设置没有连接服务上下文，提示用户从主菜单打开
            Label hint = new Label(i18n.get("import.noFile"));
            hint.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 0.92em;");
            VBox unavailableCard = settingsCard(i18n.get("preferences.tab.import"), "", hint);
            VBox cards = new VBox(unavailableCard);
            cards.getStyleClass().add("settings-card-grid");
            pane.getChildren().add(cards);
            return pane;
        }

        // ── MobaXterm section ──
        VBox mobaxtermSection = buildMobaxtermSection(i18n, connectionProfileService, activeProjectId, globalResult);

        // ── Xshell section ──
        VBox xshellSection = buildXshellSection(i18n, connectionProfileService, activeProjectId, globalResult);

        // ── Manual section ──
        VBox manualSection = buildManualSection(i18n, connectionProfileService, activeProjectId, globalResult);

        VBox cards = new VBox(14, mobaxtermSection, xshellSection, manualSection, globalResult);
        cards.getStyleClass().add("settings-card-grid");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll-pane");
        VBox directory = settingsSectionDirectory(i18n, scroll, cards, List.of(
                new SettingsSectionLink(i18n.get("import.section.mobaxterm"), mobaxtermSection),
                new SettingsSectionLink(i18n.get("import.section.xshell"), xshellSection),
                new SettingsSectionLink(i18n.get("import.section.manual"), manualSection)));
        BorderPane workspace = new BorderPane();
        workspace.setLeft(directory);
        workspace.setCenter(scroll);
        workspace.getStyleClass().add("settings-directory-workspace");
        BorderPane.setMargin(directory, new Insets(16, 0, 16, 18));
        pane.getChildren().add(workspace);
        VBox.setVgrow(workspace, Priority.ALWAYS);
        return pane;
    }

    private static VBox buildMobaxtermSection(I18nService i18n,
                                              com.jlshell.ui.service.ConnectionProfileService service,
                                              String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.mobaxterm"));
        title.getStyleClass().add("settings-card-title");

        Label hint = new Label(i18n.get("import.mobaxterm.hint"));
        hint.getStyleClass().add("settings-card-description");
        hint.setWrapText(true);

        Label foundLabel = new Label();
        javafx.scene.control.Button browseBtn = new javafx.scene.control.Button(i18n.get("import.mobaxterm.browse"));
        javafx.scene.control.Button importBtn = new javafx.scene.control.Button(i18n.get("import.button.import"));
        importBtn.setDisable(true);

        // 持有待导入的表单列表（数组绕过 lambda effectively-final 限制）
        java.util.List<com.jlshell.ui.model.ConnectionFormData>[] pending =
                new java.util.List[]{java.util.List.of()};

        browseBtn.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(i18n.get("import.mobaxterm.browse"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    i18n.get("import.filter.ini"), "*.ini"));
            java.io.File file = fc.showOpenDialog(browseBtn.getScene().getWindow());
            if (file == null) return;
            try {
                pending[0] = new com.jlshell.ui.service.importer.MobaXtermIniParser(projectId).parse(file.toPath());
                foundLabel.setText(i18n.get("import.found", pending[0].size()));
                importBtn.setDisable(pending[0].isEmpty());
            } catch (Exception ex) {
                foundLabel.setText(ex.getMessage());
                importBtn.setDisable(true);
            }
        });

        importBtn.setOnAction(e -> {
            if (pending[0].isEmpty()) return;
            performImport(service, projectId, pending[0], "MobaXterm", globalResult, i18n);
        });

        HBox buttonRow = new HBox(8, browseBtn, importBtn);
        VBox section = new VBox(6, title, hint, buttonRow, foundLabel);
        section.getStyleClass().add("settings-card");
        return section;
    }

    private static VBox buildXshellSection(I18nService i18n,
                                           com.jlshell.ui.service.ConnectionProfileService service,
                                           String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.xshell"));
        title.getStyleClass().add("settings-card-title");

        Label hint = new Label(i18n.get("import.xshell.hint"));
        hint.getStyleClass().add("settings-card-description");
        hint.setWrapText(true);

        Label foundLabel = new Label();
        javafx.scene.control.Button browseBtn = new javafx.scene.control.Button(i18n.get("import.xshell.browse"));
        javafx.scene.control.Button browseDirBtn = new javafx.scene.control.Button(i18n.get("import.xshell.browseDir"));
        javafx.scene.control.Button importBtn = new javafx.scene.control.Button(i18n.get("import.button.import"));
        importBtn.setDisable(true);

        java.util.List<com.jlshell.ui.model.ConnectionFormData>[] pending =
                new java.util.List[]{java.util.List.of()};

        browseBtn.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(i18n.get("import.xshell.browse"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    i18n.get("import.filter.xsh"), "*.xsh"));
            java.io.File file = fc.showOpenDialog(browseBtn.getScene().getWindow());
            if (file == null) return;
            try {
                pending[0] = new com.jlshell.ui.service.importer.XshellXshParser(projectId).parseFile(file.toPath());
                foundLabel.setText(i18n.get("import.found", pending[0].size()));
                importBtn.setDisable(pending[0].isEmpty());
            } catch (Exception ex) {
                foundLabel.setText(ex.getMessage());
                importBtn.setDisable(true);
            }
        });

        browseDirBtn.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle(i18n.get("import.xshell.browseDir"));
            java.io.File dir = dc.showDialog(browseDirBtn.getScene().getWindow());
            if (dir == null) return;
            try {
                pending[0] = new com.jlshell.ui.service.importer.XshellXshParser(projectId).parseDirectory(dir.toPath());
                foundLabel.setText(i18n.get("import.found", pending[0].size()));
                importBtn.setDisable(pending[0].isEmpty());
            } catch (Exception ex) {
                foundLabel.setText(ex.getMessage());
                importBtn.setDisable(true);
            }
        });

        importBtn.setOnAction(e -> {
            if (pending[0].isEmpty()) return;
            performImport(service, projectId, pending[0], "Xshell", globalResult, i18n);
        });

        HBox buttonRow = new HBox(8, browseBtn, browseDirBtn, importBtn);
        VBox section = new VBox(6, title, hint, buttonRow, foundLabel);
        section.getStyleClass().add("settings-card");
        return section;
    }

    private static VBox buildManualSection(I18nService i18n,
                                           com.jlshell.ui.service.ConnectionProfileService service,
                                           String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.manual"));
        title.getStyleClass().add("settings-card-title");

        Label hint = new Label(i18n.get("import.manual.hint"));
        hint.getStyleClass().add("settings-card-description");
        hint.setWrapText(true);

        // 手动表格：name/host/port/user/authType/password/passphrase + 删除按钮
        ObservableList<ManualRow> rows = FXCollections.observableArrayList();
        javafx.scene.control.TableView<ManualRow> table = new javafx.scene.control.TableView<>(rows);
        table.setEditable(true);
        table.getStyleClass().add("import-manual-table");
        table.setPrefHeight(200);
        table.setMinWidth(Region.USE_COMPUTED_SIZE);
        // 列宽固定，超出时显示横向滚动条
        table.setColumnResizePolicy(javafx.scene.control.TableView.UNCONSTRAINED_RESIZE_POLICY);

        // 序号列
        javafx.scene.control.TableColumn<ManualRow, Number> idxCol =
                new javafx.scene.control.TableColumn<>("#");
        idxCol.setPrefWidth(36);
        idxCol.setMinWidth(36);
        idxCol.setMaxWidth(36);
        idxCol.setSortable(false);
        idxCol.setCellValueFactory(c -> new javafx.beans.property.SimpleIntegerProperty(
                c.getTableView().getItems().indexOf(c.getValue()) + 1));
        table.getColumns().add(idxCol);

        table.getColumns().add(editableCol(i18n.get("import.column.name"), ManualRow::nameProperty, 130));
        table.getColumns().add(editableCol(i18n.get("import.column.host"), ManualRow::hostProperty, 140));
        table.getColumns().add(editableCol(i18n.get("import.column.port"), ManualRow::portProperty, 60));
        table.getColumns().add(editableCol(i18n.get("import.column.user"), ManualRow::userProperty, 100));

        // 认证类型用 ComboBox 列
        javafx.scene.control.TableColumn<ManualRow, String> authCol =
                new javafx.scene.control.TableColumn<>(i18n.get("import.column.authType"));
        authCol.setCellFactory(col -> new javafx.scene.control.cell.ComboBoxTableCell<>(
                FXCollections.observableArrayList("PASSWORD", "PRIVATE_KEY")));
        authCol.setCellValueFactory(c -> c.getValue().authTypeProperty());
        authCol.setOnEditCommit(e -> e.getRowValue().authTypeProperty().set(e.getNewValue()));
        authCol.setPrefWidth(110);
        authCol.setMinWidth(80);
        table.getColumns().add(authCol);

        // 密码列：默认密文，编辑时显示明文
        javafx.scene.control.TableColumn<ManualRow, String> pwdCol =
                new javafx.scene.control.TableColumn<>(i18n.get("import.column.password"));
        pwdCol.setPrefWidth(130);
        pwdCol.setMinWidth(80);
        pwdCol.setCellValueFactory(c -> {
            ManualRow row = c.getValue();
            return new javafx.beans.property.SimpleStringProperty(
                    row.passwordVisible() ? row.password() : mask(row.password()));
        });
        pwdCol.setCellFactory(col -> new javafx.scene.control.cell.TextFieldTableCell<ManualRow, String>(
                new javafx.util.StringConverter<String>() {
                    @Override public String toString(String s) { return s == null ? "" : s; }
                    @Override public String fromString(String s) { return s; }
                }) {
            @Override
            public void startEdit() {
                super.startEdit();
                if (isEditing() && getTableRow() != null && getTableRow().getItem() != null) {
                    ManualRow row = (ManualRow) getTableRow().getItem();
                    setText(row.password());
                }
            }
            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    ((ManualRow) getTableRow().getItem()).setPassword(newValue);
                }
            }
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) return;
                ManualRow row = (ManualRow) getTableRow().getItem();
                if (!isEditing()) {
                    setText(row.passwordVisible() ? row.password() : mask(row.password()));
                }
            }
        });
        table.getColumns().add(pwdCol);

        // 私钥口令列
        javafx.scene.control.TableColumn<ManualRow, String> ppCol =
                new javafx.scene.control.TableColumn<>(i18n.get("import.column.passphrase"));
        ppCol.setPrefWidth(120);
        ppCol.setMinWidth(80);
        ppCol.setCellValueFactory(c -> {
            ManualRow row = c.getValue();
            return new javafx.beans.property.SimpleStringProperty(
                    row.passphraseVisible() ? row.passphrase() : mask(row.passphrase()));
        });
        ppCol.setCellFactory(col -> new javafx.scene.control.cell.TextFieldTableCell<ManualRow, String>(
                new javafx.util.StringConverter<String>() {
                    @Override public String toString(String s) { return s == null ? "" : s; }
                    @Override public String fromString(String s) { return s; }
                }) {
            @Override
            public void startEdit() {
                super.startEdit();
                if (isEditing() && getTableRow() != null && getTableRow().getItem() != null) {
                    ManualRow row = (ManualRow) getTableRow().getItem();
                    setText(row.passphrase());
                }
            }
            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    ((ManualRow) getTableRow().getItem()).setPassphrase(newValue);
                }
            }
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) return;
                ManualRow row = (ManualRow) getTableRow().getItem();
                if (!isEditing()) {
                    setText(row.passphraseVisible() ? row.passphrase() : mask(row.passphrase()));
                }
            }
        });
        table.getColumns().add(ppCol);

        // 删除行按钮列
        javafx.scene.control.TableColumn<ManualRow, Void> delCol =
                new javafx.scene.control.TableColumn<>("");
        delCol.setPrefWidth(36);
        delCol.setMinWidth(36);
        delCol.setMaxWidth(36);
        delCol.setSortable(false);
        delCol.setCellFactory(col -> new javafx.scene.control.TableCell<ManualRow, Void>() {
            private final javafx.scene.control.Button btn = new javafx.scene.control.Button("✕");
            {
                btn.setStyle("-fx-background-color:transparent;-fx-text-fill:#9ca3af;-fx-padding:0;-fx-font-size: 0.92em;-fx-cursor:hand;-fx-min-width:24px;-fx-min-height:20px;");
                btn.setOnAction(e -> {
                    ManualRow row = getTableView().getItems().get(getIndex());
                    getTableView().getItems().remove(row);
                });
                btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color:transparent;-fx-text-fill:#ef4444;-fx-padding:0;-fx-font-size: 0.92em;-fx-cursor:hand;-fx-min-width:24px;-fx-min-height:20px;"));
                btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color:transparent;-fx-text-fill:#9ca3af;-fx-padding:0;-fx-font-size: 0.92em;-fx-cursor:hand;-fx-min-width:24px;-fx-min-height:20px;"));
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        table.getColumns().add(delCol);

        // ScrollPane 包裹：横向纵向都有滚动条
        // fitToWidth/Height=false 让表格保持自然尺寸，超出视口时显示滚动条
        javafx.scene.control.ScrollPane tableScroll = new javafx.scene.control.ScrollPane(table);
        tableScroll.setFitToWidth(false);
        tableScroll.setFitToHeight(false);
        tableScroll.setPrefHeight(200);
        tableScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        tableScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tableScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);

        javafx.scene.control.Button loadJsonBtn = new javafx.scene.control.Button(i18n.get("import.manual.browseJson"));
        javafx.scene.control.Button addRowBtn = new javafx.scene.control.Button(i18n.get("import.manual.addRow"));
        javafx.scene.control.Button sampleBtn = new javafx.scene.control.Button(i18n.get("import.manual.sample"));
        javafx.scene.control.Button pasteJsonBtn = new javafx.scene.control.Button(i18n.get("import.manual.pasteJson"));
        javafx.scene.control.Button importBtn = new javafx.scene.control.Button(i18n.get("import.button.import"));

        sampleBtn.setOnAction(e -> showJsonSamplePopup(sampleBtn, i18n));
        pasteJsonBtn.setOnAction(e -> showPasteJsonPopup(pasteJsonBtn, i18n, rows, projectId, service, globalResult));

        loadJsonBtn.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(i18n.get("import.manual.browseJson"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    i18n.get("import.filter.json"), "*.json"));
            java.io.File file = fc.showOpenDialog(loadJsonBtn.getScene().getWindow());
            if (file == null) return;
            try {
                java.util.List<com.jlshell.ui.model.ConnectionFormData> parsed =
                        new com.jlshell.ui.service.importer.ManualJsonParser(projectId).parse(file.toPath());
                rows.clear();
                for (com.jlshell.ui.model.ConnectionFormData f : parsed) {
                    rows.add(new ManualRow(f.displayName(), f.host(), String.valueOf(f.port()),
                            f.username(), f.authenticationType().name(), f.password(), f.passphrase()));
                }
            } catch (Exception ex) {
                globalResult.setText(ex.getMessage());
            }
        });

        addRowBtn.setOnAction(e -> rows.add(new ManualRow("", "", "22", "", "PASSWORD")));

        importBtn.setOnAction(e -> {
            if (rows.isEmpty()) return;
            java.util.List<java.util.Map<String, String>> rowMaps = new java.util.ArrayList<>();
            for (ManualRow r : rows) {
                if (r.host() == null || r.host().isBlank()) continue;
                java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("name", r.name() == null ? "" : r.name());
                m.put("host", r.host());
                m.put("port", r.port() == null ? "22" : r.port());
                m.put("user", r.user() == null ? "" : r.user());
                m.put("authType", r.authType() == null ? "PASSWORD" : r.authType());
                m.put("password", r.password() == null ? "" : r.password());
                m.put("passphrase", r.passphrase() == null ? "" : r.passphrase());
                rowMaps.add(m);
            }
            java.util.List<com.jlshell.ui.model.ConnectionFormData> forms =
                    new com.jlshell.ui.service.importer.ManualJsonParser(projectId).fromRows(rowMaps);
            performImport(service, projectId, forms, i18n.get("import.section.manual"), globalResult, i18n);
        });

        HBox buttonRow = new HBox(8, loadJsonBtn, addRowBtn, sampleBtn, pasteJsonBtn, importBtn);
        VBox section = new VBox(6, title, hint, tableScroll, buttonRow);
        section.getStyleClass().add("settings-card");
        return section;
    }

    /** 密文遮罩：非空显示 "•••"，空显示空串。 */
    private static String mask(String value) {
        return (value == null || value.isEmpty()) ? "" : "•••";
    }

    /**
     * 弹出一个悬浮的 JSON 格式示例窗口，包含密码认证和私钥认证两种形式，
     * 用户可一键复制。点空白处或复制后自动关闭。
     * 使用 CSS 样式类适配当前主题，不硬编码颜色。
     */
    private static void showJsonSamplePopup(javafx.scene.control.Button anchor, I18nService i18n) {
        String sample =
                "[\n"
                + "  {\n"
                + "    \"name\": \"web-server\",\n"
                + "    \"host\": \"192.168.1.10\",\n"
                + "    \"port\": 22,\n"
                + "    \"user\": \"deploy\",\n"
                + "    \"authType\": \"PASSWORD\",\n"
                + "    \"password\": \"secret-password-here\",\n"
                + "    \"description\": \"Production web server\"\n"
                + "  },\n"
                + "  {\n"
                + "    \"name\": \"db-server\",\n"
                + "    \"host\": \"192.168.1.20\",\n"
                + "    \"port\": 22,\n"
                + "    \"user\": \"admin\",\n"
                + "    \"authType\": \"PRIVATE_KEY\",\n"
                + "    \"privateKeyPath\": \"/Users/you/.ssh/id_ed25519\",\n"
                + "    \"passphrase\": \"optional-key-passphrase\",\n"
                + "    \"description\": \"Database server\"\n"
                + "  }\n"
                + "]";

        javafx.scene.control.TextArea codeArea = new javafx.scene.control.TextArea(sample);
        codeArea.setWrapText(true);
        codeArea.setPrefSize(440, 280);
        codeArea.setEditable(false);
        codeArea.getStyleClass().add("import-json-code");

        javafx.scene.control.Button copyBtn = new javafx.scene.control.Button(i18n.get("import.manual.sample.copy"));

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        copyBtn.setOnAction(ev -> {
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(sample), null);
            popup.hide();
        });

        VBox content = new VBox(8, codeArea, copyBtn);
        content.getStyleClass().addAll("import-json-sample", "dialog-pane");
        content.setPadding(new Insets(12));
        content.setPrefWidth(460);

        popup.getContent().add(content);
        // Popup 创建独立的 Scene，不会继承父窗口的样式表，需要手动添加
        popup.getScene().getStylesheets().addAll(anchor.getScene().getStylesheets());
        popup.show(anchor, anchor.getScene().getWindow().getX() + anchor.getLayoutX(),
                anchor.getScene().getWindow().getY() + anchor.getLayoutY() + anchor.getHeight() + 4);
    }

    /**
     * 弹出一个悬浮窗口让用户粘贴 JSON，点击导入后解析并添加到表格。
     * 使用 CSS 样式类适配当前主题。
     */
    private static void showPasteJsonPopup(javafx.scene.control.Button anchor, I18nService i18n,
                                           ObservableList<ManualRow> rows, String projectId,
                                           com.jlshell.ui.service.ConnectionProfileService service,
                                           Label globalResult) {
        javafx.scene.control.TextArea pasteArea = new javafx.scene.control.TextArea();
        pasteArea.setPromptText(i18n.get("import.manual.pasteJson.hint"));
        pasteArea.setWrapText(true);
        pasteArea.setPrefSize(440, 260);
        pasteArea.getStyleClass().add("import-json-code");

        javafx.scene.control.Button importBtn = new javafx.scene.control.Button(i18n.get("import.button.import"));
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 0.85em;");

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        importBtn.setOnAction(ev -> {
            String text = pasteArea.getText().trim();
            if (text.isEmpty()) return;
            try {
                com.jlshell.ui.service.ConnectionShareService shareService =
                        new com.jlshell.ui.service.ConnectionShareService();
                if (shareService.isShareText(text)) {
                    com.jlshell.ui.model.ConnectionFormData form;
                    if (shareService.requiresShareCode(text)) {
                        java.util.Optional<String> code = showShareCodeDialog(anchor, i18n);
                        if (code.isEmpty()) {
                            return;
                        }
                        form = shareService.importShareText(text, code.get().toCharArray(), projectId);
                    } else {
                        form = shareService.importShareText(text, projectId);
                    }
                    performImport(service, projectId, java.util.List.of(form),
                            i18n.get("import.section.share"), globalResult, i18n);
                    popup.hide();
                    return;
                }

                java.util.List<com.jlshell.ui.model.ConnectionFormData> parsed =
                        new com.jlshell.ui.service.importer.ManualJsonParser(projectId)
                                .parseJsonText(text);
                rows.clear();
                for (com.jlshell.ui.model.ConnectionFormData f : parsed) {
                    rows.add(new ManualRow(f.displayName(), f.host(), String.valueOf(f.port()),
                            f.username(), f.authenticationType().name(), f.password(), f.passphrase()));
                }
                popup.hide();
                globalResult.setText(i18n.get("import.found", parsed.size()));
            } catch (Exception ex) {
                errorLabel.setText(ex.getMessage());
            }
        });

        VBox content = new VBox(8, pasteArea, errorLabel, importBtn);
        content.getStyleClass().addAll("import-json-sample", "dialog-pane");
        content.setPadding(new Insets(12));
        content.setPrefWidth(460);

        popup.getContent().add(content);
        // Popup 创建独立的 Scene，不会继承父窗口的样式表，需要手动添加
        popup.getScene().getStylesheets().addAll(anchor.getScene().getStylesheets());
        popup.show(anchor, anchor.getScene().getWindow().getX() + anchor.getLayoutX(),
                anchor.getScene().getWindow().getY() + anchor.getLayoutY() + anchor.getHeight() + 4);
    }

    private static java.util.Optional<String> showShareCodeDialog(javafx.scene.Node owner, I18nService i18n) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("connection.share.importCodeTitle"));
        dialog.setHeaderText(i18n.get("connection.share.importCodeHeader"));
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        PasswordField codeField = new PasswordField();
        codeField.setPromptText(i18n.get("connection.share.code"));
        codeField.setPrefColumnCount(24);

        VBox content = new VBox(8, new Label(i18n.get("connection.share.code")), codeField);
        content.setPadding(new Insets(8, 0, 0, 0));
        dialog.getDialogPane().setContent(content);

        ButtonType importType = new ButtonType(i18n.get("import.button.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);
        javafx.scene.Node importButton = dialog.getDialogPane().lookupButton(importType);
        importButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (codeField.getText() == null || codeField.getText().isBlank()) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == importType ? codeField.getText().trim() : null);
        return dialog.showAndWait();
    }

    /**
     * 创建可编辑的文本列。使用自定义 CellFactory，确保焦点丢失时也会提交编辑，
     * 而不是 JavaFX 默认的 cancelEdit 行为（点击其他单元格会丢失输入）。
     */
    private static javafx.scene.control.TableColumn<ManualRow, String> editableCol(
            String title, java.util.function.Function<ManualRow, javafx.beans.property.SimpleStringProperty> propGetter,
            double width) {
        javafx.scene.control.TableColumn<ManualRow, String> col = new javafx.scene.control.TableColumn<>(title);
        col.setCellValueFactory(c -> propGetter.apply(c.getValue()));
        col.setCellFactory(tc -> new CommitOnFocusLostTableCell());
        col.setOnEditCommit(e -> propGetter.apply(e.getRowValue()).set(e.getNewValue()));
        col.setPrefWidth(width);
        col.setMinWidth(width * 0.6);
        return col;
    }

    /**
     * 自定义 TextFieldTableCell：在 cancelEdit 时（点击其他单元格、焦点丢失）
     * 提交输入值而非丢弃，解决 JavaFX 默认编辑丢失的 bug。
     */
    private static class CommitOnFocusLostTableCell extends javafx.scene.control.cell.TextFieldTableCell<ManualRow, String> {
        CommitOnFocusLostTableCell() {
            super(new javafx.util.StringConverter<String>() {
                @Override public String toString(String s) { return s == null ? "" : s; }
                @Override public String fromString(String s) { return s; }
            });
        }

        @Override
        public void cancelEdit() {
            if (getGraphic() instanceof javafx.scene.control.TextField tf) {
                commitEdit(tf.getText());
            } else {
                super.cancelEdit();
            }
        }
    }

    /**
     * 执行导入：在当前项目下建三级文件夹分层：
     *   导入（根）→ 来源（MobaXterm/Xshell/手动）→ 日期时间（yyyy-MM-dd HH:mm:ss）
     * 如果"导入"根文件夹已存在则复用，否则新建。
     */
    private static void performImport(com.jlshell.ui.service.ConnectionProfileService service,
                                      String projectId,
                                      java.util.List<com.jlshell.ui.model.ConnectionFormData> profiles,
                                      String sourceLabel,
                                      Label resultLabel,
                                      I18nService i18n) {
        if (profiles.isEmpty()) return;
        int success = 0;
        try {
            // 第一级："导入" 根文件夹（复用已有）
            String importRootName = i18n.get("import.folder.root");
            com.jlshell.ui.model.FolderProfile importRoot = findOrCreateFolder(service, importRootName, null, projectId);

            // 第二级：来源子文件夹（MobaXterm / Xshell / 手动）
            com.jlshell.ui.model.FolderProfile sourceFolder = findOrCreateFolder(service, sourceLabel, importRoot.id(), projectId);

            // 第三级：日期时间
            String dateTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            com.jlshell.ui.model.FolderProfile dateFolder = service.saveFolder(null, dateTime, sourceFolder.id(), projectId);

            for (com.jlshell.ui.model.ConnectionFormData form : profiles) {
                try {
                    service.saveImported(form.withFolderId(dateFolder.id()));
                    success++;
                } catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger(PreferencesDialog.class)
                            .warn("Skip failed import entry: {}", ex.getMessage());
                }
            }
            resultLabel.setText(i18n.get("import.result.count", success, profiles.size()));
        } catch (Exception ex) {
            resultLabel.setText(ex.getMessage());
        }
    }

    /**
     * 在指定 parentId 下查找同名文件夹，找到则返回，否则新建。
     */
    private static com.jlshell.ui.model.FolderProfile findOrCreateFolder(
            com.jlshell.ui.service.ConnectionProfileService service,
            String name, String parentId, String projectId) {
        java.util.List<com.jlshell.ui.model.FolderProfile> allFolders = service.listFolders(projectId);
        for (com.jlshell.ui.model.FolderProfile f : allFolders) {
            if (name.equals(f.name()) && java.util.Objects.equals(parentId, f.parentId())) {
                return f;
            }
        }
        return service.saveFolder(null, name, parentId, projectId);
    }

    /** 手动表格的行模型，使用 JavaFX StringProperty 以支持 TableView 编辑自动刷新。 */
    public static class ManualRow {
        private final javafx.beans.property.SimpleStringProperty name = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty host = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty port = new javafx.beans.property.SimpleStringProperty("22");
        private final javafx.beans.property.SimpleStringProperty user = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty authType = new javafx.beans.property.SimpleStringProperty("PASSWORD");
        private final javafx.beans.property.SimpleStringProperty password = new javafx.beans.property.SimpleStringProperty("");
        private final javafx.beans.property.SimpleStringProperty passphrase = new javafx.beans.property.SimpleStringProperty("");
        private boolean passwordVisible;
        private boolean passphraseVisible;

        public ManualRow(String name, String host, String port, String user, String authType) {
            this(name, host, port, user, authType, "", "");
        }

        public ManualRow(String name, String host, String port, String user, String authType,
                         String password, String passphrase) {
            this.name.set(name);
            this.host.set(host);
            this.port.set(port);
            this.user.set(user);
            this.authType.set(authType);
            this.password.set(password);
            this.passphrase.set(passphrase);
        }

        public javafx.beans.property.SimpleStringProperty nameProperty() { return name; }
        public String name() { return name.get(); }
        public void setName(String v) { name.set(v); }
        public javafx.beans.property.SimpleStringProperty hostProperty() { return host; }
        public String host() { return host.get(); }
        public void setHost(String v) { host.set(v); }
        public javafx.beans.property.SimpleStringProperty portProperty() { return port; }
        public String port() { return port.get(); }
        public void setPort(String v) { port.set(v); }
        public javafx.beans.property.SimpleStringProperty userProperty() { return user; }
        public String user() { return user.get(); }
        public void setUser(String v) { user.set(v); }
        public javafx.beans.property.SimpleStringProperty authTypeProperty() { return authType; }
        public String authType() { return authType.get(); }
        public void setAuthType(String v) { authType.set(v); }
        public javafx.beans.property.SimpleStringProperty passwordProperty() { return password; }
        public String password() { return password.get(); }
        public void setPassword(String v) { password.set(v); }
        public javafx.beans.property.SimpleStringProperty passphraseProperty() { return passphrase; }
        public String passphrase() { return passphrase.get(); }
        public void setPassphrase(String v) { passphrase.set(v); }
        public boolean passwordVisible() { return passwordVisible; }
        public void setPasswordVisible(boolean v) { this.passwordVisible = v; }
        public boolean passphraseVisible() { return passphraseVisible; }
        public void setPassphraseVisible(boolean v) { this.passphraseVisible = v; }
    }

    // ── Shortcuts Tab ──────────────────────────────────────────────────────

    private static VBox buildShortcutsPane(ShortcutRegistry shortcutRegistry, I18nService i18n,
                                            ThemeService themeService, Stage owner,
                                            Runnable preferenceChanged) {
        VBox pane = new VBox(12);
        pane.getStyleClass().add("shortcut-page");
        pane.setPadding(new Insets(16, 20, 12, 20));

        // Search field
        TextField searchField = new TextField();
        searchField.setPromptText(i18n.get("shortcut.search.prompt"));
        searchField.setMinWidth(220);
        searchField.setPrefWidth(480);
        searchField.setMaxWidth(520);

        Label totalBadge = new Label();
        Label customizedBadge = new Label();
        Label unassignedBadge = new Label();
        totalBadge.getStyleClass().add("shortcut-summary-badge");
        customizedBadge.getStyleClass().add("shortcut-summary-badge");
        unassignedBadge.getStyleClass().add("shortcut-summary-badge");
        Label resultCount = new Label();
        resultCount.getStyleClass().add("shortcut-result-count");

        Runnable[] refreshSummary = new Runnable[1];
        refreshSummary[0] = () -> {
            if (shortcutRegistry == null) {
                totalBadge.setText(i18n.get("shortcut.summary.total", 0));
                customizedBadge.setText(i18n.get("shortcut.summary.customized", 0));
                unassignedBadge.setText(i18n.get("shortcut.summary.unassigned", 0));
                return;
            }
            List<ShortcutDefinition> definitions = shortcutRegistry.definitions();
            long customized = definitions.stream().filter(definition ->
                    !Objects.equals(normalizeShortcutBinding(shortcutRegistry.getEffectivePrimary(definition.id())),
                            normalizeShortcutBinding(definition.defaultPrimary()))
                            || !Objects.equals(normalizeShortcutBinding(shortcutRegistry.getEffectiveSecondary(definition.id())),
                            normalizeShortcutBinding(definition.defaultSecondary()))).count();
            long unassigned = definitions.stream().filter(definition ->
                    normalizeShortcutBinding(shortcutRegistry.getEffectivePrimary(definition.id())).isBlank()
                            && normalizeShortcutBinding(shortcutRegistry.getEffectiveSecondary(definition.id())).isBlank()).count();
            totalBadge.setText(i18n.get("shortcut.summary.total", definitions.size()));
            customizedBadge.setText(i18n.get("shortcut.summary.customized", customized));
            unassignedBadge.setText(i18n.get("shortcut.summary.unassigned", unassigned));
        };

        // Scrollable content area for shortcut rows
        VBox shortcutsList = new VBox(14);
        shortcutsList.getStyleClass().add("shortcut-list-container");
        ScrollPane scrollPane = new ScrollPane(shortcutsList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPrefHeight(400);
        scrollPane.getStyleClass().add("shortcut-scroll-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Build shortcut rows grouped by category
        Runnable rebuildList = new Runnable() {
            @Override
            public void run() {
                shortcutsList.getChildren().clear();
                if (shortcutRegistry == null) return;

                // Group definitions by category, preserving insertion order
                Map<String, List<ShortcutDefinition>> byCategory = new LinkedHashMap<>();
                for (ShortcutDefinition def : shortcutRegistry.definitions()) {
                    byCategory.computeIfAbsent(def.category(), k -> new ArrayList<>()).add(def);
                }

                for (Map.Entry<String, List<ShortcutDefinition>> entry : byCategory.entrySet()) {
                    String category = entry.getKey();
                    List<ShortcutDefinition> defs = entry.getValue();

                    Label categoryLabel = new Label(i18n.get("shortcut.category." + category));
                    categoryLabel.getStyleClass().add("shortcut-category-title");
                    Label categoryCount = new Label(i18n.get("shortcut.category.count", defs.size()));
                    categoryCount.getStyleClass().add("shortcut-category-count");
                    Region categorySpacer = new Region();
                    HBox.setHgrow(categorySpacer, Priority.ALWAYS);
                    HBox categoryHeader = new HBox(8, categoryLabel, categorySpacer, categoryCount);
                    categoryHeader.setAlignment(Pos.CENTER_LEFT);
                    categoryHeader.getStyleClass().add("shortcut-category-header");
                    VBox rows = new VBox();

                    for (ShortcutDefinition def : defs) {
                        VBox row = buildShortcutRow(def, shortcutRegistry, i18n, themeService, owner,
                                refreshSummary[0], preferenceChanged);
                        rows.getChildren().add(row);
                    }
                    VBox categoryCard = new VBox(8, categoryHeader, new Separator(), rows);
                    categoryCard.getStyleClass().add("shortcut-category-card");
                    categoryCard.setUserData("category:" + category);
                    shortcutsList.getChildren().add(categoryCard);
                }
                refreshSummary[0].run();
                int visible = filterShortcutRows(shortcutsList, shortcutRegistry, i18n, searchField.getText());
                resultCount.setText(i18n.get("shortcut.search.results", visible));
            }
        };
        rebuildList.run();

        // Search filtering
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (shortcutRegistry == null) return;
            int visible = filterShortcutRows(shortcutsList, shortcutRegistry, i18n, newVal);
            resultCount.setText(i18n.get("shortcut.search.results", visible));
        });

        // Reset to Defaults button
        Button resetBtn = new Button(i18n.get("shortcut.resetDefaults"));
        resetBtn.setDisable(shortcutRegistry == null);
        resetBtn.setMinWidth(Region.USE_PREF_SIZE);
        resetBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    i18n.get("shortcut.resetDefaults.confirm"),
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle(i18n.get("preferences.tab.shortcuts"));
            confirm.setHeaderText(null);
            if (owner != null) confirm.initOwner(owner);
            themeService.applyToDialog(confirm);
            if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                shortcutRegistry.resetAll();
                rebuildList.run();
                preferenceChanged.run();
            }
        });

        Label pageTitle = new Label(i18n.get("shortcut.summary.title"));
        pageTitle.getStyleClass().add("shortcut-summary-title");
        Label pageDescription = new Label(i18n.get("shortcut.summary.description"));
        pageDescription.getStyleClass().add("shortcut-summary-description");
        pageDescription.setWrapText(true);
        VBox titleBox = new VBox(3, pageTitle, pageDescription);
        HBox badges = new HBox(7, totalBadge, customizedBadge, unassignedBadge);
        badges.setAlignment(Pos.CENTER_RIGHT);
        BorderPane summaryHeader = new BorderPane();
        summaryHeader.setLeft(titleBox);
        summaryHeader.setRight(badges);
        BorderPane.setAlignment(badges, Pos.TOP_RIGHT);

        VBox searchBox = new VBox(4, searchField, resultCount);
        HBox toolbar = new HBox(12, searchBox, new Region(), resetBtn);
        toolbar.getStyleClass().add("shortcut-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbar.getChildren().get(1), Priority.ALWAYS);

        VBox overview = new VBox(12, summaryHeader, new Separator(), toolbar);
        overview.getStyleClass().add("shortcut-overview-card");

        Label commandHeader = new Label(i18n.get("shortcut.column.command"));
        commandHeader.getStyleClass().add("shortcut-column-title");
        commandHeader.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(commandHeader, Priority.ALWAYS);
        Label primaryHeader = new Label(i18n.get("shortcut.column.primary"));
        primaryHeader.getStyleClass().add("shortcut-column-title");
        primaryHeader.setMinWidth(186);
        Label secondaryHeader = new Label(i18n.get("shortcut.column.secondary"));
        secondaryHeader.getStyleClass().add("shortcut-column-title");
        secondaryHeader.setMinWidth(186);
        HBox columns = new HBox(14, commandHeader, primaryHeader, secondaryHeader);
        columns.getStyleClass().add("shortcut-column-header");
        columns.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.control.ToggleGroup directoryGroup = new javafx.scene.control.ToggleGroup();
        VBox directoryItems = new VBox(5);
        Map<String, Integer> categorySizes = new LinkedHashMap<>();
        if (shortcutRegistry != null) {
            shortcutRegistry.definitions().forEach(definition ->
                    categorySizes.merge(definition.category(), 1, Integer::sum));
        }
        javafx.scene.control.ToggleButton allItem = shortcutDirectoryItem(
                i18n.get("shortcut.directory.all", shortcutRegistry == null ? 0 : shortcutRegistry.definitions().size()),
                "all", directoryGroup);
        directoryItems.getChildren().add(allItem);
        for (Map.Entry<String, Integer> category : categorySizes.entrySet()) {
            directoryItems.getChildren().add(shortcutDirectoryItem(
                    i18n.get("shortcut.directory.category",
                            i18n.get("shortcut.category." + category.getKey()), category.getValue()),
                    category.getKey(), directoryGroup));
        }
        allItem.setSelected(true);
        directoryGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) oldToggle.setSelected(true);
                return;
            }
            String category = String.valueOf(newToggle.getUserData());
            if (!searchField.getText().isBlank()) searchField.clear();
            Platform.runLater(() -> scrollToShortcutCategory(scrollPane, shortcutsList, category));
        });

        Label directoryTitle = new Label(i18n.get("shortcut.directory.title"));
        directoryTitle.getStyleClass().add("shortcut-directory-title");
        Label directoryHint = new Label(i18n.get("shortcut.directory.hint"));
        directoryHint.getStyleClass().add("shortcut-directory-hint");
        directoryHint.setWrapText(true);
        VBox directory = new VBox(8, directoryTitle, directoryHint, new Separator(), directoryItems);
        directory.getStyleClass().add("shortcut-directory");
        directory.setMinWidth(138);
        directory.setPrefWidth(154);
        directory.setMaxWidth(168);

        VBox content = new VBox(12, overview, columns, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        BorderPane workspace = new BorderPane();
        workspace.getStyleClass().add("shortcut-workspace");
        workspace.setLeft(directory);
        workspace.setCenter(content);
        BorderPane.setMargin(directory, new Insets(0, 14, 0, 0));
        VBox.setVgrow(workspace, Priority.ALWAYS);
        pane.getChildren().add(workspace);
        return pane;
    }

    private static javafx.scene.control.ToggleButton shortcutDirectoryItem(String text, String category,
                                                                            javafx.scene.control.ToggleGroup group) {
        javafx.scene.control.ToggleButton item = new javafx.scene.control.ToggleButton(text);
        item.getStyleClass().add("shortcut-directory-item");
        item.setUserData(category);
        item.setToggleGroup(group);
        item.setMaxWidth(Double.MAX_VALUE);
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private static void scrollToShortcutCategory(ScrollPane scrollPane, VBox shortcutsList, String category) {
        if ("all".equals(category)) {
            scrollPane.setVvalue(0);
            return;
        }
        javafx.scene.Node target = shortcutsList.getChildren().stream()
                .filter(node -> Objects.equals(node.getUserData(), "category:" + category))
                .findFirst().orElse(null);
        if (target == null) return;
        double contentHeight = shortcutsList.getBoundsInLocal().getHeight();
        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double scrollableHeight = Math.max(1, contentHeight - viewportHeight);
        scrollPane.setVvalue(Math.max(0, Math.min(1,
                target.getBoundsInParent().getMinY() / scrollableHeight)));
    }

    private static int filterShortcutRows(VBox shortcutsList, ShortcutRegistry shortcutRegistry,
                                          I18nService i18n, String searchText) {
        if (shortcutRegistry == null) return 0;
        String query = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        int visibleCount = 0;
        for (javafx.scene.Node cardNode : shortcutsList.getChildren()) {
            if (!(cardNode instanceof VBox card) || card.getChildren().size() < 3
                    || !(card.getChildren().get(2) instanceof VBox rows)) continue;
            boolean anyVisible = false;
            for (javafx.scene.Node row : rows.getChildren()) {
                if (row.getUserData() instanceof ShortcutDefinition def) {
                    boolean match = query.isBlank()
                            || i18n.get(def.nameKey()).toLowerCase(Locale.ROOT).contains(query)
                            || ShortcutConverter.toDisplayText(shortcutRegistry.getEffectivePrimary(def.id()))
                            .toLowerCase(Locale.ROOT).contains(query)
                            || ShortcutConverter.toDisplayText(shortcutRegistry.getEffectiveSecondary(def.id()))
                            .toLowerCase(Locale.ROOT).contains(query);
                    row.setVisible(match);
                    row.setManaged(match);
                    anyVisible |= match;
                    if (match) visibleCount++;
                }
            }
            card.setVisible(anyVisible);
            card.setManaged(anyVisible);
        }
        return visibleCount;
    }

    private static VBox buildShortcutRow(ShortcutDefinition def, ShortcutRegistry registry,
                                          I18nService i18n, ThemeService themeService, Stage owner,
                                          Runnable refreshSummary, Runnable preferenceChanged) {
        VBox rowBox = new VBox();
        rowBox.setUserData(def);
        rowBox.getStyleClass().add("shortcut-row");

        Label nameLabel = new Label(i18n.get(def.nameKey()));
        nameLabel.getStyleClass().add("shortcut-command-name");
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        // Primary shortcut button
        String primarySpec = registry.getEffectivePrimary(def.id());
        Button primaryBtn = new Button(primarySpec != null ? ShortcutConverter.toDisplayText(primarySpec) : i18n.get("shortcut.unassigned"));
        primaryBtn.getStyleClass().add("shortcut-key-button");
        primaryBtn.setMinWidth(148);
        primaryBtn.setPrefWidth(148);

        // Secondary shortcut button
        String secondarySpec = registry.getEffectiveSecondary(def.id());
        Button secondaryBtn = new Button(secondarySpec != null ? ShortcutConverter.toDisplayText(secondarySpec) : i18n.get("shortcut.unassigned"));
        secondaryBtn.getStyleClass().add("shortcut-key-button");
        secondaryBtn.setMinWidth(148);
        secondaryBtn.setPrefWidth(148);

        // Per-key clear buttons (只清除单个快捷键)
        Button clearPrimaryBtn = new Button("×");
        clearPrimaryBtn.getStyleClass().add("shortcut-clear-button");
        clearPrimaryBtn.setTooltip(new Tooltip(i18n.get("shortcut.clear")));
        clearPrimaryBtn.setOnAction(e -> {
            registry.setUserPrimary(def.id(), null);
            primaryBtn.setText(i18n.get("shortcut.unassigned"));
            refreshSummary.run();
            preferenceChanged.run();
        });

        Button clearSecondaryBtn = new Button("×");
        clearSecondaryBtn.getStyleClass().add("shortcut-clear-button");
        clearSecondaryBtn.setTooltip(new Tooltip(i18n.get("shortcut.clear")));
        clearSecondaryBtn.setOnAction(e -> {
            registry.setUserSecondary(def.id(), null);
            secondaryBtn.setText(i18n.get("shortcut.unassigned"));
            refreshSummary.run();
            preferenceChanged.run();
        });

        // Setup key recording for primary and secondary
        Runnable shortcutChanged = () -> {
            refreshSummary.run();
            preferenceChanged.run();
        };
        setupKeyRecording(primaryBtn, def, registry, i18n, themeService, owner, true, secondaryBtn, shortcutChanged);
        setupKeyRecording(secondaryBtn, def, registry, i18n, themeService, owner, false, primaryBtn, shortcutChanged);

        HBox primaryCell = new HBox(6, primaryBtn, clearPrimaryBtn);
        primaryCell.setMinWidth(186);
        primaryCell.setAlignment(Pos.CENTER_LEFT);
        HBox secondaryCell = new HBox(6, secondaryBtn, clearSecondaryBtn);
        secondaryCell.setMinWidth(186);
        secondaryCell.setAlignment(Pos.CENTER_LEFT);
        HBox hbox = new HBox(14, nameLabel, primaryCell, secondaryCell);
        hbox.setAlignment(Pos.CENTER_LEFT);
        rowBox.getChildren().add(hbox);
        return rowBox;
    }

    private static void setupKeyRecording(Button keyButton, ShortcutDefinition def, ShortcutRegistry registry,
                                           I18nService i18n, ThemeService themeService, Stage owner,
                                           boolean isPrimary, Button otherButton, Runnable preferenceChanged) {
        keyButton.setOnAction(e -> {
            if (registry == null) return;

            // Store original text for restore on cancel
            String originalText = keyButton.getText();
            String recordingText = i18n.get("shortcut.pressKey");
            keyButton.setText(recordingText);
            keyButton.setStyle("-fx-border-color: -jl-accent; -fx-border-width: 2;");

            // 录制状态标志：true = 正在录制，focus-lost 才恢复；false = 已完成，忽略 focus-lost
            boolean[] recording = {true};

            // Event filter for key recording
            javafx.event.EventHandler<javafx.scene.input.KeyEvent> keyHandler = new javafx.event.EventHandler<>() {
                @Override
                public void handle(javafx.scene.input.KeyEvent event) {
                    // Escape → cancel
                    if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        keyButton.setText(originalText);
                        keyButton.setStyle("");
                        recording[0] = false;
                        keyButton.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this);
                        event.consume();
                        return;
                    }

                    // Try to convert the key event
                    String spec = FxShortcutConverter.fromKeyEvent(event);
                    if (spec == null) {
                        // Modifier only — ignore
                        event.consume();
                        return;
                    }

                    event.consume();

                    // Check for conflicts — 禁止冲突，不保存
                    List<ShortcutDefinition> conflicts = registry.findConflicts(def.id(), spec);
                    if (!conflicts.isEmpty()) {
                        // Show conflict indicator
                        String conflictName = i18n.get(conflicts.get(0).nameKey());
                        keyButton.setStyle("-fx-border-color: #ef4444; -fx-border-width: 2;");
                        Tooltip conflictTooltip = new Tooltip(i18n.get("shortcut.conflict", conflictName));
                        keyButton.setTooltip(conflictTooltip);

                        // Restore border after 1.5s
                        PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
                        pause.setOnFinished(pt -> {
                            keyButton.setText(originalText);
                            keyButton.setStyle("");
                            keyButton.setTooltip(null);
                            recording[0] = false;
                        });
                        pause.play();
                    } else {
                        // No conflict — save and update
                        if (isPrimary) {
                            registry.setUserPrimary(def.id(), spec);
                        } else {
                            registry.setUserSecondary(def.id(), spec);
                        }
                        keyButton.setText(ShortcutConverter.toDisplayText(spec));
                        keyButton.setStyle("");
                        recording[0] = false;
                        preferenceChanged.run();
                    }

                    keyButton.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this);
                }
            };

            // Focus lost → 仅在录制中时取消录制
            javafx.beans.value.ChangeListener<Boolean> focusLostListener = new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Boolean> obs, Boolean oldVal, Boolean newVal) {
                    if (!newVal) { // lost focus
                        if (recording[0]) {
                            // 还在录制中，取消并恢复原文本
                            keyButton.setText(originalText);
                            keyButton.setStyle("");
                            if (keyButton.getScene() != null) {
                                keyButton.getScene().removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, keyHandler);
                            }
                        }
                        keyButton.focusedProperty().removeListener(this);
                    }
                }
            };

            keyButton.focusedProperty().addListener(focusLostListener);
            keyButton.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, keyHandler);
        });
    }

    // ── About Tab ──────────────────────────────────────────────────────────

    static javafx.scene.Node buildAboutPane(I18nService i18n) {
        return buildAboutPane(i18n, null);
    }

    static javafx.scene.Node buildAboutPane(I18nService i18n, Runnable checkUpdatesAction) {
        VBox content = new VBox(8);
        content.getStyleClass().add("about-content");
        content.setMaxWidth(920);

        Label logo = new Label("JL");
        logo.getStyleClass().add("about-logo");
        logo.setAlignment(Pos.CENTER);

        Label appName = new Label("JLShell");
        appName.getStyleClass().add("about-app-name");
        Label desc = new Label(i18n.get("preferences.about.description"));
        desc.getStyleClass().add("about-description");
        desc.setWrapText(true);
        VBox identity = new VBox(4, appName, desc);
        identity.setAlignment(Pos.CENTER_LEFT);

        Label version = new Label(i18n.get("preferences.about.version", VERSION));
        version.getStyleClass().add("about-version-badge");

        HBox heroActions = new HBox(8, version);
        heroActions.setAlignment(Pos.CENTER_RIGHT);
        if (checkUpdatesAction != null) {
            heroActions.getChildren().add(aboutCompactActionButton(
                    i18n.get("menu.help.checkUpdates"), "/icons/about-update.svg", checkUpdatesAction));
        }

        Region heroSpacer = new Region();
        HBox.setHgrow(heroSpacer, Priority.ALWAYS);
        HBox hero = new HBox(12, logo, identity, heroSpacer, heroActions);
        hero.getStyleClass().add("about-hero");
        hero.setAlignment(Pos.CENTER_LEFT);

        GridPane links = new GridPane();
        links.getStyleClass().add("about-links");
        links.setHgap(10);
        links.setVgap(10);
        links.setMaxWidth(Double.MAX_VALUE);
        for (int index = 0; index < 4; index++) {
            ColumnConstraints linkColumn = new ColumnConstraints();
            linkColumn.setPercentWidth(25);
            linkColumn.setHgrow(Priority.ALWAYS);
            linkColumn.setFillWidth(true);
            links.getColumnConstraints().add(linkColumn);
        }

        List<Button> linkButtons = List.of(
                aboutLinkButton(i18n.get("preferences.about.website"),
                        i18n.get("preferences.about.website.description"),
                        "/icons/about-home.svg", "https://jlshell.oomn.net"),
                aboutLinkButton(i18n.get("preferences.about.github"),
                        i18n.get("preferences.about.github.description"),
                        "/icons/about-github.svg", "https://github.com/Voghost/JLShell"),
                aboutLinkButton(i18n.get("preferences.about.releases"),
                        i18n.get("preferences.about.releases.description"),
                        "/icons/about-download.svg", "https://github.com/Voghost/JLShell/releases/latest"),
                aboutLinkButton(i18n.get("preferences.about.feedback"),
                        i18n.get("preferences.about.feedback.description"),
                        "/icons/about-issues.svg", "https://github.com/Voghost/JLShell/issues")
        );
        for (int index = 0; index < linkButtons.size(); index++) {
            Button linkButton = linkButtons.get(index);
            links.add(linkButton, index, 0);
            GridPane.setHgrow(linkButton, Priority.ALWAYS);
            GridPane.setFillWidth(linkButton, true);
        }
        VBox linksCard = aboutCard(i18n.get("preferences.about.resources"),
                i18n.get("preferences.about.resources.description"), links);

        FlowPane capabilities = new FlowPane(7, 7);
        capabilities.getStyleClass().add("about-chip-list");
        List.of("SSH Terminal", "SFTP", i18n.get("preferences.about.capability.connections"),
                        i18n.get("preferences.about.capability.plugins"), "JSON-RPC API")
                .forEach(text -> capabilities.getChildren().add(aboutChip(text)));
        Label tech = new Label("Java 21 · JavaFX 21 · SSHJ · JediTerm · JDBI 3 · SQLite");
        tech.getStyleClass().add("about-tech-stack");
        tech.setWrapText(true);
        VBox capabilitiesBody = new VBox(6, capabilities, tech);
        VBox capabilitiesCard = aboutCard(i18n.get("preferences.about.capabilities"),
                i18n.get("preferences.about.capabilities.description"), capabilitiesBody);

        VBox projectInfo = new VBox(5,
                aboutInfoRow(i18n.get("preferences.about.author"), "Voghost"),
                aboutInfoRow(i18n.get("preferences.about.license"), "MIT"),
                aboutInfoRow(i18n.get("preferences.about.repository"), "Voghost/JLShell"));
        VBox projectCard = aboutCard(i18n.get("preferences.about.project"),
                i18n.get("preferences.about.project.description"), projectInfo);

        String javaVersion = System.getProperty("java.runtime.version",
                System.getProperty("java.version", "-"));
        String os = System.getProperty("os.name", "-") + " "
                + System.getProperty("os.version", "") + " · " + System.getProperty("os.arch", "-");
        VBox runtimeInfo = new VBox(5,
                aboutInfoRow(i18n.get("preferences.about.runtime.app"), VERSION),
                aboutInfoRow(i18n.get("preferences.about.runtime.java"), javaVersion),
                aboutInfoRow(i18n.get("preferences.about.runtime.system"), os.strip()));

        Button copyRuntime = new Button(i18n.get("preferences.about.runtime.copy"));
        copyRuntime.getStyleClass().addAll("about-compact-action", "about-runtime-copy");
        copyRuntime.setOnAction(event -> {
            copyToClipboard(buildRuntimeDiagnostics());
            copyRuntime.setText(i18n.get("preferences.about.runtime.copied"));
            copyRuntime.setDisable(true);
            PauseTransition restore = new PauseTransition(Duration.seconds(1.4));
            restore.setOnFinished(ignored -> {
                copyRuntime.setText(i18n.get("preferences.about.runtime.copy"));
                copyRuntime.setDisable(false);
            });
            restore.play();
        });
        VBox runtimeCard = aboutCard(i18n.get("preferences.about.runtime"),
                i18n.get("preferences.about.runtime.description"), runtimeInfo, copyRuntime);

        HBox detailCards = new HBox(10, projectCard, runtimeCard);
        detailCards.getStyleClass().add("about-detail-cards");
        HBox.setHgrow(projectCard, Priority.ALWAYS);
        HBox.setHgrow(runtimeCard, Priority.ALWAYS);
        projectCard.setMaxWidth(Double.MAX_VALUE);
        runtimeCard.setMaxWidth(Double.MAX_VALUE);

        Label footer = new Label(i18n.get("preferences.about.footer"));
        footer.getStyleClass().add("about-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        content.getChildren().addAll(hero, linksCard, capabilitiesCard, detailCards, footer);

        StackPane wrapper = new StackPane(content);
        wrapper.getStyleClass().add("about-page");
        wrapper.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(wrapper);
        scroll.getStyleClass().addAll("settings-scroll-pane", "about-scroll-pane");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private static VBox aboutCard(String title, String description, javafx.scene.Node body) {
        return aboutCard(title, description, body, null);
    }

    private static VBox aboutCard(String title, String description, javafx.scene.Node body,
                                  javafx.scene.Node headerAction) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("about-card-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("about-card-description");
        descriptionLabel.setWrapText(true);

        javafx.scene.Node header = titleLabel;
        if (headerAction != null) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox headerRow = new HBox(8, titleLabel, spacer, headerAction);
            headerRow.setAlignment(Pos.CENTER_LEFT);
            header = headerRow;
        }

        VBox card = new VBox(4, header, descriptionLabel, new Separator(), body);
        card.getStyleClass().add("about-card");
        return card;
    }

    /**
     * 构建可直接粘贴到 Issue 的客户端环境报告。
     * 刻意不包含用户名、用户目录、启动参数和环境变量，避免复制敏感信息。
     */
    static String buildRuntimeDiagnostics() {
        Runtime runtime = Runtime.getRuntime();
        String launcherVersion = runtimeProperty("jlshell.launcher.version");
        String javafxVersion = firstRuntimeProperty("javafx.runtime.version", "javafx.version");
        String javaRuntime = runtimeProperty("java.runtime.name") + " "
                + firstRuntimeProperty("java.runtime.version", "java.version");
        String javaVm = runtimeProperty("java.vm.name") + " " + runtimeProperty("java.vm.version");
        String system = runtimeProperty("os.name") + " " + runtimeProperty("os.version");

        StringBuilder report = new StringBuilder(512)
                .append("JLShell Environment Report\n")
                .append("Generated: ").append(java.time.OffsetDateTime.now()).append('\n')
                .append('\n')
                .append("Application\n")
                .append("- JLShell: ").append(VERSION).append('\n');
        if (!"-".equals(launcherVersion)) {
            report.append("- Launcher: ").append(launcherVersion).append('\n');
        }

        report.append('\n')
                .append("Java\n")
                .append("- Runtime: ").append(javaRuntime.strip()).append('\n')
                .append("- Vendor: ").append(runtimeProperty("java.vendor")).append('\n')
                .append("- VM: ").append(javaVm.strip()).append('\n')
                .append("- Class version: ").append(runtimeProperty("java.class.version")).append('\n')
                .append("- JavaFX: ").append(javafxVersion).append('\n')
                .append('\n')
                .append("System\n")
                .append("- OS: ").append(system.strip()).append('\n')
                .append("- Architecture: ").append(runtimeProperty("os.arch")).append('\n')
                .append("- Available processors: ").append(runtime.availableProcessors()).append('\n')
                .append("- Max heap: ").append(formatRuntimeBytes(runtime.maxMemory())).append('\n')
                .append("- Locale: ").append(Locale.getDefault().toLanguageTag()).append('\n')
                .append("- Time zone: ").append(java.time.ZoneId.systemDefault().getId()).append('\n')
                .append("- File encoding: ").append(java.nio.charset.Charset.defaultCharset().name()).append('\n');

        String display = primaryDisplayDescription();
        if (!display.isBlank()) {
            report.append("- Primary display: ").append(display).append('\n');
        }
        return report.toString();
    }

    private static String primaryDisplayDescription() {
        if (!Platform.isFxApplicationThread()) {
            return "";
        }
        try {
            javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
            javafx.geometry.Rectangle2D bounds = screen.getBounds();
            return Math.round(bounds.getWidth()) + "x" + Math.round(bounds.getHeight())
                    + " logical px @ " + Math.round(screen.getDpi()) + " DPI";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstRuntimeProperty(String... keys) {
        for (String key : keys) {
            String value = runtimeProperty(key);
            if (!"-".equals(value)) return value;
        }
        return "-";
    }

    private static String runtimeProperty(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private static String formatRuntimeBytes(long bytes) {
        double gib = bytes / (1024d * 1024d * 1024d);
        return String.format(Locale.ROOT, "%.2f GiB", gib);
    }

    private static Button aboutLinkButton(String title, String description, String iconResource, String url) {
        return aboutActionButton(title, description, iconResource, () -> openExternalLink(url));
    }

    private static Button aboutActionButton(String title, String description,
                                            String iconResource, Runnable action) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("about-link-title");
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("about-link-description");
        descriptionLabel.setWrapText(false);
        descriptionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        descriptionLabel.setMaxWidth(Double.MAX_VALUE);
        VBox text = new VBox(3, titleLabel, descriptionLabel);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Region icon = loadAboutSvgIcon(iconResource, 18);
        StackPane iconBox = new StackPane(icon == null ? new Region() : icon);
        iconBox.getStyleClass().add("about-link-icon-box");
        iconBox.setMinSize(30, 30);
        iconBox.setPrefSize(30, 30);
        iconBox.setMaxSize(30, 30);

        HBox graphic = new HBox(11, iconBox, text);
        graphic.getStyleClass().add("about-link-graphic");
        graphic.setAlignment(Pos.CENTER_LEFT);
        graphic.setMinWidth(0);
        graphic.setMaxWidth(Double.MAX_VALUE);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().add("about-link-button");
        button.setMinWidth(0);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(62);
        button.setPrefHeight(62);
        button.setMaxHeight(62);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button aboutCompactActionButton(String title, String iconResource, Runnable action) {
        Button button = new Button(title);
        Region icon = loadAboutSvgIcon(iconResource, 14);
        if (icon != null) button.setGraphic(icon);
        button.getStyleClass().add("about-compact-action");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Region loadAboutSvgIcon(String resourcePath, double size) {
        try (var stream = PreferencesDialog.class.getResourceAsStream(resourcePath)) {
            if (stream == null) return null;
            String svgContent = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder pathData = new StringBuilder();
            int offset = 0;
            while (offset < svgContent.length()) {
                int start = svgContent.indexOf("d=\"", offset);
                if (start < 0) break;
                if (start > 0 && Character.isLetterOrDigit(svgContent.charAt(start - 1))) {
                    offset = start + 3;
                    continue;
                }
                start += 3;
                int end = svgContent.indexOf('"', start);
                if (end < 0) break;
                if (!pathData.isEmpty()) pathData.append(' ');
                pathData.append(svgContent, start, end);
                offset = end + 1;
            }
            if (pathData.isEmpty()) return null;

            javafx.scene.shape.SVGPath shape = new javafx.scene.shape.SVGPath();
            shape.setContent(pathData.toString());
            Region icon = new Region();
            icon.setShape(shape);
            icon.setMinSize(size, size);
            icon.setPrefSize(size, size);
            icon.setMaxSize(size, size);
            icon.setStyle("-fx-scale-shape: true;");
            icon.getStyleClass().add("about-link-icon");
            return icon;
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(PreferencesDialog.class)
                    .warn("Failed to load About page icon {}", resourcePath, error);
            return null;
        }
    }

    private static Label aboutChip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("about-chip");
        return chip;
    }

    private static HBox aboutInfoRow(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("about-info-key");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("about-info-value");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(280);
        valueLabel.setTextAlignment(TextAlignment.RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, keyLabel, spacer, valueLabel);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static void openExternalLink(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(PreferencesDialog.class)
                    .warn("Failed to open external link {}", url, error);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void updatePreview(Text preview, String family, double size) {
        preview.setFont(Font.font(family, size));
    }

    private static List<String> loadMonospacedFonts() {
        String[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> result = PREFERRED_MONO.stream()
                .filter(f -> Arrays.asList(all).contains(f))
                .collect(Collectors.toCollection(ArrayList::new));
        for (String f : all) {
            if (!result.contains(f) && isMonospaced(f)) result.add(f);
        }
        if (result.isEmpty()) result.add("Monospaced");
        return result;
    }

    private static boolean isMonospaced(String family) {
        try {
            java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, 12);
            FontMetrics fm = new Canvas().getFontMetrics(font);
            return fm.charWidth('i') == fm.charWidth('W');
        } catch (Exception e) { return false; }
    }

    private static String fmt0(double v) { return String.format("%.0f", v); }
    private static String fmt1(double v) { return String.format("%.1f", v); }
    private static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
