package com.jlshell.ui.swing;

import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;
import java.awt.Desktop;

/** Publishes the Swing workbench menus in the macOS screen menu bar. */
public final class MacMenuBridge {
    private static JMenuBar installed;

    private MacMenuBridge() {}

    public static boolean isInstalled() { return installed != null; }

    public static void install(JMenuBar menuBar) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Native menu requires EDT");
        if (!com.formdev.flatlaf.util.SystemInfo.isMacOS || !Desktop.isDesktopSupported()) return;
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.APP_MENU_BAR)) return;
        desktop.setDefaultMenuBar(menuBar);
        installed = menuBar;
    }

    public static void uninstall(JMenuBar menuBar) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Native menu requires EDT");
        if (installed != menuBar) return;
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_MENU_BAR)) desktop.setDefaultMenuBar(null);
        }
        installed = null;
    }
}
