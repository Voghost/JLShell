package com.jlshell.app;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TrayIcon;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import com.jlshell.ui.view.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class JlShellDesktopApplication extends Application {

    private AppContext appContext;
    private TrayIcon trayIcon;

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "JLShell");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JLShell");
        launch(args);
    }

    @Override
    public void init() {
        appContext = new AppContext();
    }

    @Override
    public void start(Stage stage) {
        MainWindow mainWindow = appContext.getMainWindow();
        stage.setTitle("JLShell");
        stage.setScene(mainWindow.createScene(stage));
        stage.setMinWidth(1200);
        stage.setMinHeight(780);

        // JavaFX window icon (taskbar + title bar on Windows/Linux)
        try (InputStream is = getClass().getResourceAsStream("/icons/app_icon.png")) {
            if (is != null) {
                stage.getIcons().add(new javafx.scene.image.Image(is));
            }
        } catch (Exception ignored) {}

        // macOS Dock + Windows taskbar icon via AWT Taskbar API
        java.awt.Image awtIcon = loadAwtIcon();
        if (awtIcon != null) {
            try {
                if (Taskbar.isTaskbarSupported()) {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                        taskbar.setIconImage(awtIcon);
                    }
                }
            } catch (Exception ignored) {}
        }

        stage.setOnCloseRequest(event -> {
            event.consume();
            scheduleShutdown();
        });

        stage.show();
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

