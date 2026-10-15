package com.jlshell.app;

/**
 * Entry point that sets macOS system properties before AWT/JavaFX initialises.
 * macOS derives the application menu name from the main class's simple name,
 * so this class is deliberately named "Launcher" — but we override it with
 * {@code apple.awt.application.name} which takes precedence when set early enough.
 */
public class Launcher {
    private static final StartupSplash STARTUP_SPLASH = new StartupSplash();

    static StartupSplash startupSplash() { return STARTUP_SPLASH; }

    static void showStartupBeforeJavaFx() {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Startup must run on the launcher thread");
        STARTUP_SPLASH.show();
        try {
            STARTUP_SPLASH.firstFrame().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            STARTUP_SPLASH.hide();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                 | java.util.concurrent.CancellationException failure) {
            // 视觉反馈失败不应阻止应用启动；失败提示仍走独立 Swing 入口。
            System.err.println("Unable to show startup window: " + failure);
            STARTUP_SPLASH.hide();
        }
    }

    public static void main(String[] args) {
        // Must be set before any AWT/JavaFX class is loaded
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("apple.awt.application.name", "JLShell");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JLShell");

        showStartupBeforeJavaFx();
        JlShellDesktopApplication.main(args);
    }
}
