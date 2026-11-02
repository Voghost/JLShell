package com.jlshell.ui.swing;

import com.jlshell.core.support.RecentApplicationLogs;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke",matches="true")
class SwingStatusBarTest {
    @Test void retainsHeightAndShowsSimpleActivitiesInRoundedPopups() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            for (var theme : AppTheme.values()) {
                SwingTheme.install(theme,"#3574f0",null,13);
                var logs=new RecentApplicationLogs();
                var i18n = new com.jlshell.ui.service.I18nService(java.util.Locale.SIMPLIFIED_CHINESE);
                var bar=new SwingStatusBar(i18n::get,logs);
                var frame=new JFrame();
                try {
                    JLabel old=new JLabel(" "); old.setBorder(BorderFactory.createEmptyBorder(3,12,3,12));
                    assertEquals(old.getPreferredSize().height,bar.getPreferredSize().height);
                    logs.append(Instant.parse("2026-11-01T15:48:12Z"),"INFO","Workbench","已连接到开发服务器");
                    bar.sources(()->new SwingStatusBar.SessionInfo("test","user@example.invalid:22",false,"connected",Instant.now().minusSeconds(123),"UTF-8 · 80 × 24","UTF-8"),()->List.of("upload.zip · 31 / 50 MB"));
                    frame.add(new JPanel()); frame.add(bar,BorderLayout.SOUTH);frame.setSize(900,500);frame.setVisible(true);frame.validate();bar.refresh();
                    int height=bar.getHeight();assertEquals(old.getPreferredSize().height,height);
                    JButton session=find(bar,"status.session");assertTrue(session.getText().contains("SSH"));
                    assertTrue(find(bar,"status.transfers").isVisible());
                    find(bar,"status.logs").doClick();
                    var popup = java.util.Arrays.stream(Window.getWindows()).filter(w -> w.isShowing() && "idea.contentPopup".equals(w.getName())).findFirst().orElseThrow();
                    JList<?> activities=findList(popup); assertEquals(1, activities.getModel().getSize());
                    var entry=(RecentApplicationLogs.Entry)activities.getModel().getElementAt(0);
                    assertEquals("已连接到开发服务器",entry.message());
                    assertFalse(find(bar,"status.logs").getText().contains("INFO"));
                    assertNotNull(findNamed(popup,"idea.popupSurface"));
                    assertEquals(height,bar.getHeight());assertTrue(popup.getLocationOnScreen().y<bar.getLocationOnScreen().y);
                    try {
                        var pane=frame.getRootPane();
                        var image=new java.awt.image.BufferedImage(pane.getWidth(),pane.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var g=image.createGraphics();try{pane.printAll(g);}finally{g.dispose();}
                        var expanded=new java.awt.image.BufferedImage(popup.getWidth(),popup.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var pg=expanded.createGraphics();try{popup.printAll(pg);}finally{pg.dispose();}
                        var expandedPath=java.nio.file.Path.of("target/review/statusbar-logs-"+theme.name().toLowerCase()+".png");
                        java.nio.file.Files.createDirectories(expandedPath.getParent());javax.imageio.ImageIO.write(expanded,"png",expandedPath.toFile());
                        var path=java.nio.file.Path.of("target/review/statusbar-"+theme.name().toLowerCase()+".png");
                        java.nio.file.Files.createDirectories(path.getParent());javax.imageio.ImageIO.write(image,"png",path.toFile());
                    } catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
                    find(bar,"status.logs").requestFocusInWindow();
                    session.doClick();assertEquals(height,bar.getHeight());
                    bar.sources(()->null,List::of);
                    assertFalse(session.isEnabled()); assertFalse(find(bar,"status.transfers").isVisible());
                    assertFalse(find(bar,"status.encoding").isVisible());
                    assertEquals(height,bar.getPreferredSize().height);
                } finally {bar.close();frame.dispose();}
            }
        }).get(15,TimeUnit.SECONDS);
    }
    private static JButton find(Container root,String name){for(Component c:root.getComponents()){if(name.equals(c.getName()))return (JButton)c;if(c instanceof Container parent){JButton found=find(parent,name);if(found!=null)return found;}}return null;}
    private static JList<?> findList(Container root){for(Component c:root.getComponents()){if(c instanceof JList<?> list)return list;if(c instanceof Container parent){var found=findList(parent);if(found!=null)return found;}}return null;}
    private static Component findNamed(Container root,String name){for(Component c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container parent){var found=findNamed(parent,name);if(found!=null)return found;}}return null;}
}
