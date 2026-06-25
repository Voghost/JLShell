package com.jlshell.ui.dialog;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/**
 * 独立的"关于"对话框，从菜单栏 Help → About 打开。
 * 复用 PreferencesDialog 的版本号读取逻辑和内容布局。
 */
public class AboutDialog {

    private AboutDialog() {}

    public static void show(Stage owner, I18nService i18n, ThemeService themeService) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("menu.help.about"));
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);

        dialog.getDialogPane().setContent(buildAboutContent(i18n));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static VBox buildAboutContent(I18nService i18n) {
        // 复用 PreferencesDialog 中相同的版本读取方式
        String version = PreferencesDialog.getVersion();

        VBox pane = new VBox(12);
        pane.setPadding(new Insets(24, 28, 16, 28));
        pane.setAlignment(Pos.TOP_CENTER);

        Label appName = new Label("JLShell");
        appName.setStyle("-fx-font-size:20px;-fx-font-weight:bold;");

        Label versionLabel = new Label(i18n.get("preferences.about.version", version));
        versionLabel.setStyle("-fx-font-size:12px;");

        Label desc = new Label(i18n.get("preferences.about.description"));
        desc.setStyle("-fx-font-size:11px;");
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
        authorTitle.setStyle("-fx-font-size:11px;-fx-font-weight:bold;");

        Label authorName = new Label("voghost");
        authorName.setStyle("-fx-font-size:12px;");

        Label github = new Label("https://www.github.com/Voghost");
        github.setStyle("-fx-font-size:11px;-fx-text-fill:#4d9cf8;-fx-cursor:hand;");
        github.setOnMouseClicked(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://www.github.com/Voghost")); }
            catch (Exception ignored) {}
        });

        authorBox.getChildren().addAll(authorTitle, authorName, github);

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

        pane.getChildren().addAll(appName, versionLabel, desc, sep, authorBox, sep2, techTitle, techDetail);
        return pane;
    }
}
