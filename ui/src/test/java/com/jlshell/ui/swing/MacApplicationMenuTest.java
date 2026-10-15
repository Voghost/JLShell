package com.jlshell.ui.swing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Desktop;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

/** Inspect the actual AppKit menu, including the JDK-installed selectors and shortcuts. */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class MacApplicationMenuTest {
    @Test void localizesNativeTitlesWhilePreservingActionsShortcutsAndServices() throws Exception {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        JMenuBar bar = new JMenuBar();
        SwingUtilities.invokeAndWait(() -> {
            Desktop desktop = Desktop.getDesktop();
            desktop.setAboutHandler(event -> {});
            desktop.setPreferencesHandler(event -> {});
            bar.add(new JMenu("文件"));
            MacMenuBridge.install(bar);
        });
        try {
            List<MacApplicationMenu.Item> before = MacApplicationMenu.snapshot().get(10, TimeUnit.SECONDS);
            MacApplicationMenu.localize(Locale.SIMPLIFIED_CHINESE).get(10, TimeUnit.SECONDS);
            List<MacApplicationMenu.Item> chinese = MacApplicationMenu.snapshot().get(10, TimeUnit.SECONDS);
            assertTrue(chinese.stream().anyMatch(item -> item.title().equals("关于 JLShell")), chinese::toString);
            for (String title : List.of("设置…", "服务", "隐藏 JLShell", "隐藏其他应用", "显示全部", "退出 JLShell"))
                assertTrue(chinese.stream().anyMatch(item -> item.title().equals(title)), () -> title + ": " + chinese);
            assertEquals(before.stream().map(MacApplicationMenuTest::behavior).toList(),
                    chinese.stream().map(MacApplicationMenuTest::behavior).toList());
            MacApplicationMenu.localize(Locale.ENGLISH).get(10, TimeUnit.SECONDS);
            List<MacApplicationMenu.Item> english = MacApplicationMenu.snapshot().get(10, TimeUnit.SECONDS);
            for (String title : List.of("About JLShell", "Settings…", "Services", "Hide JLShell", "Quit JLShell"))
                assertTrue(english.stream().anyMatch(item -> item.title().equals(title)), () -> title + ": " + english);
            assertEquals(before.stream().map(MacApplicationMenuTest::behavior).toList(),
                    english.stream().map(MacApplicationMenuTest::behavior).toList());
        } finally { SwingUtilities.invokeAndWait(() -> MacMenuBridge.uninstall(bar)); }
    }

    private static List<Object> behavior(MacApplicationMenu.Item item) {
        return List.of(item.action(), item.key(), item.modifiers(), item.target(), item.submenu());
    }
}
