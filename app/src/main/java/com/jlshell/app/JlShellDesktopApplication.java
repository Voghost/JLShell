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
import com.jlshell.ui.support.RestartHelper;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.LoggerFactory;

public class JlShellDesktopApplication extends Application {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JlShellDesktopApplication.class);

    private AppContext appContext;
    private TrayIcon trayIcon;
    private final StartupSplash splash = Launcher.startupSplash();
    private Throwable initializationFailure;
    private boolean fatalErrorShown;

    public static void main(String[] args) {
        // 全局未捕获异常处理：记录到日志，避免 Windows 闪退时看不到任何信息
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionReporter(System.err,
                (thread, throwable) -> log.error("Uncaught exception in thread [{}]", thread.getName(), throwable)));
        // 兼容直接调用 Application.main 的入口；正常 Launcher 路径已绘制过首帧。
        Launcher.showStartupBeforeJavaFx();
        StartupDiagnostics.logBeforeJavaFxLaunch();
        launch(args);
    }

    @Override
    public void init() {
        try {
            BundledFontLoader.load();
        } catch (Exception | LinkageError failure) {
            initializationFailure = failure;
        }
    }

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        if (initializationFailure != null) { showFatalError(initializationFailure); return; }
        try {
            startApplication(stage);
        } catch (Exception | LinkageError failure) {
            log.error("Failed to start JLShell", failure);
            showFatalError(failure);
        }
    }

    private void startApplication(Stage stage) {
        splash.updateStatus("正在加载连接与偏好设置", "Loading connections and preferences");

        try {
            appContext = new AppContext();
        } catch (Exception e) {
            log.error("Failed to initialize AppContext", e);
            showFatalError(e);
            return;
        }

        splash.updateStatus("正在打开工作空间", "Opening your workspace");
        MainWindow mainWindow = appContext.getMainWindow();

        // On Windows, use TRANSPARENT stage style so the window background is
        // truly transparent — rounded corners on .app-root won't show white
        // corners.  UNDECORATED still paints an opaque white background.
        if (isWindows()) {
            stage.initStyle(StageStyle.TRANSPARENT);
        } else if (isMac() && Platform.isSupported(ConditionalFeature.UNIFIED_WINDOW)) {
            // Keep the native macOS traffic-light controls, while allowing the
            // JavaFX scene background to extend into the title-bar decoration.
            // A regular DECORATED stage follows the launcher process appearance,
            // which can differ between a jpackage app and an IDE/direct launch.
            stage.initStyle(StageStyle.UNIFIED);
        }

        stage.setTitle("JLShell");
        stage.setScene(mainWindow.createScene(stage));
        // Minimum window size is now set adaptively in MainWindow.createScene()
        // based on Screen.getPrimary().getVisualBounds(), so the app scales
        // properly on HiDPI / small screens (e.g. 1920×1080 @ 150 %).

        // JavaFX window icons (taskbar + Alt-Tab on Windows/Linux). Supplying
        // multiple sizes avoids Windows scaling a tiny icon on high-DPI setups.
        addStageIcon(stage, "/icons/app_icon_16.png");
        addStageIcon(stage, "/icons/app_icon_32.png");
        addStageIcon(stage, "/icons/app_icon_48.png");
        addStageIcon(stage, "/icons/app_icon_256.png");
        addStageIcon(stage, "/icons/brand/jlshell.png");

        // AWT 操作不能在 FX/AppKit 回调中争用 Swing 的 AWTTreeLock。
        // 系统菜单、托盘及 Swing 窗口全部异步交给 EDT，FX 线程从不等待 EDT。
        javax.swing.SwingUtilities.invokeLater(() -> registerDesktopHandlers(mainWindow, stage));

        stage.setOnCloseRequest(event -> {
            event.consume();
            scheduleShutdown();
        });

        if (!"javafx".equalsIgnoreCase(System.getProperty("jlshell.ui.renderer", "swing"))) {
            // 本分支默认使用原生 Swing 工作台；保留 JavaFX 渲染入口供对照验收。
            Platform.setImplicitExit(false);
            mainWindow.showSwingWindow(stage, this::scheduleShutdown).whenComplete((unused, error) ->
                    Platform.runLater(() -> {
                        splash.hide();
                        if (error != null) { showFatalError(error); return; }
                        UpdateActivationMarker.markStartupConfirmed();
                        installSystemTray();
                    }));
        } else {
            stage.show();
            UpdateActivationMarker.markStartupConfirmed();
            splash.hide();
            installSystemTray();
        }
    }

    private void scheduleShutdown() {
        Platform.runLater(() -> {
            if (appContext != null) appContext.getMainWindow().closeSwingWindow();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (trayIcon != null && SystemTray.isSupported()) {
                    SystemTray.getSystemTray().remove(trayIcon);
                    trayIcon = null;
                }
            });
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

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    /**
     * Schedule a restart: shuts down cleanly, then re-launches the application.
     * Used after updates or when settings require a restart to take effect.
     */
    public void scheduleRestart() {
        com.jlshell.ui.support.RestartHelper.scheduleRestartAndThen(() -> {
            if (appContext != null) {
                appContext.close();
            }
        });
    }

    @Override
    public void stop() {}

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 显示致命错误对话框（AppContext 初始化失败时调用） */
    private void showFatalError(Throwable t) {
        if (fatalErrorShown) return;
        fatalErrorShown = true;
        splash.hide();
        javax.swing.SwingUtilities.invokeLater(() ->
                com.jlshell.ui.swing.SwingStartupFailure.create(t, () -> {
                    Thread cleanup = new Thread(() -> {
                        try {
                            if (appContext != null) appContext.close();
                        } catch (Exception error) {
                            log.warn("Failed to clean up after startup failure", error);
                        } finally {
                            Platform.exit();
                            System.exit(1);
                        }
                    }, "jlshell-startup-failure-cleanup");
                    cleanup.start();
                }).setVisible(true));
    }

    private void addStageIcon(Stage stage, String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                stage.getIcons().add(new javafx.scene.image.Image(is));
            }
        } catch (Exception ignored) {}
    }

    private void registerDesktopHandlers(MainWindow mainWindow, Stage stage) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Desktop handlers require EDT");
        }
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
                    Platform.runLater(() -> mainWindow.openAbout(stage)));
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((e, response) -> {
                    scheduleShutdown();
                    response.cancelQuit();
                });
            }
            // AppContext 已读取 ui.language。菜单仅在本次启动时选择语言，语言更改重启后生效。
            com.jlshell.ui.swing.MacApplicationMenu.localize(java.util.Locale.getDefault())
                    .exceptionally(error -> {
                        log.warn("Unable to localize macOS application menu", error);
                        return null;
                    });
        }
    }

    private void installSystemTray() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            javax.swing.SwingUtilities.invokeLater(this::installSystemTray);
            return;
        }
        if (!SystemTray.isSupported()) return;
        java.awt.Image icon = loadAwtIcon();
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open JLShell");
            MenuItem hideItem = new MenuItem("Hide");
            MenuItem exitItem = new MenuItem("Exit");

            openItem.addActionListener(e -> Platform.runLater(() -> appContext.getMainWindow().showMainWindow()));
            hideItem.addActionListener(e -> Platform.runLater(() -> appContext.getMainWindow().hideMainWindow()));
            exitItem.addActionListener(e -> scheduleShutdown());

            menu.add(openItem);
            menu.add(hideItem);
            menu.addSeparator();
            menu.add(exitItem);

            trayIcon = new TrayIcon(icon != null ? icon : fallbackAwtIcon(16), "JLShell", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> appContext.getMainWindow().showMainWindow()));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            trayIcon = null;
        }
    }

    private java.awt.Image loadAwtIcon() {
        try (InputStream is = getClass().getResourceAsStream("/icons/brand/jlshell.png")) {
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
