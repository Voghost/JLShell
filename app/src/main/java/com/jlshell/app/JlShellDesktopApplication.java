package com.jlshell.app;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import com.jlshell.ui.view.MainWindow;
import com.jlshell.ui.support.BundledFontLoader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.LoggerFactory;

public class JlShellDesktopApplication extends Application {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JlShellDesktopApplication.class);

    private AppContext appContext;
    private TrayIcon trayIcon;
    private SplashStage splash;

    public static void main(String[] args) {
        // 全局未捕获异常处理：记录到日志，避免 Windows 闪退时看不到任何信息
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("Uncaught exception in thread [" + thread.getName() + "]");
            throwable.printStackTrace(System.err);
            LoggerFactory.getLogger(JlShellDesktopApplication.class)
                    .error("Uncaught exception in thread [{}]", thread.getName(), throwable);
        });
        launch(args);
    }

    @Override
    public void init() {
        BundledFontLoader.load();
        // AppContext 里 305 主题 JSON / 4.7MB 插件 JAR / 2MB AWT 字体都已延迟加载，
        // 所以 init() 阶段很快；splash 在 start() 显示遮盖 JavaFX 冷启动。
        splash = new SplashStage();
    }

    @Override
    public void start(Stage stage) {
        splash.show();
        log.info("Splash shown, starting AppContext...");

        try {
            appContext = new AppContext();
        } catch (Exception e) {
            log.error("Failed to initialize AppContext", e);
            showFatalError(e);
            return;
        }
        log.info("AppContext created, building scene...");

        MainWindow mainWindow = appContext.getMainWindow();

        // On Windows, remove the OS title bar and use a custom one embedded in the app.
        if (isWindows()) {
            stage.initStyle(StageStyle.UNDECORATED);
        }

        stage.setTitle("JLShell");
        stage.setScene(mainWindow.createScene(stage));
        log.info("Scene created, configuring stage...");
        // Minimum window size is now set adaptively in MainWindow.createScene()
        // based on Screen.getPrimary().getVisualBounds(), so the app scales
        // properly on HiDPI / small screens (e.g. 1920×1080 @ 150 %).

        // JavaFX window icon (taskbar + title bar on Windows/Linux)
        try (InputStream is = getClass().getResourceAsStream("/icons/app_icon.png")) {
            if (is != null) {
                stage.getIcons().add(new javafx.scene.image.Image(is));
            }
        } catch (Exception ignored) {}

        java.awt.Image awtIcon = loadAwtIcon();

        // Ensure AWT toolkit is initialized before registering Desktop handlers.
        // On macOS, Desktop.setPreferencesHandler() etc. only work after AWT is loaded.
        java.awt.Toolkit.getDefaultToolkit();

        // Register macOS application-menu handlers (Preferences, About, Quit).
        // This causes these items to appear in the native JLShell application menu.
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(e ->
                    Platform.runLater(() -> mainWindow.openPreferences(stage)));
            }
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler(e ->
                    Platform.runLater(() -> showAboutDialog(stage)));
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((e, response) -> {
                    scheduleShutdown();
                    response.cancelQuit();
                });
            }
        }

        stage.setOnCloseRequest(event -> {
            event.consume();
            scheduleShutdown();
        });

        stage.show();
        log.info("Stage shown, JLShell started successfully");
        splash.hide();
        installSystemTray(stage, awtIcon);
    }

    private void scheduleShutdown() {
        Platform.runLater(() -> {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        });
        Thread shutdownThread = new Thread(() -> {
            try {
                if (appContext != null) {
                    appContext.close();
                }
            } finally {
                Platform.exit();
                Runtime.getRuntime().halt(0);
            }
        }, "jlshell-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    @Override
    public void stop() {}

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 显示致命错误对话框（AppContext 初始化失败时调用） */
    private void showFatalError(Throwable t) {
        splash.hide();
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR,
                "JLShell failed to start:\n" + msg,
                javafx.scene.control.ButtonType.OK);
        alert.setTitle("JLShell — Fatal Error");
        alert.setHeaderText("Startup failed");
        alert.showAndWait();
        Platform.exit();
    }

    private static String getAppVersion() {
        String v = JlShellDesktopApplication.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.13";
    }

    private void showAboutDialog(Stage stage) {
        javafx.scene.control.Alert about = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        about.setTitle("About JLShell");
        about.setHeaderText("JLShell");
        about.setContentText("SSH / SFTP Client\nVersion " + getAppVersion());
        about.initOwner(stage);
        about.showAndWait();
    }

    private void installSystemTray(Stage stage, java.awt.Image icon) {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open JLShell");
            MenuItem hideItem = new MenuItem("Hide");
            MenuItem exitItem = new MenuItem("Exit");

            openItem.addActionListener(e -> Platform.runLater(() -> { stage.show(); stage.toFront(); }));
            hideItem.addActionListener(e -> Platform.runLater(stage::hide));
            exitItem.addActionListener(e -> scheduleShutdown());

            menu.add(openItem);
            menu.add(hideItem);
            menu.addSeparator();
            menu.add(exitItem);

            trayIcon = new TrayIcon(icon != null ? icon : fallbackAwtIcon(16), "JLShell", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> { stage.show(); stage.toFront(); }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            trayIcon = null;
        }
    }

    private java.awt.Image loadAwtIcon() {
        try (InputStream is = getClass().getResourceAsStream("/icons/app_icon.png")) {
            if (is != null) return ImageIO.read(is);
        } catch (Exception ignored) {}
        return fallbackAwtIcon(256);
    }

    private java.awt.Image fallbackAwtIcon(int size) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = size / 4;
        g.setColor(new java.awt.Color(15, 23, 36));
        g.fillRoundRect(0, 0, size, size, arc, arc);
        int pad = size / 5;
        g.setColor(new java.awt.Color(56, 189, 248));
        g.fillRoundRect(pad, pad, size - pad * 2, size - pad * 2, arc / 2, arc / 2);
        g.dispose();
        return img;
    }
}

