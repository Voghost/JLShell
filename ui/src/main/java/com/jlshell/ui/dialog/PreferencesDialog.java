package com.jlshell.ui.dialog;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.ui.service.I18nService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.awt.Canvas;
import java.awt.Font;
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
 * 采用 TabPane 结构，每个 Tab 对应一类配置，方便未来扩展新配置项。
 *
 * <p>扩展方式：新增配置类别时，在 {@link #buildTabPane} 中添加新 Tab 即可。
 */
public class PreferencesDialog {

    private static final List<String> PREFERRED_MONO = List.of(
            "JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Fira Code",
            "Source Code Pro", "Hack", "Inconsolata", "Menlo", "Monaco",
            "Consolas", "Courier New", "SF Mono", "Ubuntu Mono"
    );

    /** language code → display name */
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();
    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("zh_CN", "中文 (简体)");
    }

    private PreferencesDialog() {}

    public static void show(Stage owner, FontProfileService fontProfileService, AppSettingsService appSettings, I18nService i18n) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("preferences.title"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(
                PreferencesDialog.class.getResource("/css/dark-theme.css").toExternalForm());
        dialog.getDialogPane().setPrefWidth(520);

        FontProfile[] pending = { fontProfileService.activeProfile() };
        String[] pendingLang = { appSettings.get("ui.language", "en") };

        TabPane tabs = buildTabPane(fontProfileService, appSettings, i18n, pending, pendingLang);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                fontProfileService.updateActiveProfile(pending[0]);
                String prevLang = appSettings.get("ui.language", "en");
                appSettings.set("ui.language", pendingLang[0]);
                if (!prevLang.equals(pendingLang[0])) {
                    applyLocale(pendingLang[0]);
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.INFORMATION,
                            i18n.get("preferences.general.restartRequired"),
                            javafx.scene.control.ButtonType.OK);
                    alert.showAndWait();
                }
            }
            return null;
        });

        dialog.showAndWait();
    }

    private static void applyLocale(String langCode) {
        Locale locale = langCode.contains("_")
                ? new Locale(langCode.split("_")[0], langCode.split("_")[1])
                : new Locale(langCode);
        Locale.setDefault(locale);
    }

    private static TabPane buildTabPane(FontProfileService fontProfileService, AppSettingsService appSettings, I18nService i18n, FontProfile[] pending, String[] pendingLang) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ── General Tab ───────────────────────────────────────────────
        Tab generalTab = new Tab(i18n.get("preferences.tab.general"));
        generalTab.setContent(buildGeneralPane(appSettings, i18n, pendingLang));
        tabPane.getTabs().add(generalTab);

        // ── Terminal Tab ──────────────────────────────────────────────
        Tab terminalTab = new Tab(i18n.get("preferences.tab.terminal"));
        terminalTab.setContent(buildTerminalPane(fontProfileService.activeProfile(), i18n, pending));
        tabPane.getTabs().add(terminalTab);

        return tabPane;
    }

    private static VBox buildGeneralPane(AppSettingsService appSettings, I18nService i18n, String[] pendingLang) {
        String currentLang = appSettings.get("ui.language", "en");

        ComboBox<String> langCombo = new ComboBox<>();
        langCombo.getItems().addAll(LANGUAGES.values());
        String currentDisplay = LANGUAGES.getOrDefault(currentLang, "English");
        langCombo.setValue(currentDisplay);
        langCombo.setPrefWidth(200);

        langCombo.valueProperty().addListener((o, ov, nv) -> {
            LANGUAGES.entrySet().stream()
                    .filter(e -> e.getValue().equals(nv))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(code -> pendingLang[0] = code);
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 8, 20));
        grid.add(new Label(i18n.get("preferences.general.language")), 0, 0);
        grid.add(langCombo, 1, 0);

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(8));
        return pane;
    }

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

        // 任意控件变化时更新 pending
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

    private static void updatePreview(Text preview, String family, double size) {
        preview.setFont(javafx.scene.text.Font.font(family, size));
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
            Font font = new Font(family, Font.PLAIN, 12);
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
