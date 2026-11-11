package com.jlshell.app;

import com.jlshell.ui.support.UiThread;
import com.jlshell.ui.dialog.AboutDialog;
import com.jlshell.ui.dialog.ProjectManagerDialog;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.swing.SwingWorkbench;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import static com.jlshell.app.SettingsUiSmokeTest.Probe.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原窗口模型与新 Swing 外壳的集成验收：主题可读性、关闭恢复、项目保存和像素缩放。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class DialogUiSmokeTest {
    @TempDir Path temporary;
    @Test void preservesDialogActionsAndReadableThemes() throws Exception {
        Path log = temporary.resolve("dialogs.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED", "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED", "-Duser.home=" + temporary.resolve("home"),
                "-Djlshell.ui.preview=true", "-Djlshell.ui.renderer=swing", "-Djlshell.dialog.review=" +
                Path.of("..", "ui", "target", "review").toAbsolutePath(), "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("DIALOG_UI_READY"), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return e.toString(); } }
    public static class Probe {
        static CompletableFuture<Void> open(Runnable action) {
            var future = new CompletableFuture<Void>();
            com.jlshell.ui.support.UiThread.run(() -> { try { action.run(); future.complete(null); } catch(Throwable e) { future.completeExceptionally(e); } });
            return future;
        }
        static JDialog window(String name) throws Exception {
            for(int count=0;count<100;count++) {
                JDialog found=edt(() -> {
                    for(Window window:Window.getWindows()) if(window instanceof JDialog dialog && dialog.isShowing() && name.equals(dialog.getName()))return dialog;
                    return null;
                });
                if(found!=null) { Thread.sleep(300); return found; }
                Thread.sleep(50);
            }
            throw new AssertionError("Missing dialog: "+name);
        }



        static void capture(JDialog dialog,String name)throws Exception {
            Path directory=Path.of(System.getProperty("jlshell.dialog.review"));Files.createDirectories(directory);
            Thread.sleep(200);
            edt(()-> {
                JRootPane root=dialog.getRootPane();
                var image=new BufferedImage(root.getWidth()*2,root.getHeight()*2,BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();graphics.scale(2,2);root.printAll(graphics);graphics.dispose();
                try{javax.imageio.ImageIO.write(image,"png",directory.resolve(name+".png").toFile());}catch(Exception e){throw new RuntimeException(e);}
                return null;
            });
        }
        static void close(JDialog dialog,CompletableFuture<Void> closed)throws Exception {
            edt(()->{((JButton)named(dialog,"dialog.close")).doClick();return null;});
            closed.get(10,TimeUnit.SECONDS);
            assertFalse(edt(dialog::isShowing));
        }
        static void assertAboutComplete(JDialog dialog, String pageName) {
            dialog.validate();
            JScrollPane scroll = (JScrollPane) named(dialog, pageName);
            assertNotNull(scroll);
            assertFalse(scroll.getVerticalScrollBar().isVisible(), () -> "关于页出现纵向滚动条，viewport="
                    + scroll.getViewport().getExtentSize() + ", content=" + scroll.getViewport().getView().getPreferredSize());
            for (String name : java.util.List.of("about.resources", "about.details", "about.footer")) {
                Component component = named(scroll, name);
                assertNotNull(component, name);
                Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), scroll.getViewport().getView());
                assertTrue(scroll.getViewport().getViewRect().contains(bounds), name + " 必须完整显示: " + bounds);
            }
            assertEquals(4, ((Container) named(scroll, "about.resources")).getComponentCount());
            assertEquals(12, ((Container) named(scroll, "about.details")).getComponentCount());
        }
        public static void main(String[] args) {
            try{verify();System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(2);}
        }
        static void verify()throws Exception {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);

            AppContext context=fx(()->{var value=new AppContext();main=value.getMainWindow();owner=null;
                main.showSwingWindow(owner,()->{});return value;});
            @SuppressWarnings("unchecked") SwingWorkbench workbench=((CompletableFuture<SwingWorkbench>)field(main,"swingWorkbench")).get(10,TimeUnit.SECONDS);
            I18nService i18n=(I18nService)field(main,"i18nService");
            ThemeService themes=(ThemeService)field(main,"themeService");
            ConnectionProfileService profiles=(ConnectionProfileService)field(main,"connectionProfileService");
            VaultService vault=(VaultService)field(main,"vaultService");
            edt(()->{workbench.show();return null;});
            for(AppTheme theme:java.util.List.of(AppTheme.DARK,AppTheme.LIGHT)) {
                fx(()->{themes.setTheme(theme);return null;});Thread.sleep(200);
                var settingsClosed=open(()->main.openPreferences(owner));
                JDialog settings=window("jlshell.settings");
                assertNotNull(edt(()->named(settings,"dialog.header")),"Settings must have the themed dialog header");
                edt(()->{((JTextField)named(settings,"settings.search")).setText(i18n.get("preferences.tab.account"));return null;});Thread.sleep(300);
                edt(()->{NativeSettingsProbe.assertNative(settings);assertNotNull(named(settings,"account-status-badge"));
                    NativeSettingsProbe.button(settings,i18n.get("account.login.password.action")).doClick();return null;});
                JDialog login=window("jlshell.account");
                edt(()->{NativeSettingsProbe.assertNative(login);assertFalse(NativeSettingsProbe.all(login,JPasswordField.class).isEmpty());return null;});
                click(login,"dialog.cancel");fx(()->null);edt(()->null);
                assertTrue(edt(settings::isEnabled));
                capture(settings,"settings-account-"+theme.name().toLowerCase());
                edt(()->{((JButton)named(settings,"settings.cancel")).doClick();return null;});settingsClosed.get(10,TimeUnit.SECONDS);

                var checks=new java.util.concurrent.atomic.AtomicInteger();
                var aboutClosed=AboutDialog.show(owner,i18n,themes,checks::incrementAndGet);
                JDialog about=window("jlshell.about");
                assertFalse(edt(workbench.frame()::isEnabled));
                edt(()->{NativeSettingsProbe.assertNative(about);assertTrue(NativeSettingsProbe.all(about,JLabel.class).stream().anyMatch(label->"JLShell".equals(label.getText())&&label.getFont().getSize()>=30));assertAboutComplete(about,"dialog.content");return null;});
                capture(about,"about-"+theme.name().toLowerCase());
                if(theme==AppTheme.DARK) {
                    Dimension original = edt(about::getSize);
                    edt(()->{about.setSize(560,350);about.validate();return null;});
                    edt(()->{JScrollPane scroll=(JScrollPane)named(about,"dialog.content");
                        assertTrue(scroll.getVerticalScrollBar().isVisible(),"小窗口需要保留滚动以访问全部内容");
                        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                        Component footer=named(scroll,"about.footer");
                        Rectangle bounds=SwingUtilities.convertRectangle(footer.getParent(),footer.getBounds(),scroll.getViewport().getView());
                        assertTrue(scroll.getViewport().getViewRect().contains(bounds),"小窗口滚动后页脚应可见");return null;});
                    edt(()->{about.setSize(original);about.validate();((JScrollPane)named(about,"dialog.content")).getVerticalScrollBar().setValue(0);return null;});
                    edt(()->{NativeSettingsProbe.button(about,i18n.get("menu.help.checkUpdates")).doClick();return null;});
                    aboutClosed.get(10,TimeUnit.SECONDS);
                    assertEquals(1,checks.get(),"Original check-updates action must close its dialog and run once");
                } else close(about,aboutClosed);
                assertTrue(edt(workbench.frame()::isEnabled),"Closing standalone dialog must restore its host");

                var projectClosed=ProjectManagerDialog.show(owner,profiles,i18n,themes,vault,null,ignored->{});
                JDialog projects=window("jlshell.projects");
                edt(() -> { NativeSettingsProbe.assertNative(projects); return null; });
                if(theme==AppTheme.DARK) {
                    edt(() -> {
                        ((JButton) named(projects,"projects.new")).doClick();
                        ((JTextField) named(projects,"projects.name")).setText("UI 验收项目");
                        ((JButton) named(projects,"projects.save")).doClick();
                        return null;
                    });
                    for (int attempt = 0; attempt < 200 && profiles.listProjects().stream().noneMatch(project -> "UI 验收项目".equals(project.name())); attempt++) Thread.sleep(25);
                    assertTrue(profiles.listProjects().stream().anyMatch(project->"UI 验收项目".equals(project.name())),"Project save must reach encrypted persistence");
                }
                capture(projects,"projects-"+theme.name().toLowerCase());close(projects,projectClosed);
            }
            edt(()->{workbench.close();return null;});fx(()->{context.close();return null;});
            System.out.println("DIALOG_UI_READY: readable badges, compact dialogs, native scale, original project save and close verified");
        }
    }
}
