package com.jlshell.app;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 启动时的预加载 splash 窗口。
 * init() 末尾构造，start() 里 show()；主窗口 show() 之后 hide()。
 * 不依赖 AppContext/I18nService/ThemeService —— splash 出现时这些都还没准备好。
 */
public class SplashStage {

    private static final double WIDTH = 280;
    private static final double HEIGHT = 240;

    private Stage stage;

    public void show() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::show);
            return;
        }
        if (stage != null) {
            return;
        }

        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle("JLShell");

        ImageView icon = new ImageView(loadIcon());
        icon.setFitWidth(96);
        icon.setFitHeight(96);
        icon.setPreserveRatio(true);

        Label nameLabel = new Label("JLShell");
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 20; -fx-font-weight: bold;");

        Label versionLabel = new Label("v" + readVersion());
        versionLabel.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 11;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setProgress(-1);
        progress.setPrefSize(28, 28);
        progress.setStyle("-fx-progress-color: #38bdf8;");

        VBox content = new VBox(10, icon, nameLabel, versionLabel, progress);
        content.setAlignment(Pos.CENTER);
        content.setPrefSize(WIDTH, HEIGHT);
        content.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 12;");

        StackPane root = new StackPane(content);
        root.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(root);
        scene.setFill(null);
        stage.setScene(scene);

        Screen screen = Screen.getPrimary();
        Rectangle2D vis = screen.getVisualBounds();
        stage.setX(vis.getMinX() + (vis.getWidth() - WIDTH) / 2);
        stage.setY(vis.getMinY() + (vis.getHeight() - HEIGHT) / 2);

        stage.show();
    }

    public void hide() {
        if (stage == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::hide);
            return;
        }
        stage.hide();
        stage.close();
        stage = null;
    }

    private Image loadIcon() {
        try (var is = SplashStage.class.getResourceAsStream("/icons/app_icon.png")) {
            if (is != null) return new Image(is);
        } catch (Exception ignored) {}
        return null;
    }

    private String readVersion() {
        String v = SplashStage.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0";
    }
}
