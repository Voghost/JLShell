package com.jlshell.app;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import com.jlshell.ui.view.MainWindow;

/** 使用原生 AWT/Swing 启动桌面应用。服务初始化由启动线程完成。 */
public final class JlShellDesktopApplication {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JlShellDesktopApplication.class);
    private AppContext appContext;
    private TrayIcon trayIcon;
    private final StartupSplash splash = Launcher.startupSplash();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private boolean fatalErrorShown;

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionReporter(System.err,
                (thread, error) -> log.error("Uncaught exception in thread [{}]", thread.getName(), error)));
        Launcher.showStartupBeforeToolkit();
        StartupDiagnostics.logBeforeLaunch();
        new JlShellDesktopApplication().startApplication();
    }

    private void startApplication() {
        splash.updateStatus("正在加载连接与偏好设置", "Loading connections and preferences");
        try {
            appContext = StartupTiming.trace().call("app-context", () -> new AppContext(StartupTiming.trace()));
            splash.updateStatus("正在打开工作空间", "Opening your workspace");
            MainWindow mainWindow = appContext.getMainWindow();
            mainWindow.showSwingWindow(null, this::scheduleShutdown, StartupTiming.trace()).whenComplete((ignored, error) -> {
                splash.hide();
                if (error != null) { showFatalError(error); return; }
                StartupTiming.trace().mark("workbench-shown");
                UpdateActivationMarker.markStartupConfirmed();
                javax.swing.SwingUtilities.invokeLater(() -> { registerDesktopHandlers(mainWindow); installSystemTray(); });
            });
        } catch (Exception | LinkageError error) {
            log.error("Failed to start JLShell", error); showFatalError(error);
        }
    }

    private void scheduleShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        Thread shutdown = new Thread(() -> {
            try {
                if (appContext != null) appContext.close();
            } finally {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (appContext != null) appContext.getMainWindow().closeSwingWindow();
                    if (trayIcon != null && SystemTray.isSupported()) SystemTray.getSystemTray().remove(trayIcon);
                    System.exit(0);
                });
            }
        }, "jlshell-shutdown");
        shutdown.start();
    }

    public void scheduleRestart() {
        com.jlshell.ui.support.RestartHelper.scheduleRestartAndThen(() -> { if (appContext != null) appContext.close(); });
    }

    private void showFatalError(Throwable error) {
        if (fatalErrorShown) return;
        fatalErrorShown = true; StartupTiming.trace().failed(error); splash.hide();
        javax.swing.SwingUtilities.invokeLater(() -> com.jlshell.ui.swing.SwingStartupFailure.create(error, () -> {
            Thread cleanup = new Thread(() -> {
                try { if (appContext != null) appContext.close(); }
                catch (Exception failure) { log.warn("Failed to clean up after startup failure", failure); }
                finally { System.exit(1); }
            }, "jlshell-startup-failure-cleanup");
            cleanup.start();
        }).setVisible(true));
    }

    private void registerDesktopHandlers(MainWindow mainWindow) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Desktop handlers require EDT");
        }
        // Register macOS application-menu handlers (Preferences, About, Quit).
        // This causes these items to appear in the native JLShell application menu.
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(e ->
                    javax.swing.SwingUtilities.invokeLater(() -> mainWindow.openPreferences(null)));
            }
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler(e ->
                    javax.swing.SwingUtilities.invokeLater(() -> mainWindow.openAbout(null)));
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

            openItem.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(() -> appContext.getMainWindow().showMainWindow()));
            hideItem.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(() -> appContext.getMainWindow().hideMainWindow()));
            exitItem.addActionListener(e -> scheduleShutdown());

            menu.add(openItem);
            menu.add(hideItem);
            menu.addSeparator();
            menu.add(exitItem);

            trayIcon = new TrayIcon(icon != null ? icon : fallbackAwtIcon(16), "JLShell", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> javax.swing.SwingUtilities.invokeLater(() -> appContext.getMainWindow().showMainWindow()));
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
