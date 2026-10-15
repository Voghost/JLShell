package com.jlshell.app;

import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.ui.dialog.VaultManagerDialog;
import com.jlshell.ui.model.*;
import com.jlshell.ui.service.*;
import com.jlshell.ui.swing.SwingAlerts;
import com.jlshell.ui.theme.*;
import com.jlshell.ssh.support.HostKeyConfirmationCallback.TrustDecision;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.jlshell.app.SettingsUiSmokeTest.Probe.*;
import static com.jlshell.app.DialogUiSmokeTest.Probe.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native message ownership, keyboard dismissal, trust decisions and real Swing credential edits. */
@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class NativeDialogsUiSmokeTest {
    @TempDir Path temporary;
    @Test void nativeDialogsPreserveTrustAndCredentialActions() throws Exception {
        Path log=temporary.resolve("native-dialogs.log");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED", "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED", "-Duser.home="+temporary.resolve("home"),
                "-Djlshell.ui.preview=true", "-Djlshell.ui.renderer=swing", "-Djlshell.dialog.review="+
                Path.of("..","ui","target","review").toAbsolutePath(), "-cp",System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(80,TimeUnit.SECONDS),()->DialogUiSmokeTest.read(log));
            assertEquals(0,process.exitValue(),()->DialogUiSmokeTest.read(log));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }

    public static class Probe {
        static boolean hasFxPanel(Component component) {
            if(component instanceof javafx.embed.swing.JFXPanel)return true;
            return component instanceof Container parent && Arrays.stream(parent.getComponents()).anyMatch(Probe::hasFxPanel);
        }
        static void click(JDialog dialog,String name)throws Exception {
            edt(()->{((AbstractButton)named(dialog,name)).doClick();return null;}); fx(()->null);edt(()->null);
        }
        public static void main(String[] args) {
            try { verify();System.out.println("NATIVE_DIALOGS_READY");System.exit(0); }
            catch(Throwable error){error.printStackTrace();System.exit(2);}
        }
        static void verify()throws Exception {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);java.awt.Toolkit.getDefaultToolkit();Platform.startup(()->Platform.setImplicitExit(false));
            AppContext context=fx(AppContext::new);
            I18nService i18n=(I18nService)field(context.getMainWindow(),"i18nService");
            ThemeService themes=(ThemeService)field(context.getMainWindow(),"themeService");
            Stage owner=fx(Stage::new);
            for(AppTheme theme:List.of(AppTheme.DARK,AppTheme.LIGHT)) {
                fx(()->{themes.setTheme(theme);return null;});
                var shown=open(()->{
                    Alert alert=new Alert(Alert.AlertType.ERROR,"连接失败：用于界面验收的测试信息，未访问远程主机。",ButtonType.OK);
                    alert.setTitle("连接失败");alert.setHeaderText(null);SwingAlerts.showAndWait(alert,themes,i18n);
                });
                JDialog error=window("jlshell.message.error");assertFalse(edt(()->hasFxPanel(error)));
                int shortHeight=edt(error::getHeight);
                assertTrue(shortHeight<240,"Short errors should not retain the old 290px height");
                capture(error,"native-error-"+theme.name().toLowerCase());click(error,"dialog.ok");shown.get(5,TimeUnit.SECONDS);
                HostKeyConfirmationService service=new HostKeyConfirmationService(i18n,themes);
                var once=CompletableFuture.supplyAsync(()->service.confirmDecision("fixture.example",2222,"ssh-ed25519","SHA256:fixture-for-ui-only",false));
                JDialog trust=window("jlshell.message.confirmation");assertFalse(edt(()->hasFxPanel(trust)));
                assertTrue(edt(trust::getHeight)>shortHeight,"Trust details should increase the natural height");
                assertTrue(edt(trust::getHeight)<260,"Trust messages should remain compact");
                capture(trust,"native-hostkey-"+theme.name().toLowerCase());click(trust,"hostkey.once");
                assertEquals(TrustDecision.TRUST_ONCE,once.get(5,TimeUnit.SECONDS));
                var save=CompletableFuture.supplyAsync(()->service.confirmDecision("fixture.example",2222,"ssh-ed25519","SHA256:fixture-for-ui-only",true));
                JDialog changedKey=window("jlshell.message.warning");click(changedKey,"hostkey.save");assertEquals(TrustDecision.TRUST_AND_SAVE,save.get(5,TimeUnit.SECONDS));
                var cancel=CompletableFuture.supplyAsync(()->service.confirmDecision("fixture.example",2222,"ssh-ed25519","SHA256:fixture-for-ui-only",false));
                JDialog rejected=window("jlshell.message.confirmation");click(rejected,"dialog.cancel");assertEquals(TrustDecision.REJECT,cancel.get(5,TimeUnit.SECONDS));
            }
            var longMessage=open(()->{
                Alert alert=new Alert(Alert.AlertType.ERROR,
                        "连接失败：这是需要自动换行的较长错误详情，用于确认正文不会截断。".repeat(8)+"\n"+
                        "详细诊断信息：测试堆栈行，仅用于界面测试。\n".repeat(100),ButtonType.OK);
                alert.setTitle("长错误信息");alert.setHeaderText(null);SwingAlerts.showAndWait(alert,themes,i18n);
            });
            JDialog longError=window("jlshell.message.error");
            edt(()->{
                JScrollPane scroll=(JScrollPane)named(longError,"dialog.messageScroll");
                assertTrue(scroll.getVerticalScrollBar().isVisible(),"Long messages must scroll");
                assertTrue(scroll.getViewport().getView().getPreferredSize().height>scroll.getViewport().getExtentSize().height);
                assertTrue(longError.getHeight()<longError.getGraphicsConfiguration().getBounds().height);
                assertTrue(named(longError,"dialog.ok").isShowing());
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                return null;
            });
            capture(longError,"native-error-long-light");click(longError,"dialog.ok");longMessage.get(5,TimeUnit.SECONDS);
            VaultKeyService keys=new VaultKeyService(null) {
                public boolean isUnlocked(){return false;}
                public boolean isCustomKeyConfigured(){return false;}
            };
            Map<String,VaultEntryFormData> entries=new LinkedHashMap<>();
            VaultService vault=new VaultService(null,null,keys) {
                public List<VaultEntryProfile> listByProject(String project){return entries.values().stream().map(f->new VaultEntryProfile(f.id(),f.name(),f.authenticationType(),f.encryptionMode(),f.projectId(),f.privateKeyPath())).toList();}
                public VaultEntryFormData loadForm(String id){return entries.get(id);}
                public VaultEntryProfile save(VaultEntryFormData f){
                    String id=f.id()==null?"fixture":f.id();
                    var stored=new VaultEntryFormData(id,f.name(),f.authenticationType(),f.encryptionMode(),f.password(),f.passphrase(),f.keyContent(),f.privateKeyPath(),f.projectId());entries.put(id,stored);
                    return listByProject(null).getFirst();
                }
                public void delete(String id){entries.remove(id);}
            };
            for(AppTheme theme:List.of(AppTheme.DARK,AppTheme.LIGHT)) {
                fx(()->{themes.setTheme(theme);return null;});
                var shown=open(()->VaultManagerDialog.show(owner,vault,i18n,themes,null,List.of(),null));
                shown.whenComplete((v,error)->{if(error!=null)error.printStackTrace();});
                JDialog manager=window("jlshell.vault");assertFalse(edt(()->hasFxPanel(manager)));
                edt(()->{((JTextField)named(manager,"vault-name")).setText("演示凭据");((JPasswordField)named(manager,"vault-password")).setText("fixture-only");return null;});
                fx(()->null);click(manager,"vault-save");
                assertEquals("fixture-only",entries.get("fixture").password());
                capture(manager,"native-vault-"+theme.name().toLowerCase());
                edt(()->{((JComboBox<?>)named(manager,"vault-auth")).setSelectedItem(AuthenticationType.PRIVATE_KEY);return null;});fx(()->null);edt(()->null);
                assertFalse(edt(()->named(manager,"vault-password").isShowing()));
                capture(manager,"native-vault-key-"+theme.name().toLowerCase());
                click(manager,"dialog.close");shown.get(5,TimeUnit.SECONDS);
            }
            context.close();
        }
    }
}
