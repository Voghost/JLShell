package com.jlshell.app;

/**
 * Entry point that sets macOS system properties before AWT/JavaFX initialises.
 * macOS derives the application menu name from the main class's simple name,
 * so this class is deliberately named "Launcher" — but we override it with
 * {@code apple.awt.application.name} which takes precedence when set early enough.
 */
public class Launcher {

    public static void main(String[] args) {
        // Must be set before any AWT/JavaFX class is loaded
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("apple.awt.application.name", "JLShell");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JLShell");

        JlShellDesktopApplication.main(args);
    }
}
