package com.jlshell.ui.dialog;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
import java.util.stream.Collectors;
import java.util.ArrayList;

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

    private static final String VERSION = "0.1.0-SNAPSHOT";

    private PreferencesDialog() {}

    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings, I18nService i18n, ThemeService themeService) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("preferences.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.getDialogPane().setPrefWidth(520);

        FontProfile[] pending = { fontProfileService.activeProfile() };
        String[] pendingLang = { appSettings.get("ui.language", "en") };
        String[] pendingTheme = { appSettings.get("ui.theme", "DARK") };
        String[] pendingConnTimeout = { appSettings.get("connection.timeout", "10") };

        TabPane tabs = buildTabPane(fontProfileService, appSettings, i18n, themeService, pending, pendingLang, pendingTheme, pendingConnTimeout);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                fontProfileService.updateActiveProfile(pending[0]);
                String prevLang = appSettings.get("ui.language", "en");
                appSettings.set("ui.language", pendingLang[0]);

                String prevTheme = appSettings.get("ui.theme", "DARK");
                appSettings.set("ui.theme", pendingTheme[0]);
                if (!prevTheme.equals(pendingTheme[0])) {
                    AppTheme newTheme = "LIGHT".equals(pendingTheme[0]) ? AppTheme.LIGHT : AppTheme.DARK;
                    themeService.currentThemeProperty().set(newTheme);
                }

                appSettings.set("connection.timeout", pendingConnTimeout[0]);

                if (!prevLang.equals(pendingLang[0])) {
                    showRestartPrompt(owner, i18n);
                }
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

    private static TabPane buildTabPane(FontProfileService fontProfileService, AppSettingsService appSettings,
                                         I18nService i18n, ThemeService themeService,
                                         FontProfile[] pending, String[] pendingLang,
                                         String[] pendingTheme, String[] pendingConnTimeout) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab generalTab = new Tab(i18n.get("preferences.tab.general"));
        generalTab.setContent(buildGeneralPane(appSettings, i18n, themeService, pendingLang, pendingTheme));
        tabPane.getTabs().add(generalTab);

        Tab connectionTab = new Tab(i18n.get("preferences.tab.connection"));
        connectionTab.setContent(buildConnectionPane(appSettings, i18n, pendingConnTimeout));
        tabPane.getTabs().add(connectionTab);

        Tab terminalTab = new Tab(i18n.get("preferences.tab.terminal"));
        terminalTab.setContent(buildTerminalPane(fontProfileService.activeProfile(), i18n, pending));
        tabPane.getTabs().add(terminalTab);

        Tab aboutTab = new Tab(i18n.get("preferences.tab.about"));
        aboutTab.setContent(buildAboutPane(i18n));
        tabPane.getTabs().add(aboutTab);

        return tabPane;
    }

    // ── General Tab ────────────────────────────────────────────────────────

    private static VBox buildGeneralPane(AppSettingsService appSettings, I18nService i18n,
                                          ThemeService themeService, String[] pendingLang, String[] pendingTheme) {
        String currentLang = appSettings.get("ui.language", "en");
        String currentTheme = appSettings.get("ui.theme", "DARK");

        // Language
        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(LANGUAGES.values());
        langCombo.setValue(LANGUAGES.getOrDefault(currentLang, "English"));
        langCombo.setPrefWidth(200);
        langCombo.valueProperty().addListener((o, ov, nv) -> {
            LANGUAGES.entrySet().stream()
                    .filter(e -> e.getValue().equals(nv))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(code -> pendingLang[0] = code);
        });

        // Theme
        ComboBox<String> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll("Dark", "Light");
        themeCombo.setValue("LIGHT".equals(currentTheme) ? "Light" : "Dark");
        themeCombo.setPrefWidth(200);
        themeCombo.valueProperty().addListener((o, ov, nv) ->
                pendingTheme[0] = "Light".equals(nv) ? "LIGHT" : "DARK");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));
        grid.add(new Label(i18n.get("preferences.general.language")), 0, 0);
        grid.add(langCombo, 1, 0);
        grid.add(new Label(i18n.get("preferences.general.theme")), 0, 1);
        grid.add(themeCombo, 1, 1);

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ── Connection Tab ─────────────────────────────────────────────────────

    private static VBox buildConnectionPane(AppSettingsService appSettings, I18nService i18n, String[] pendingConnTimeout) {
        String currentTimeout = appSettings.get("connection.timeout", "10");

        // Connection timeout
        TextField timeoutField = new TextField(currentTimeout);
        timeoutField.setPrefWidth(80);
        timeoutField.textProperty().addListener((o, ov, nv) -> {
            try {
                int v = Integer.parseInt(nv.trim());
                if (v > 0) pendingConnTimeout[0] = String.valueOf(v);
            } catch (NumberFormatException ignored) {}
        });

        Label timeoutUnit = new Label(i18n.get("preferences.connection.timeoutUnit"));

        // Keep alive interval
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

    // ── Terminal Tab ───────────────────────────────────────────────────────

    private static VBox buildTerminalPane(FontProfile current, I18nService i18n, FontProfile[] pending) {
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
        fontCombo.valueProperty().addListener((o, ov, nv) -> updatePreview(preview, nv, sizeSlider.getValue()));
        sizeSlider.valueProperty().addListener((o, ov, nv) -> updatePreview(preview, fontCombo.getValue(), nv.doubleValue()));
        updatePreview(preview, fontCombo.getValue(), sizeSlider.getValue());

        Runnable sync = () -> {
            double size    = parseDouble(sizeField.getText(), current.size());
            double spacing = parseDouble(spacingField.getText(), current.lineSpacing());
            pending[0] = new FontProfile(fontCombo.getValue(), size, ligaturesCheck.isSelected(), spacing);
        };
        fontCombo.valueProperty().addListener((o, ov, nv) -> sync.run());
        sizeSlider.valueProperty().addListener((o, ov, nv) -> sync.run());
        spacingSlider.valueProperty().addListener((o, ov, nv) -> sync.run());
        ligaturesCheck.selectedProperty().addListener((o, ov, nv) -> sync.run());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));

        grid.add(new Label(i18n.get("preferences.terminal.fontFamily")), 0, 0);
        grid.add(fontCombo, 1, 0, 2, 1);

        grid.add(new Label(i18n.get("preferences.terminal.fontSize")), 0, 1);
        HBox sizeRow = new HBox(8, sizeSlider, sizeField);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(sizeRow, 1, 1, 2, 1);

        grid.add(new Label(i18n.get("preferences.terminal.lineSpacing")), 0, 2);
        HBox spacingRow = new HBox(8, spacingSlider, spacingField);
        spacingRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(spacingRow, 1, 2, 2, 1);

        grid.add(ligaturesCheck, 1, 3, 2, 1);

        grid.add(new Label(i18n.get("preferences.terminal.preview")), 0, 4);
        grid.add(preview, 1, 4, 2, 1);

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ── About Tab ──────────────────────────────────────────────────────────

    private static VBox buildAboutPane(I18nService i18n) {
        VBox pane = new VBox(12);
        pane.setPadding(new Insets(24, 28, 16, 28));
        pane.setAlignment(Pos.TOP_CENTER);

        // App name
        Label appName = new Label("JLShell");
        appName.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");

        // Version
        Label version = new Label(i18n.get("preferences.about.version", VERSION));
        version.setStyle("-fx-font-size:12px;");

        // Description
        Label desc = new Label(i18n.get("preferences.about.description"));
        desc.setStyle("-fx-font-size:11px;");
        desc.setWrapText(true);
        desc.setMaxWidth(400);
        desc.setTextAlignment(TextAlignment.CENTER);

        // Separator
        Region sep = new Region();
        sep.setStyle("-fx-pref-height:1px;-fx-background-color:derive(-fx-text-fill, 50%);-fx-max-width:300;");
        sep.setPrefWidth(300);

        // Author info
        VBox authorBox = new VBox(4);
        authorBox.setAlignment(Pos.CENTER);

        Label authorTitle = new Label(i18n.get("preferences.about.author"));
        authorTitle.setStyle("-fx-font-size:11px;-fx-font-weight:bold;");

        Label authorName = new Label("voghost");
        authorName.setStyle("-fx-font-size:12px;");

        Label github = new Label("https://www.github.com/Voghost");
        github.setStyle("-fx-font-size:11px;-fx-text-fill:#4d9cf8;-fx-underline:true;");
        github.setOnMouseClicked(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://www.github.com/Voghost")); }
            catch (Exception ignored) {}
        });
        github.setStyle("-fx-font-size:11px;-fx-text-fill:#4d9cf8;-fx-cursor:hand;");

        authorBox.getChildren().addAll(authorTitle, authorName, github);

        // Tech stack
        Region sep2 = new Region();
        sep2.setStyle("-fx-pref-height:1px;-fx-background-color:derive(-fx-text-fill, 50%);-fx-max-width:300;");
        sep2.setPrefWidth(300);

        Label techTitle = new Label(i18n.get("preferences.about.techStack"));
        techTitle.setStyle("-fx-font-size:11px;-fx-font-weight:bold;");

        Label techDetail = new Label("Java 21 · JavaFX 21 · SSHJ · JediTerm · JDBI 3 · SQLite");
        techDetail.setStyle("-fx-font-size:10px;");
        techDetail.setWrapText(true);
        techDetail.setMaxWidth(400);
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
