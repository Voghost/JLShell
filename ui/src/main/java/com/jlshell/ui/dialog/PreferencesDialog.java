package com.jlshell.ui.dialog;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.plugin.api.rpc.Capability;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.rpc.CapabilitySpec;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.program.api.ProgramApiCatalog;
import com.jlshell.program.api.ProgramApiDefinition;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AccentColor;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.beans.binding.Bindings;
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
import javafx.scene.control.SplitPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

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
import java.util.stream.Collectors;

/**
 * 偏好设置对话框。
 * 采用 TabPane 结构，每个 Tab 对应一类配置。
 */
public class PreferencesDialog {

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
                connectionProfileService, activeProjectId, apiServer, null, null, null, null, 0);
    }

    /** 打开偏好设置对话框，可指定初始选中的 Tab 索引。 */
    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings,
                            I18nService i18n, ThemeService themeService,
                            com.jlshell.ui.service.ConnectionProfileService connectionProfileService,
                            String activeProjectId,
                            com.jlshell.api.server.ApiServer apiServer,
                            int initialTabIndex) {
        show(owner, fontProfileService, appSettings, i18n, themeService,
                connectionProfileService, activeProjectId, apiServer, null, null, null, null, initialTabIndex);
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
                connectionProfileService, activeProjectId, apiServer, capabilityBus, null, null, null, initialTabIndex);
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
                            int initialTabIndex) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("preferences.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.getDialogPane().setPrefWidth(620);

        FontProfile[] pending = { fontProfileService.activeProfile() };
        String[] pendingLang = { appSettings.get("ui.language", null) };
        String[] pendingTheme = { appSettings.get("ui.theme", "DARK") };
        AccentColor[] pendingAccent = { themeService.accentColor() };
        String[] pendingConnTimeout = { appSettings.get("connection.timeout", "10") };
        String[] pendingHoverExpand = { appSettings.get("ui.topbar.hoverExpand", "false") };
        TerminalColorScheme[] pendingScheme = { themeService.activeColorScheme() };
        String[] pendingApiEnabled = { appSettings.get("api.enabled", "false") };
        String[] pendingApiPort = { appSettings.get("api.port", "0") };
        String[] pendingUiFontFamily = { appSettings.get("ui.font.family", null) };
        String[] pendingUiFontSize = { appSettings.get("ui.font.size", "13") };
        String[] pendingScrollbackLines = { appSettings.get("terminal.scrollback.lines",
                String.valueOf(TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES)) };
        Runnable[] updateApplyState = new Runnable[1];
        Runnable preferenceChanged = () -> {
            if (updateApplyState[0] != null) updateApplyState[0].run();
        };

        TabPane tabs = buildTabPane(fontProfileService, appSettings, i18n, themeService,
                pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingHoverExpand, pendingScheme,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager, pluginManager,
                selectedSessionId,
                pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines, preferenceChanged);
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
                pendingConnTimeout, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines) };
        updateApplyState[0] = () -> applyButton.setDisable(!hasPendingSettingsChanges(lastApplied[0],
                snapshotOf(pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout,
                        pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                        pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines)));
        updateApplyState[0].run();
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume(); // 阻止 Dialog 默认的关闭逻辑
            boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort, pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines);
            lastApplied[0] = snapshotOf(pending, pendingLang, pendingTheme, pendingAccent,
                    pendingConnTimeout, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort,
                    pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines);
            updateApplyState[0].run();
            if (needRestart) showRestartPrompt(owner, i18n);
        });

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                boolean needRestart = applyPendingSettings(fontProfileService, appSettings, themeService, pending, pendingLang, pendingTheme, pendingAccent, pendingConnTimeout, pendingHoverExpand, pendingScheme, pendingApiEnabled, pendingApiPort, pendingUiFontFamily, pendingUiFontSize, pendingScrollbackLines);
                if (needRestart) showRestartPrompt(owner, i18n);
            }
            return null;
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
        System.exit(0);
    }

    private static boolean applyPendingSettings(FontProfileService fontProfileService, AppSettingsService appSettings,
                                              ThemeService themeService, FontProfile[] pending, String[] pendingLang,
                                              String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
                                              String[] pendingHoverExpand,
                                              TerminalColorScheme[] pendingScheme,
                                              String[] pendingApiEnabled, String[] pendingApiPort,
                                              String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                              String[] pendingScrollbackLines) {
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

        boolean langChanged = !Objects.equals(prevLang, pendingLang[0]);
        return langChanged || apiChanged;
    }

    private record PreferencesSnapshot(
            FontProfile fontProfile,
            String language,
            String theme,
            AccentColor accent,
            String connectionTimeout,
            String hoverExpand,
            TerminalColorScheme colorScheme,
            String apiEnabled,
            String apiPort,
            String uiFontFamily,
            String uiFontSize,
            String scrollbackLines
    ) {}

    private static PreferencesSnapshot snapshotOf(FontProfile[] pending, String[] pendingLang,
                                                  String[] pendingTheme, AccentColor[] pendingAccent,
                                                  String[] pendingConnTimeout, String[] pendingHoverExpand,
                                                  TerminalColorScheme[] pendingScheme,
                                                  String[] pendingApiEnabled, String[] pendingApiPort,
                                                  String[] pendingUiFontFamily, String[] pendingUiFontSize,
                                                  String[] pendingScrollbackLines) {
        return new PreferencesSnapshot(
                pending[0],
                pendingLang[0],
                pendingTheme[0],
                pendingAccent[0],
                pendingConnTimeout[0],
                pendingHoverExpand[0],
                pendingScheme[0],
                pendingApiEnabled[0],
                pendingApiPort[0],
                normalizeNullableBlank(pendingUiFontFamily[0]),
                pendingUiFontSize[0],
                pendingScrollbackLines[0]
        );
    }

    private static boolean hasPendingSettingsChanges(PreferencesSnapshot lastApplied, PreferencesSnapshot current) {
        return !Objects.equals(lastApplied, current);
    }

    private static String normalizeNullableBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static TabPane buildTabPane(FontProfileService fontProfileService, AppSettingsService appSettings,
                                         I18nService i18n, ThemeService themeService,
                                         FontProfile[] pending, String[] pendingLang,
                                         String[] pendingTheme, AccentColor[] pendingAccent, String[] pendingConnTimeout,
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
                                         Runnable preferenceChanged) {
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("preferences-tabs");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab generalTab = new Tab(i18n.get("preferences.tab.general"));
        generalTab.setContent(buildGeneralPane(appSettings, i18n, themeService, pendingLang, pendingTheme,
                pendingAccent, pendingHoverExpand, pendingUiFontFamily, pendingUiFontSize, preferenceChanged));
        tabPane.getTabs().add(generalTab);

        Tab connectionTab = new Tab(i18n.get("preferences.tab.connection"));
        connectionTab.setContent(buildConnectionPane(appSettings, i18n, pendingConnTimeout, preferenceChanged));
        tabPane.getTabs().add(connectionTab);

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
        pluginsTab.setContent(buildPluginsPane(i18n, programPluginManager, pluginManager));
        tabPane.getTabs().add(pluginsTab);

        Tab aboutTab = new Tab(i18n.get("preferences.tab.about"));
        aboutTab.setContent(buildAboutPane(i18n));
        tabPane.getTabs().add(aboutTab);

        return tabPane;
    }

    // ── General Tab ────────────────────────────────────────────────────────

    private static VBox buildGeneralPane(AppSettingsService appSettings, I18nService i18n,
                                          ThemeService themeService, String[] pendingLang, String[] pendingTheme,
                                          AccentColor[] pendingAccent, String[] pendingHoverExpand,
                                          String[] pendingUiFontFamily, String[] pendingUiFontSize,
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

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));
        grid.add(new Label(i18n.get("preferences.general.language")), 0, 0);
        grid.add(langCombo, 1, 0);
        grid.add(new Label(i18n.get("preferences.general.theme")), 0, 1);
        grid.add(themeCombo, 1, 1);
        grid.add(new Label(i18n.get("preferences.general.accentColor")), 0, 2);
        grid.add(accentCombo, 1, 2);
        grid.add(hoverExpandCheck, 1, 3);

        // UI 字体
        grid.add(new Label(i18n.get("preferences.general.uiFontFamily")), 0, 4);
        grid.add(uiFontCombo, 1, 4);
        grid.add(new Label(i18n.get("preferences.general.uiFontSize")), 0, 5);
        HBox uiFontSizeRow = new HBox(8, uiFontSizeSlider, uiFontSizeField);
        uiFontSizeRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(uiFontSizeRow, 1, 5);
        grid.add(new Label(i18n.get("preferences.general.uiFontPreview")), 0, 6);
        grid.add(uiFontPreview, 1, 6);

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ── Connection Tab ─────────────────────────────────────────────────────

    private static VBox buildConnectionPane(AppSettingsService appSettings, I18nService i18n,
                                            String[] pendingConnTimeout, Runnable preferenceChanged) {
        String currentTimeout = appSettings.get("connection.timeout", "10");

        TextField timeoutField = new TextField(currentTimeout);
        timeoutField.setPrefWidth(80);
        timeoutField.textProperty().addListener((o, ov, nv) -> {
            try {
                int v = Integer.parseInt(nv.trim());
                if (v > 0) {
                    pendingConnTimeout[0] = String.valueOf(v);
                    preferenceChanged.run();
                }
            } catch (NumberFormatException ignored) {}
        });

        Label timeoutUnit = new Label(i18n.get("preferences.connection.timeoutUnit"));

        String currentKeepAlive = appSettings.get("connection.keepAliveInterval", "60");
        TextField keepAliveField = new TextField(currentKeepAlive);
        keepAliveField.setPrefWidth(80);
        keepAliveField.textProperty().addListener((o, ov, nv) -> {
            try {
                int v = Integer.parseInt(nv.trim());
                if (v >= 0) appSettings.set("connection.keepAliveInterval", String.valueOf(v));
            } catch (NumberFormatException ignored) {}
        });

        Label keepAliveUnit = new Label(i18n.get("preferences.connection.keepAliveUnit"));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));

        grid.add(new Label(i18n.get("preferences.connection.timeout")), 0, 0);
        grid.add(new HBox(4, timeoutField, timeoutUnit), 1, 0);

        grid.add(new Label(i18n.get("preferences.connection.keepAlive")), 0, 1);
        grid.add(new HBox(4, keepAliveField, keepAliveUnit), 1, 1);

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(8));
        return pane;
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
        fontGrid.setPadding(new Insets(16, 20, 8, 20));

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
        schemeSection.setPadding(new Insets(8, 20, 8, 20));

        Label schemeLabel = new Label(i18n.get("preferences.terminal.colorScheme"));
        schemeLabel.setStyle("-fx-font-weight:bold;");

        schemeSection.getChildren().addAll(
                schemeLabel,
                filterField,
                schemeList,
                schemePreview,
                new HBox(8, new Label(i18n.get("preferences.terminal.colorScheme.opacity")), opacitySlider, opacityValue),
                actionBtns);

        // ── Combine ──
        VBox pane = new VBox(fontGrid, schemeSection);
        pane.setPadding(new Insets(0, 0, 8, 0));
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
        VBox box = new VBox(12);
        box.setPadding(new Insets(16, 20, 12, 20));

        CheckBox enableCb = new CheckBox(i18n.get("api.enabled"));
        enableCb.setSelected("true".equalsIgnoreCase(pendingApiEnabled[0]));
        enableCb.selectedProperty().addListener((o, ov, nv) -> {
            pendingApiEnabled[0] = String.valueOf(nv);
            preferenceChanged.run();
        });

        Label portLabel = new Label(i18n.get("api.port"));
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
        tokenHint.setStyle("-fx-font-size: 0.85em;");

        Button copyToken = new Button(i18n.get("api.copyToken"));
        copyToken.setDisable(apiServer == null || apiServer.token() == null || apiServer.token().isEmpty());
        copyToken.setOnAction(e -> {
            String t = apiServer == null ? "" : apiServer.token();
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(t);
            cb.setContent(cc);
        });

        Label restart = new Label(i18n.get("api.restartRequired"));
        restart.setStyle("-fx-text-fill: gray; -fx-font-size: 0.85em;");
        restart.setWrapText(true);

        HBox configRow = new HBox(8, portLabel, portField);
        configRow.setAlignment(Pos.CENTER_LEFT);
        HBox tokenRow = new HBox(8, tokenHint, copyToken);
        tokenRow.setAlignment(Pos.CENTER_LEFT);

        VBox apiBrowser = buildApiBrowser(i18n, apiServer, capabilityBus, selectedSessionId);
        VBox.setVgrow(apiBrowser, Priority.ALWAYS);

        box.getChildren().addAll(enableCb, configRow, currentLabel, tokenRow, restart, apiBrowser);
        return box;
    }

    private static VBox buildApiBrowser(I18nService i18n, com.jlshell.api.server.ApiServer apiServer,
                                        CapabilityBus capabilityBus, String selectedSessionId) {
        ObservableList<ApiDocEntry> entries = FXCollections.observableArrayList(apiDocEntries(capabilityBus, selectedSessionId));
        FilteredList<ApiDocEntry> filtered = new FilteredList<>(entries, e -> true);

        Label title = new Label(i18n.get("api.docs.title"));
        title.setStyle("-fx-font-weight: bold;");

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

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setPrefHeight(220);
        detail.getStyleClass().add("api-doc-detail");

        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) ->
                detail.setText(entry == null ? "" : entry.detailText(i18n, apiServer)));
        if (!filtered.isEmpty()) {
            list.getSelectionModel().select(0);
        }

        SplitPane split = new SplitPane(list, detail);
        split.setDividerPositions(0.36);
        VBox.setVgrow(split, Priority.ALWAYS);

        Label hint = new Label(i18n.get("api.docs.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.85em;");

        VBox pane = new VBox(8, title, search, split, hint);
        pane.setPadding(new Insets(8, 0, 0, 0));
        return pane;
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
        String args = spec.inputSchema() == null ? "{}" : "{ /* see inputSchema */ }";
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
            String endpoint = apiServer != null && apiServer.enabled() && apiServer.port() > 0
                    ? "http://127.0.0.1:" + apiServer.port() + "/rpc"
                    : "http://127.0.0.1:<port>/rpc";
            String method = type == ApiDocType.PLUGIN ? "capability.invoke" : name;
            String params = type == ApiDocType.PLUGIN ? paramsExample : paramsExample;
            return i18n.get("api.docs.type") + ": " + typeLabel(i18n) + "\n"
                    + i18n.get("api.docs.name") + ": " + name + "\n"
                    + i18n.get("api.docs.method") + ": " + method + "\n"
                    + i18n.get("api.docs.requiresSession") + ": " + (requiresSession ? i18n.get("api.docs.yes") : i18n.get("api.docs.no")) + "\n\n"
                    + i18n.get("api.docs.description") + ":\n" + description + "\n\n"
                    + i18n.get("api.docs.endpoint") + ":\n" + endpoint + "\n\n"
                    + i18n.get("api.docs.headers") + ":\nAuthorization: Bearer <token>\nContent-Type: application/json\n\n"
                    + i18n.get("api.docs.request") + ":\n"
                    + "{\n"
                    + "  \"jsonrpc\": \"2.0\",\n"
                    + "  \"id\": 1,\n"
                    + "  \"method\": \"" + method + "\",\n"
                    + "  \"params\": " + compactJson(params) + "\n"
                    + "}\n\n"
                    + i18n.get("api.docs.inputSchema") + ":\n"
                    + (inputSchema == null ? i18n.get("api.docs.noSchema") : inputSchema.toString()) + "\n\n"
                    + i18n.get("api.docs.result") + ":\n" + resultHint;
        }
    }

    private static String compactJson(String json) {
        return json == null || json.isBlank() ? "{}" : json.trim().replace("\n", "");
    }

    // ── Plugins Tab ────────────────────────────────────────────────────────

    private static VBox buildPluginsPane(I18nService i18n, ProgramPluginManager programPluginManager,
                                         PluginManager pluginManager) {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(16, 20, 12, 20));

        ObservableList<PluginDocEntry> entries = FXCollections.observableArrayList(pluginDocEntries(programPluginManager, pluginManager));
        FilteredList<PluginDocEntry> filtered = new FilteredList<>(entries, e -> true);

        ComboBox<String> scopeFilter = new ComboBox<>(FXCollections.observableArrayList(
                i18n.get("plugins.filter.all"),
                i18n.get("plugins.filter.program"),
                i18n.get("plugins.filter.session")
        ));
        scopeFilter.getSelectionModel().select(0);
        scopeFilter.valueProperty().addListener((obs, oldValue, newValue) -> {
            String selected = newValue == null ? i18n.get("plugins.filter.all") : newValue;
            filtered.setPredicate(entry -> selected.equals(i18n.get("plugins.filter.all"))
                    || (selected.equals(i18n.get("plugins.filter.program")) && entry.scope() == com.jlshell.plugin.api.PluginScope.PROGRAM)
                    || (selected.equals(i18n.get("plugins.filter.session")) && entry.scope() == com.jlshell.plugin.api.PluginScope.SESSION));
        });

        ListView<PluginDocEntry> list = new ListView<>(filtered);
        list.setPrefWidth(230);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(PluginDocEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.metadata().displayName());
                name.setStyle("-fx-font-weight: bold;");
                Label scope = new Label(item.scopeLabel(i18n));
                scope.getStyleClass().add("plugin-scope-badge");
                scope.getStyleClass().add(item.scope() == com.jlshell.plugin.api.PluginScope.PROGRAM
                        ? "plugin-scope-program"
                        : "plugin-scope-session");
                Label version = new Label(item.metadata().version());
                version.getStyleClass().add("plugin-version-label");
                HBox meta = new HBox(6, scope, version);
                meta.setAlignment(Pos.CENTER_LEFT);
                VBox row = new VBox(5, name, meta);
                setText(null);
                setGraphic(row);
            }
        });

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefHeight(180);

        VBox settingsHost = new VBox(8);
        settingsHost.setPadding(new Insets(8, 0, 0, 0));
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, entry) -> {
            detail.setText(entry == null ? "" : entry.detailText(i18n));
            settingsHost.getChildren().clear();
            if (entry != null && entry.settingsNode() != null) {
                settingsHost.getChildren().add(new Label(i18n.get("plugins.settings")));
                settingsHost.getChildren().add(entry.settingsNode());
            }
        });
        if (!filtered.isEmpty()) {
            list.getSelectionModel().select(0);
        }

        VBox right = new VBox(8, detail, settingsHost);
        SplitPane split = new SplitPane(list, right);
        split.setDividerPositions(0.35);
        VBox.setVgrow(split, Priority.ALWAYS);

        pane.getChildren().addAll(scopeFilter, split);
        return pane;
    }

    private static List<PluginDocEntry> pluginDocEntries(ProgramPluginManager programPluginManager,
                                                         PluginManager pluginManager) {
        List<PluginDocEntry> entries = new ArrayList<>();
        if (programPluginManager != null) {
            programPluginManager.getAvailablePlugins().forEach(desc -> {
                javafx.scene.Node settings = desc.instance().settingsView(desc.context());
                entries.add(new PluginDocEntry(desc.metadata(), settings));
            });
        }
        if (pluginManager != null) {
            pluginManager.getAvailablePlugins().forEach(desc -> entries.add(new PluginDocEntry(desc.metadata(), null)));
        }
        return entries;
    }

    private record PluginDocEntry(com.jlshell.plugin.api.PluginMetadata metadata, javafx.scene.Node settingsNode) {
        com.jlshell.plugin.api.PluginScope scope() {
            return metadata.scope();
        }

        String scopeLabel(I18nService i18n) {
            return scope() == com.jlshell.plugin.api.PluginScope.PROGRAM
                    ? i18n.get("plugins.scope.program")
                    : i18n.get("plugins.scope.session");
        }

        String detailText(I18nService i18n) {
            String range = (blank(metadata.minHostVersionInclusive()) && blank(metadata.maxHostVersionInclusive()))
                    ? i18n.get("plugins.compat.undeclared")
                    : (blank(metadata.minHostVersionInclusive()) ? "*" : metadata.minHostVersionInclusive())
                    + " - "
                    + (blank(metadata.maxHostVersionInclusive()) ? "*" : metadata.maxHostVersionInclusive());
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
        pane.setPadding(new Insets(16, 20, 12, 20));

        // 三个 section 共享一个结果标签，显示最近一次导入结果
        Label globalResult = new Label();
        globalResult.setStyle("-fx-text-fill: #38bdf8; -fx-font-size: 0.92em;");

        if (connectionProfileService == null) {
            // 从终端 Tab 打开的偏好设置没有连接服务上下文，提示用户从主菜单打开
            Label hint = new Label(i18n.get("import.noFile"));
            hint.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 0.92em;");
            pane.getChildren().add(hint);
            return pane;
        }

        // ── MobaXterm section ──
        VBox mobaxtermSection = buildMobaxtermSection(i18n, connectionProfileService, activeProjectId, globalResult);

        // ── Xshell section ──
        VBox xshellSection = buildXshellSection(i18n, connectionProfileService, activeProjectId, globalResult);

        // ── Manual section ──
        VBox manualSection = buildManualSection(i18n, connectionProfileService, activeProjectId, globalResult);

        pane.getChildren().addAll(mobaxtermSection, xshellSection, manualSection, globalResult);
        return pane;
    }

    private static VBox buildMobaxtermSection(I18nService i18n,
                                              com.jlshell.ui.service.ConnectionProfileService service,
                                              String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.mobaxterm"));
        title.setStyle("-fx-font-size: 1.08em;-fx-font-weight:bold;");

        Label hint = new Label(i18n.get("import.mobaxterm.hint"));
        hint.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 0.85em;");
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
        section.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8; -fx-padding: 12;");
        return section;
    }

    private static VBox buildXshellSection(I18nService i18n,
                                           com.jlshell.ui.service.ConnectionProfileService service,
                                           String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.xshell"));
        title.setStyle("-fx-font-size: 1.08em;-fx-font-weight:bold;");

        Label hint = new Label(i18n.get("import.xshell.hint"));
        hint.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 0.85em;");
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
        section.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8; -fx-padding: 12;");
        return section;
    }

    private static VBox buildManualSection(I18nService i18n,
                                           com.jlshell.ui.service.ConnectionProfileService service,
                                           String projectId, Label globalResult) {
        Label title = new Label(i18n.get("import.section.manual"));
        title.setStyle("-fx-font-size: 1.08em;-fx-font-weight:bold;");

        Label hint = new Label(i18n.get("import.manual.hint"));
        hint.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 0.85em;");
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
        section.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-background-radius: 8; -fx-padding: 12;");
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

    // ── About Tab ──────────────────────────────────────────────────────────

    private static VBox buildAboutPane(I18nService i18n) {
        VBox pane = new VBox(12);
        pane.setPadding(new Insets(24, 28, 16, 28));
        pane.setAlignment(Pos.TOP_CENTER);

        Label appName = new Label("JLShell");
        appName.setStyle("-fx-font-size: 1.54em;-fx-font-weight:bold;");

        Label version = new Label(i18n.get("preferences.about.version", VERSION));
        version.setStyle("-fx-font-size: 0.92em;");

        Label desc = new Label(i18n.get("preferences.about.description"));
        desc.setStyle("-fx-font-size: 0.85em;");
        desc.setWrapText(true);
        desc.setMaxWidth(400);
        desc.setPrefWidth(400);
        desc.setAlignment(Pos.CENTER);
        desc.setTextAlignment(TextAlignment.CENTER);

        Region sep = new Region();
        sep.setStyle("-fx-pref-height:1px;-fx-background-color:derive(-fx-text-fill, 50%);-fx-max-width:300;");
        sep.setPrefWidth(300);

        VBox authorBox = new VBox(4);
        authorBox.setAlignment(Pos.CENTER);

        Label authorTitle = new Label(i18n.get("preferences.about.author"));
        authorTitle.setStyle("-fx-font-size: 0.85em;-fx-font-weight:bold;");

        Label authorName = new Label("voghost");
        authorName.setStyle("-fx-font-size: 0.92em;");

        Label github = new Label("https://www.github.com/Voghost");
        github.setStyle("-fx-font-size: 0.85em;-fx-text-fill:-jl-accent;-fx-underline:true;-fx-cursor:hand;");
        github.setOnMouseClicked(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://www.github.com/Voghost")); }
            catch (Exception ignored) {}
        });

        authorBox.getChildren().addAll(authorTitle, authorName, github);

        Region sep2 = new Region();
        sep2.setStyle("-fx-pref-height:1px;-fx-background-color:derive(-fx-text-fill, 50%);-fx-max-width:300;");
        sep2.setPrefWidth(300);

        Label techTitle = new Label(i18n.get("preferences.about.techStack"));
        techTitle.setStyle("-fx-font-size: 0.85em;-fx-font-weight:bold;");

        Label techDetail = new Label("Java 21 · JavaFX 21 · SSHJ · JediTerm · JDBI 3 · SQLite");
        techDetail.setStyle("-fx-font-size: 0.77em;");
        techDetail.setWrapText(true);
        techDetail.setMaxWidth(400);
        techDetail.setPrefWidth(400);
        techDetail.setAlignment(Pos.CENTER);
        techDetail.setTextAlignment(TextAlignment.CENTER);

        pane.getChildren().addAll(appName, version, desc, sep, authorBox, sep2, techTitle, techDetail);
        return pane;
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
