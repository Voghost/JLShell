package com.jlshell.ui.swing.idea;

import com.formdev.flatlaf.util.SystemInfo;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.swing.SwingTheme;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class IdeaPopupRenderingTest {
    @Test void popupPreservesUiFontAndUsesOpaqueWindowsTextSurface() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK,"#3574f0",null,13);
            JFrame owner=new JFrame();
            IdeaListPopup popup=new IdeaListPopup(List.of(IdeaListPopup.Row.action("Find 查找",null,"Ctrl+F",null,()->{})),190,false,"");
            try {
                JButton anchor=new JButton("Open");owner.add(anchor);owner.setSize(400,240);owner.setVisible(true);
                popup.show(anchor,0,anchor.getHeight());
                var window=(JWindow)SwingUtilities.getWindowAncestor(popup);
                assertTrue(window.isShowing());
                if(SystemInfo.isWindows) {
                    assertTrue(window.isOpaque(),"Windows popup must retain an opaque surface for LCD text");
                    assertEquals(255,window.getBackground().getAlpha());
                    boolean cjkInstalled = java.util.Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                            .anyMatch(name -> name.equalsIgnoreCase("Microsoft YaHei UI") || name.equalsIgnoreCase("Microsoft YaHei") || name.equalsIgnoreCase("SimSun"));
                    if (cjkInstalled) assertEquals(-1,UIManager.getFont("defaultFont").canDisplayUpTo("Find 查找 Paste 复制"));
                }
                JList<?> list=(JList<?>)descendant(popup,JList.class);
                assertEquals(UIManager.getFont("defaultFont").getFamily(),list.getFont().getFamily());
                assertEquals(13,list.getFont().getSize());
                popup.setVisible(false);
                assertFalse(window.isDisplayable());
                SwingTheme.install(AppTheme.LIGHT,"#3574f0",Font.SANS_SERIF,14);
                assertEquals(14,UIManager.getFont("defaultFont").getSize());
            } finally {popup.setVisible(false);owner.dispose();}
        }).get(15,TimeUnit.SECONDS);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void capturesActualWindowsPopupForVisualReview() throws Exception {
        var owner = new java.util.concurrent.atomic.AtomicReference<JFrame>();
        var popup = new java.util.concurrent.atomic.AtomicReference<IdeaListPopup>();
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
                JFrame frame = new JFrame("Windows popup rendering");
                JButton anchor = new JButton("Menu font: " + UIManager.getFont("defaultFont").getFontName());
                frame.add(anchor); frame.setSize(480, 120); frame.setLocation(100, 100); frame.setVisible(true); owner.set(frame);
                var menu = new IdeaListPopup(List.of(
                        IdeaListPopup.Row.action("Find 查找", null, "Ctrl+F", null, () -> {}),
                        IdeaListPopup.Row.action("Copy 复制", null, "Ctrl+C", null, () -> {}),
                        IdeaListPopup.Row.action("Paste 粘贴", null, "Ctrl+V", null, () -> {}),
                        IdeaListPopup.Row.action("Select All 全选", null, "Ctrl+A", null, () -> {}),
                        IdeaListPopup.Row.action("Font and Color Settings 字体与配色", null, "", "", () -> {})
                ), 300, false, "", IdeaListPopup.Style.CONTEXT);
                popup.set(menu); menu.show(anchor, 0, anchor.getHeight());
            }).get(15, TimeUnit.SECONDS);
            Robot robot = new Robot(); robot.waitForIdle();
            Rectangle bounds = SwingExecutors.supplyOnEdtAsync(() -> {
                Window window = SwingUtilities.getWindowAncestor(popup.get());
                return new Rectangle(window.getLocationOnScreen(), window.getSize());
            }).get(10, TimeUnit.SECONDS);
            var directory = java.nio.file.Path.of("target", "ui-smoke");
            java.nio.file.Files.createDirectories(directory);
            javax.imageio.ImageIO.write(robot.createScreenCapture(bounds), "png", directory.resolve("windows-popup.png").toFile());
        } finally {
            SwingExecutors.runOnEdtAsync(() -> {
                if (popup.get() != null) popup.get().setVisible(false);
                if (owner.get() != null) owner.get().dispose();
            }).get(10, TimeUnit.SECONDS);
        }
    }
    private static Component descendant(Container c,Class<?> type){for(Component child:c.getComponents()){if(type.isInstance(child))return child;if(child instanceof Container container){Component found=descendant(container,type);if(found!=null)return found;}}return null;}
}
