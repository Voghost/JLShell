package com.jlshell.app;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

import com.jlshell.ui.view.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX 桌面应用启动入口。
 */
public class JlShellDesktopApplication extends Application {

    private ConfigurableApplicationContext applicationContext;
    private TrayIcon trayIcon;

    public static void main(String[] args) {
        // macOS: app name in Dock and menu bar
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "JLShell");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JLShell");
        launch(args);
    }

    @Override
    public void init() {
        applicationContext = new SpringApplicationBuilder(JlShellSpringApplication.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(String[]::new));
    }

    @Override
    public void start(Stage stage) {
        MainWindow mainWindow = applicationContext.getBean(MainWindow.class);
        stage.setTitle("JLShell");
        stage.setScene(mainWindow.createScene(stage));
        stage.setMinWidth(1200);
        stage.setMinHeight(780);

        // macOS Dock icon
        Image appIcon = createAppIcon(256);
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(appIcon);
                }
            }
        } catch (Exception ignored) {}

        // Close button → schedule shutdown on a background thread to avoid
        // blocking the JavaFX Application Thread (which would cause a freeze).
        stage.setOnCloseRequest(event -> {
            event.consume();
            scheduleShutdown();
        });

        stage.show();
        installSystemTray(stage, appIcon);
    }

    private void scheduleShutdown() {
        // Hide the window immediately so the UI feels responsive
        Platform.runLater(() -> {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        });
        // Do the heavy Spring shutdown off the JavaFX thread
        Thread shutdownThread = new Thread(() -> {
            try {
                if (applicationContext != null) {
                    applicationContext.close();
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
    public void stop() {
        // Called by JavaFX when Platform.exit() completes — nothing to do here,
        // shutdown is already handled by scheduleShutdown().
    }

    private void installSystemTray(Stage stage, Image icon) {
        if (!SystemTray.isSupported()) {
            return;
        }

        try {
            PopupMenu menu = new PopupMenu();
            MenuItem openItem = new MenuItem("Open JLShell");
            MenuItem hideItem = new MenuItem("Hide");
            MenuItem exitItem = new MenuItem("Exit");

            openItem.addActionListener(event -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            hideItem.addActionListener(event -> Platform.runLater(stage::hide));
            exitItem.addActionListener(event -> scheduleShutdown());

            menu.add(openItem);
            menu.add(hideItem);
            menu.addSeparator();
            menu.add(exitItem);

            trayIcon = new TrayIcon(createAppIcon(16), "JLShell", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException exception) {
            trayIcon = null;
        }
    }

    private Image createAppIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = size / 4;
        g.setColor(new Color(15, 23, 36));
        g.fillRoundRect(0, 0, size, size, arc, arc);
        // inner accent square
        int pad = size / 5;
        int inner = size - pad * 2;
        int innerArc = arc / 2;
        g.setColor(new Color(56, 189, 248));
        g.fillRoundRect(pad, pad, inner, inner, innerArc, innerArc);
        g.dispose();
        return image;
    }
}
