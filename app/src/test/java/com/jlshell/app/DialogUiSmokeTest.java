package com.jlshell.app;

import com.jlshell.ui.dialog.AboutDialog;
import com.jlshell.ui.dialog.ProjectManagerDialog;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.swing.SwingFxPanel;
import com.jlshell.ui.swing.SwingWorkbench;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
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
            Platform.runLater(() -> { try { action.run(); future.complete(null); } catch(Throwable e) { future.completeExceptionally(e); } });
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
        static SwingFxPanel panel(JDialog dialog,String name)throws Exception {
            SwingFxPanel panel=edt(()->(SwingFxPanel)named(dialog,name));
            fx(()->{panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();return null;});
            return panel;
        }
        static void readable(SwingFxPanel panel,String selector)throws Exception {
            fx(()-> {
                Label label=(Label)panel.getScene().getRoot().lookup(selector);
                assertNotNull(label); assertFalse(label.getText().isBlank());
                Color foreground=(Color)label.getTextFill();
                Color background=(Color)label.getBackground().getFills().getFirst().getFill();
                assertTrue(Math.abs(brightness(foreground)-brightness(background))>0.35,
                        selector+" must not paint its text with the badge background color");
                assertTrue(label.getWidth()<450,"Status badges must size to their text");
                return null;
            });
        }
        static double brightness(Color c) { return .2126*c.getRed()+.7152*c.getGreen()+.0722*c.getBlue(); }
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
        public static void main(String[] args) {
            try{verify();System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(2);}
        }
        static void verify()throws Exception {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            Platform.startup(()->Platform.setImplicitExit(false));
            AppContext context=fx(()->{var value=new AppContext();main=value.getMainWindow();owner=new Stage();
                owner.setScene(main.createScene(owner));main.showSwingWindow(owner,()->{});return value;});
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
                SwingFxPanel settingsPanel=panel(settings,"settings.content");
                readable(settingsPanel,".account-status-badge");
                capture(settings,"settings-account-"+theme.name().toLowerCase());
                edt(()->{((JButton)named(settings,"settings.cancel")).doClick();return null;});settingsClosed.get(10,TimeUnit.SECONDS);

                var checks=new java.util.concurrent.atomic.AtomicInteger();
                var aboutClosed=open(()->AboutDialog.show(owner,i18n,themes,checks::incrementAndGet));
                JDialog about=window("jlshell.about");
                assertFalse(edt(workbench.frame()::isEnabled));
                SwingFxPanel aboutPanel=panel(about,"dialog.content");
                readable(aboutPanel,".about-version-badge");
                double expected=edt(()->aboutPanel.getGraphicsConfiguration().getDefaultTransform().getScaleX());
                double actual=edt(()->{try{var field=javafx.embed.swing.JFXPanel.class.getDeclaredField("scaleFactorX");field.setAccessible(true);return field.getDouble(aboutPanel);}catch(Exception e){throw new RuntimeException(e);}});
                assertEquals(expected,actual,.01,"Embedded forms must render at the native display scale");
                capture(about,"about-"+theme.name().toLowerCase());
                if(theme==AppTheme.DARK) {
                    fx(()->{aboutPanel.getScene().getRoot().lookupAll(".button").stream().filter(Button.class::isInstance)
                            .map(Button.class::cast).filter(button->i18n.get("menu.help.checkUpdates").equals(button.getText()))
                            .findFirst().orElseThrow().fire();return null;});
                    aboutClosed.get(10,TimeUnit.SECONDS);
                    assertEquals(1,checks.get(),"Original check-updates action must close its dialog and run once");
                } else close(about,aboutClosed);
                assertTrue(edt(workbench.frame()::isEnabled),"Closing standalone dialog must restore its host");

                var projectClosed=open(()->ProjectManagerDialog.show(owner,profiles,i18n,themes,vault,null,ignored->{}));
                JDialog projects=window("jlshell.projects");
                SwingFxPanel projectPanel=panel(projects,"dialog.content");
                fx(()->{
                    @SuppressWarnings("unchecked") ListView<ProjectProfile> list=(ListView<ProjectProfile>)projectPanel.getScene().getRoot().lookup(".project-manager-list");
                    Color background=(Color)list.getBackground().getFills().getFirst().getFill();
                    Label name=(Label)projectPanel.getScene().getRoot().lookup(".project-manager-item-name");
                    assertTrue(Math.abs(brightness((Color)name.getTextFill())-brightness(background))>.35);
                    return null;
                });
                if(theme==AppTheme.DARK) fx(()->{
                    var root=projectPanel.getScene().getRoot();
                    root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button->i18n.get("project.action.new").equals(button.getText())).findFirst().orElseThrow().fire();
                    ((TextField)root.lookup(".project-manager-input")).setText("UI 验收项目");
                    root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button->i18n.get("project.action.save").equals(button.getText())).findFirst().orElseThrow().fire();
                    assertTrue(profiles.listProjects().stream().anyMatch(project->"UI 验收项目".equals(project.name())),"Original save handler must remain connected");
                    return null;
                });
                capture(projects,"projects-"+theme.name().toLowerCase());close(projects,projectClosed);
            }
            edt(()->{workbench.close();return null;});fx(()->{context.close();return null;});
            System.out.println("DIALOG_UI_READY: readable badges, compact dialogs, native scale, original project save and close verified");
        }
    }
}
