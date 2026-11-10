package com.jlshell.ui.dialog;

import com.jlshell.ui.support.FxThread;

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
import com.jlshell.ui.theme.AccentColor;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Canvas;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
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

    private static final Logger log = LoggerFactory.getLogger(PreferencesDialog.class);

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



    /** 从 MANIFEST.MF 或 pom.properties 读取实际构建版本号 */
    private static String readVersion() {
        // 优先从 META-INF/MANIFEST.MF 读取 Implementation-Version（jpackage 打包时写入）
        try {
            // 只读取承载本类的 JAR，不能把 FlatLaf 等依赖的版本误认为宿主版本。
            var res = PreferencesDialog.class.getResource("PreferencesDialog.class");
            if (res != null && res.openConnection() instanceof java.net.JarURLConnection connection) {
                var manifest = connection.getManifest();
                String version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
                if (version != null && !version.isBlank()) return version;
            }
        } catch (Exception ignored) {}
        // 回退到 Maven 生成的 pom.properties（ui 模块）
        try {
            var res = PreferencesDialog.class.getResource("/META-INF/maven/com.jlshell/ui/pom.properties");
            if (res != null) {
                var props = new java.util.Properties();
                try (var stream = res.openStream()) { props.load(stream); }
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
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer) {
        return show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, 0);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            int initialTabIndex) {
        return show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            CapabilityBus capabilityBus,
                            int initialTabIndex) {
        return show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus,
                null, null, null, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, FontProfileService fontProfileService, AppSettingsService appSettings,
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
        return show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager,
                pluginManager, selectedSessionId, memoryReclaimService, null, null, initialTabIndex);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static java.util.concurrent.CompletableFuture<Void> show(Object owner, FontProfileService fontProfileService, AppSettingsService appSettings,
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
        FontProfile[] pending = { fontProfileService.activeProfile() };
        String[] pendingLang = { appSettings.get("ui.language", null) };
        String[] pendingTheme = { appSettings.get("ui.theme", "DARK") };
        AccentColor[] pendingAccent = { themeService.accentColor() };
        String[] pendingConnTimeout = { appSettings.get("connection.timeout", "10") };
        String[] pendingKeepAlive = { appSettings.get("connection.keepAliveInterval", "60") };
        TerminalColorScheme[] pendingScheme = { themeService.activeColorScheme() };
        String[] pendingApiEnabled = { appSettings.get("api.enabled", "false") };
        String[] pendingApiPort = { appSettings.get("api.port", "0") };
        String[] pendingUiFontFamily = { appSettings.get("ui.font.family", null) };
        String[] pendingUiFontSize = { appSettings.get("ui.font.size", "13") };
        String[] pendingHighlights = { themeService.terminalHighlights().config().serialize() };
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

        PreferencesSnapshot[] lastApplied = { snapshotOf(pending, pendingLang, pendingTheme, pendingAccent,
                pendingConnTimeout, pendingKeepAlive, pendingScheme, pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines, pendingHighlights,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry) };
        var canApply = new com.jlshell.core.support.MutableValue<>(false);
        updateApplyState[0] = () -> canApply.set(hasPendingSettingsChanges(lastApplied[0],
                snapshotOf(pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                         pendingScheme, pendingApiEnabled, pendingApiPort,
                        pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines, pendingHighlights,
                        pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                        pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry)));
        updateApplyState[0].run();
        Runnable apply = () -> {
            boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending,
                    pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                     pendingScheme, pendingApiEnabled, pendingApiPort, pendingUiFontFamily,
                    pendingUiFontSize, pendingScrollbackLines, pendingHighlights, pendingUpdateAutoCheck, pendingUpdateChannel,
                    pendingUpdateBaseUrl, pendingAccountSyncEnabled, pendingAccountBaseUrl);
            lastApplied[0] = snapshotOf(pending, pendingLang, pendingTheme, pendingAccent,
                    pendingConnTimeout, pendingKeepAlive, pendingScheme, pendingApiEnabled, pendingApiPort,
                    pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines, pendingHighlights,
                    pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                    pendingAccountSyncEnabled, pendingAccountBaseUrl, shortcutRegistry);
            updateApplyState[0].run();
            if (needRestart) showRestartPrompt(owner, i18n, themeService);
        };

        Runnable applyAndClose = () -> {
            boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending,
                    pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                    pendingScheme, pendingApiEnabled, pendingApiPort, pendingUiFontFamily, pendingUiFontSize,
                    pendingScrollbackLines, pendingHighlights, pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                    pendingAccountSyncEnabled, pendingAccountBaseUrl);
            if (needRestart) showRestartPrompt(owner, i18n, themeService);
        };

        var pages = buildPages(fontProfileService, appSettings, i18n, themeService,
                pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingKeepAlive,
                 pendingScheme,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager, pluginManager,
                selectedSessionId,
                pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines, pendingHighlights,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                pendingAccountSyncEnabled, pendingAccountBaseUrl,
                accountService, shortcutRegistry, owner,
                memoryReclaimService, preferenceChanged);
        return com.jlshell.ui.swing.SwingPreferencesDialog.showAsync(pages, initialTabIndex, i18n, themeService,
                canApply, () -> FxThread.supplyAsync(() -> { apply.run(); themeService.refreshUi(); return null; }),
                () -> FxThread.supplyAsync(() -> { applyAndClose.run(); themeService.refreshUi(); return null; }));
    }

    private static void showRestartPrompt(Object owner, I18nService i18n, ThemeService themeService) {
        com.jlshell.ui.swing.SwingFormDialog.showMessage(i18n.get("preferences.title"), "jlshell.settings.restart", i18n,
                () -> new javax.swing.JLabel(i18n.get("preferences.general.restartRequired")), 540, 200);
        com.jlshell.ui.support.RestartHelper.scheduleRestart();
    }

    private static boolean applyPendingSettings(FontProfileService fontProfileService, AppSettingsService appSettings,
                                              ThemeService themeService, FontProfile[] pending, String[] pendingLang,
                                              String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                              String[] pendingKeepAlive,
                                              TerminalColorScheme[] pendingScheme,
                                              String[] pendingApiEnabled, String[] pendingApiPort,
                                              String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                              String[] pendingScrollbackLines, String[] pendingHighlights,
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
            themeService.setTheme(newTheme);
        }
        themeService.setAccentColor(pendingAccent[0]);

        appSettings.set("connection.timeout", pendingConnTimeout[0]);
        appSettings.set("connection.keepAliveInterval", pendingKeepAlive[0]);

        if (pendingScheme[0] != null && (prevTheme.equals(pendingTheme[0])
                || pendingScheme[0].isLight() == "LIGHT".equals(pendingTheme[0]))) {
            themeService.setActiveColorScheme(pendingScheme[0]);
        }
        pendingScheme[0] = themeService.activeColorScheme();

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
        themeService.refreshUiFont();
        appSettings.set("terminal.scrollback.lines", pendingScrollbackLines[0]);
        themeService.terminalHighlights().update(com.jlshell.terminal.model.TerminalHighlightConfig.parse(pendingHighlights[0]));
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
            TerminalColorScheme colorScheme,
            String apiEnabled,
            String apiPort,
            String uiFontFamily,
            String uiFontSize,
            String scrollbackLines,
            String highlights,
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
                                                  TerminalColorScheme[] pendingScheme,
                                                  String[] pendingApiEnabled, String[] pendingApiPort,
                                                  String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                                  String[] pendingScrollbackLines, String[] pendingHighlights,
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
                pendingScheme[0],
                pendingApiEnabled[0],
                pendingApiPort[0],
                normalizeNullableBlank(pendingUiFontFamily[0]),
                pendingUiFontSize[0],
                pendingScrollbackLines[0],
                pendingHighlights[0],
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

    private static List<com.jlshell.ui.swing.SwingPreferencesDialog.Page> buildPages(FontProfileService fontProfileService, AppSettingsService appSettings,
                                         I18nService i18n, ThemeService themeService,
                                         FontProfile[] pending, String[] pendingLang,
                                         String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                         String[] pendingKeepAlive,
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
                                         String[] pendingScrollbackLines, String[] pendingHighlights,
                                         String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel,
                                         String[] pendingUpdateBaseUrl,
                                         String[] pendingAccountSyncEnabled, String[] pendingAccountBaseUrl,
                                         AccountService accountService, ShortcutRegistry shortcutRegistry, Object owner,
                                         MemoryReclaimService memoryReclaimService,
                                         Runnable preferenceChanged) {
        var pages = new ArrayList<com.jlshell.ui.swing.SwingPreferencesDialog.Page>();
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(0, i18n.get("preferences.tab.general"), i18n.get("settings.group.appearance"),
                i18n.searchText("preferences.general.", "preferences.connection.", "preferences.updates."), () -> FxThread.supplyAsync(() -> buildGeneralPane(appSettings, i18n, themeService, pendingLang, pendingTheme,
                pendingAccent, pendingConnTimeout, pendingKeepAlive,
                pendingUiFontFamily, pendingUiFontSize,
                pendingUpdateAutoCheck, pendingUpdateChannel, pendingUpdateBaseUrl,
                memoryReclaimService, preferenceChanged))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(1, i18n.get("preferences.tab.account"), i18n.get("settings.group.appearance"),
                i18n.searchText("preferences.account.", "account."), () -> FxThread.supplyAsync(() -> buildAccountPane(i18n, themeService, accountService, owner,
                pendingAccountSyncEnabled, pendingAccountBaseUrl, preferenceChanged))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(2, i18n.get("preferences.tab.terminal"), null,
                i18n.searchText("preferences.terminal.", "terminal.", "colorScheme.", "highlight."), () -> FxThread.supplyAsync(() -> buildTerminalPane(appSettings, fontProfileService.activeProfile(), i18n, themeService,
                pending, pendingScheme, pendingScrollbackLines, pendingHighlights, preferenceChanged, owner))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(3, i18n.get("preferences.tab.import"), i18n.get("settings.group.tools"),
                i18n.searchText("import.", "preferences.import."), () -> FxThread.supplyAsync(() -> buildImportPane(i18n, connectionProfileService, activeProjectId, themeService))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(4, i18n.get("preferences.tab.api"), i18n.get("settings.group.tools"),
                i18n.searchText("api.", "preferences.api."), () -> FxThread.supplyAsync(() -> buildApiPane(appSettings, i18n, apiServer, capabilityBus, selectedSessionId,
                pendingApiEnabled, pendingApiPort, preferenceChanged))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(5, i18n.get("preferences.tab.plugins"), null,
                i18n.searchText("plugins."), () -> FxThread.supplyAsync(() -> buildPluginsPane(appSettings, i18n, themeService, owner,
                programPluginManager, pluginManager))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(6, i18n.get("preferences.tab.shortcuts"), null,
                i18n.searchText("shortcuts.", "shortcut."), () -> FxThread.supplyAsync(() -> buildShortcutsPane(shortcutRegistry, i18n, themeService, owner, preferenceChanged))));
        pages.add(new com.jlshell.ui.swing.SwingPreferencesDialog.Page(7, i18n.get("preferences.tab.about"), null,
                i18n.searchText("about.", "preferences.about."), () -> FxThread.supplyAsync(() -> buildAboutPane(i18n))));
        return pages;
    }

    // ── Account Tab ────────────────────────────────────────────────────────

    private static java.util.function.Supplier<javax.swing.JComponent> buildAccountPane(I18nService i18n, ThemeService themeService,
                                         AccountService accountService, Object owner,
                                         String[] pendingAccountSyncEnabled, String[] pendingAccountBaseUrl,
                                         Runnable preferenceChanged) {
        var initial = new com.jlshell.ui.swing.SwingAccountSettingsPage.Values(
                Boolean.parseBoolean(pendingAccountSyncEnabled[0]), pendingAccountBaseUrl[0]);
        return () -> new com.jlshell.ui.swing.SwingAccountSettingsPage(
                initial, accountService, i18n, values -> FxThread.run(() -> {
                    pendingAccountSyncEnabled[0] = Boolean.toString(values.syncEnabled());
                    pendingAccountBaseUrl[0] = values.baseUrl(); preferenceChanged.run();
                }), (action, refresh) -> FxThread.run(() -> {
                    switch (action) {
                        case BROWSER_LOGIN -> AccountDialog.showBrowserLogin(owner, i18n, themeService, accountService);
                        case PASSWORD_LOGIN -> AccountDialog.showLogin(owner, i18n, themeService, accountService);
                        case REGISTER -> AccountDialog.showRegister(owner, i18n, themeService, accountService);
                        case CHANGE_PASSWORD -> showChangePasswordDialog(owner, i18n, themeService, accountService);
                    }
                    javax.swing.SwingUtilities.invokeLater(refresh);
                }), message -> com.jlshell.ui.swing.SwingFormDialog.showMessage(i18n.get("account.title"), "jlshell.account.message", i18n,
                        () -> new javax.swing.JLabel(message), 520, 220));
    }

    /** 修改密码对话框。 */
    private static void showChangePasswordDialog(Object owner, I18nService i18n,
                                                 ThemeService themeService, AccountService accountService) {
        AccountDialog.showChangePassword(owner, i18n, themeService, accountService);
    }

    // ── General Tab ────────────────────────────────────────────────────────

    private static java.util.function.Supplier<javax.swing.JComponent> buildGeneralPane(AppSettingsService appSettings, I18nService i18n,
                                          ThemeService themeService, String[] pendingLang, String[] pendingTheme,
                                          AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                          String[] pendingKeepAlive, String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                          String[] pendingUpdateAutoCheck, String[] pendingUpdateChannel, String[] pendingUpdateBaseUrl,
                                          MemoryReclaimService memoryReclaimService, Runnable preferenceChanged) {
        String language = pendingLang[0] == null ? ("zh".equals(Locale.getDefault().getLanguage()) ? "zh_CN" : "en") : pendingLang[0];
        var initial = new com.jlshell.ui.swing.SwingSettingsGeneralPage.Values(language, pendingTheme[0], pendingAccent[0],
                pendingUiFontFamily[0], Math.max(8, Math.min(24, (int) parseDouble(pendingUiFontSize[0], 13))),
                Math.max(1, (int) parseDouble(pendingConnTimeout[0], 10)), Math.max(0, (int) parseDouble(pendingKeepAlive[0], 60)),
                Boolean.parseBoolean(pendingUpdateAutoCheck[0]), pendingUpdateChannel[0], pendingUpdateBaseUrl[0]);
        var fontNames = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .sorted(Comparator.comparing((String family) -> !isKnownMonospacedFamily(family)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
        return () -> com.jlshell.ui.swing.SwingSettingsGeneralPage.create(initial, fontNames, i18n,
                memoryReclaimService, values -> FxThread.run(() -> {
                    pendingLang[0] = values.language(); pendingTheme[0] = values.theme(); pendingAccent[0] = values.accent();
                    pendingUiFontFamily[0] = values.fontFamily(); pendingUiFontSize[0] = Integer.toString(values.fontSize());
                    pendingConnTimeout[0] = Integer.toString(values.timeout()); pendingKeepAlive[0] = Integer.toString(values.keepAlive());
                    pendingUpdateAutoCheck[0] = Boolean.toString(values.autoUpdate()); pendingUpdateChannel[0] = values.channel();
                    pendingUpdateBaseUrl[0] = values.baseUrl(); preferenceChanged.run();
                }));
    }

    private static java.util.function.Supplier<javax.swing.JComponent> buildTerminalPane(AppSettingsService appSettings,
                                           FontProfile current, I18nService i18n,
                                           ThemeService themeService,
                                           FontProfile[] pending, TerminalColorScheme[] pendingScheme,
                                           String[] pendingScrollbackLines, String[] pendingHighlights,
                                           Runnable preferenceChanged, Object owner) {
        List<String> monospace = new ArrayList<>(loadMonospacedFonts());
        if (!monospace.contains(current.family())) monospace.addFirst(current.family());
        List<String> chinese = new ArrayList<>();
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (new java.awt.Font(family, java.awt.Font.PLAIN, 13).canDisplayUpTo("中文字体你好世界") == -1) chinese.add(family);
        }
        if (!current.chineseFamily().isBlank() && !chinese.contains(current.chineseFamily())) chinese.add(current.chineseFamily());
        var initial = new com.jlshell.ui.swing.SwingTerminalSettingsPage.Values(pending[0], pendingScheme[0],
                parseScrollbackLines(pendingScrollbackLines[0]), com.jlshell.terminal.model.TerminalHighlightConfig.parse(pendingHighlights[0]));
        var schemes = themeService.registry().allSchemes();
        return () -> new com.jlshell.ui.swing.SwingTerminalSettingsPage(
                initial, monospace, chinese, schemes, i18n, themeService,
                values -> FxThread.run(() -> {
                    pending[0] = values.font(); pendingScheme[0] = values.scheme();
                    pendingScrollbackLines[0] = Integer.toString(values.scrollback());
                    pendingHighlights[0] = values.highlights().serialize(); preferenceChanged.run();
                }));
    }

    private static int parseScrollbackLines(String raw) {
        try {
            return TerminalRuntimeSettings.clampScrollback(Integer.parseInt(raw.trim()));
        } catch (Exception ignored) {
            return TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES;
        }
    }

    // ── API Tab ────────────────────────────────────────────────────────────

    private static java.util.function.Supplier<javax.swing.JComponent> buildApiPane(AppSettingsService appSettings, I18nService i18n,
                                     com.jlshell.api.server.ApiServer apiServer, CapabilityBus capabilityBus, String selectedSessionId,
                                     String[] pendingApiEnabled, String[] pendingApiPort, Runnable preferenceChanged) {
        boolean enabled = Boolean.parseBoolean(pendingApiEnabled[0]);
        String port = pendingApiPort[0];
        String status = apiServer != null && apiServer.enabled() ? i18n.get("api.current", Integer.toString(apiServer.port())) : i18n.get("api.disabled");
        var entries = apiDocEntries(capabilityBus, selectedSessionId).stream().map(entry -> new com.jlshell.ui.swing.SwingApiSettingsPage.Entry(
                entry.name(), entry.typeLabel(i18n), entry.description(), entry.detailText(i18n, apiServer), entry.method(),
                entry.endpoint(apiServer), entry.requestExample(), entry.schemaText(i18n))).toList();
        return () -> new com.jlshell.ui.swing.SwingApiSettingsPage(enabled, port, status,
                () -> apiServer == null ? "" : apiServer.token(), entries, i18n,
                (active, value) -> FxThread.run(() -> {
                    pendingApiEnabled[0] = Boolean.toString(active); pendingApiPort[0] = value; preferenceChanged.run();
                }));
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

    private static java.util.function.Supplier<javax.swing.JComponent> buildPluginsPane(AppSettingsService appSettings, I18nService i18n,
                                           ThemeService themes, Object owner,
                                           ProgramPluginManager programs, PluginManager sessions) {
        var runtime = new com.jlshell.ui.swing.SwingPluginSettingsPage.Runtime() {
            public java.util.concurrent.CompletableFuture<List<com.jlshell.ui.swing.SwingPluginSettingsPage.Entry>> entries() {
                return FxThread.supplyAsync(() -> {
                    var entries = new ArrayList<com.jlshell.ui.swing.SwingPluginSettingsPage.Entry>();
                    if (programs != null) for (var descriptor : programs.getInstalledPlugins()) {
                        try {
                            boolean enabled = programs.isPluginEnabled(descriptor.id());
                            java.util.function.Supplier<javax.swing.JComponent> settings = null;
                            if (enabled && descriptor.instance() != null) {
                                try { settings = descriptor.instance().settingsView(descriptor.context()); }
                                catch (RuntimeException | LinkageError error) {
                                    log.warn("Cannot inspect settings for program plugin {}", descriptor.id(), error);
                                    settings = () -> new javax.swing.JLabel(i18n.get("plugins.settingsUnavailableMessage", descriptor.displayName()));
                                }
                            }
                            entries.add(new com.jlshell.ui.swing.SwingPluginSettingsPage.Entry(descriptor.metadata(), enabled,
                                    com.jlshell.ui.swing.PluginIcons.local(descriptor.instance() == null ? null : descriptor.instance().getClass()), settings));
                        } catch (RuntimeException | LinkageError error) { log.warn("Cannot inspect program plugin", error); }
                    }
                    if (sessions != null) for (var descriptor : sessions.getInstalledPlugins()) {
                        try {
                            entries.add(new com.jlshell.ui.swing.SwingPluginSettingsPage.Entry(descriptor.metadata(), sessions.isPluginEnabled(descriptor.id()),
                                    com.jlshell.ui.swing.PluginIcons.local(descriptor.instance() == null ? null : descriptor.instance().getClass()), null));
                        } catch (RuntimeException | LinkageError error) { log.warn("Cannot inspect session plugin", error); }
                    }
                    return List.copyOf(entries);
                });
            }
            public java.util.concurrent.CompletableFuture<Void> enable(com.jlshell.ui.swing.SwingPluginSettingsPage.Entry entry, boolean enabled) {
                return FxThread.supplyAsync(() -> {
                    if (entry.metadata().scope() == com.jlshell.plugin.api.PluginScope.PROGRAM && programs != null) programs.setPluginEnabled(entry.metadata().id(), enabled);
                    else if (sessions != null) sessions.setPluginEnabled(entry.metadata().id(), enabled);
                    return null;
                });
            }
            public java.util.concurrent.CompletableFuture<Void> deactivate(String id) {
                return FxThread.supplyAsync(() -> { if (sessions != null) sessions.deactivatePlugin(id); return null; });
            }
            public java.util.concurrent.CompletableFuture<Void> reload() {
                return FxThread.supplyAsync(() -> { if (sessions != null) sessions.reloadPlugins(); return null; });
            }
        };
        String baseUrl = UpdateService.configuredBaseUrl(appSettings);
        return () -> new com.jlshell.ui.swing.SwingPluginSettingsPage(baseUrl, getVersion(), i18n, runtime);
    }

    // ── Import Tab ─────────────────────────────────────────────────────────

    private static java.util.function.Supplier<javax.swing.JComponent> buildImportPane(I18nService i18n,
                                          com.jlshell.ui.service.ConnectionProfileService service,
                                          String projectId, ThemeService themeService) {
        return () -> new com.jlshell.ui.swing.SwingImportSettingsPage(service, projectId, i18n);
    }

    // ── Shortcuts Tab ──────────────────────────────────────────────────────

    private static java.util.function.Supplier<javax.swing.JComponent> buildShortcutsPane(ShortcutRegistry shortcutRegistry, I18nService i18n,
                                             ThemeService themeService, Object owner, Runnable preferenceChanged) {
        return () -> new com.jlshell.ui.swing.SwingShortcutSettingsPage(
                shortcutRegistry, i18n, () -> FxThread.run(preferenceChanged));
    }

    // ── About Tab ──────────────────────────────────────────────────────────

    static java.util.function.Supplier<javax.swing.JComponent> buildAboutPane(I18nService i18n) { return buildAboutPane(i18n, null); }

    static java.util.function.Supplier<javax.swing.JComponent> buildAboutPane(I18nService i18n, Runnable checkUpdatesAction) {
        return () -> com.jlshell.ui.swing.SwingAboutPage.create(
                VERSION, i18n, PreferencesDialog::buildRuntimeDiagnostics, checkUpdatesAction);
    }

    /** Environment diagnostics exclude credentials, user directories and launch arguments. */
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
        if (GraphicsEnvironment.isHeadless()) return "";
        var configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        var bounds = configuration.getBounds();
        return bounds.width + "x" + bounds.height + " logical px @ " + configuration.getDefaultTransform().getScaleX() + "x";
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

    private static List<String> cachedMonoFonts;

    private static synchronized List<String> loadMonospacedFonts() {
        if (cachedMonoFonts != null) return cachedMonoFonts;
        String[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        List<String> result = PREFERRED_MONO.stream()
                .filter(f -> Arrays.asList(all).contains(f))
                .collect(Collectors.toCollection(ArrayList::new));
        for (String f : all) {
            if (!result.contains(f) && isMonospaced(f)) result.add(f);
        }
        if (result.isEmpty()) result.add("Monospaced");
        cachedMonoFonts = List.copyOf(result);
        return cachedMonoFonts;
    }

    private static boolean isKnownMonospacedFamily(String family) {
        String name = family.toLowerCase(Locale.ROOT);
        return PREFERRED_MONO.contains(family) || name.contains("mono") || name.contains("code")
                || name.contains("console") || name.contains("courier") || name.contains("typewriter");
    }

    private static boolean isMonospaced(String family) {
        try {
            java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, 12);
            FontMetrics fm = new Canvas().getFontMetrics(font);
            return fm.charWidth('i') == fm.charWidth('W');
        } catch (Exception e) { return false; }
    }

    private static double parseDouble(String s, double fallback) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
