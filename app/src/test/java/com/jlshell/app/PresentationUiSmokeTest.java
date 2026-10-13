package com.jlshell.app;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.data.entity.AuthenticationType;
import com.jlshell.data.entity.VaultEncryptionMode;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.ui.model.*;
import com.jlshell.ui.service.*;
import com.jlshell.ui.service.update.UpdateService;
import com.jlshell.ui.swing.*;
import com.jlshell.ui.theme.*;
import com.jlshell.ui.view.ConnectionDialog;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import static com.jlshell.app.SettingsUiSmokeTest.Probe.*;
import static com.jlshell.app.DialogUiSmokeTest.Probe.*;
import static org.junit.jupiter.api.Assertions.*;

/** 独立配置、回环商店与原有插件 JAR：连接结果、按钮绑定、插件设置回调的呈现层集成。 */
@EnabledIfSystemProperty(named="jlshell.ui.smoke",matches="true")
class PresentationUiSmokeTest {
    @TempDir Path temporary;
    @Test void preservesConnectionResultsAndExistingPlugins() throws Exception {
        Path log=temporary.resolve("presentation.log");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED", "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED", "-Duser.home="+temporary.resolve("home"),
                "-Djlshell.ui.preview=true", "-Djlshell.ui.renderer=swing", "-Djlshell.dialog.review="+
                Path.of("..","ui","target","review").toAbsolutePath(),
                "-Djlshell.ui.realMouse="+Boolean.getBoolean("jlshell.ui.realMouse"),"-cp",System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(85,TimeUnit.SECONDS),()->DialogUiSmokeTest.read(log));
            assertEquals(0,process.exitValue(),()->DialogUiSmokeTest.read(log));
            assertTrue(DialogUiSmokeTest.read(log).contains("PRESENTATION_UI_READY"),()->DialogUiSmokeTest.read(log));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
    public static class Probe {
        static final String SESSION_ID="com.jlshell.demo.script-snippets";
        static final String PROGRAM_ID="com.jlshell.demo.program-host-tools";
        static java.awt.Robot robot;
        static void originalPluginJar(String module,String source,String directory) throws Exception {
            Path root=Path.of("..","plugins",module), classes=Files.createTempDirectory("jlshell-plugin-fixture");
            try {
                int compiled=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",
                        System.getProperty("java.class.path"),"-d",classes.toString(),root.resolve("src/main/java/"+source).toString());
                assertEquals(0,compiled,"Unmodified plugin source must compile against the same SDK");
                Path resources=root.resolve("src/main/resources");
                try(var paths=Files.walk(resources)) {for(Path path:paths.filter(Files::isRegularFile).toList()) {
                    Path target=classes.resolve(resources.relativize(path));Files.createDirectories(target.getParent());Files.copy(path,target);
                }}
                Path jar=Path.of(System.getProperty("user.home"),".jlshell",directory,module+".jar");Files.createDirectories(jar.getParent());
                var descriptor=com.google.gson.JsonParser.parseString(Files.readString(resources.resolve("META-INF/jlshell-plugin.json"))).getAsJsonObject();
                Manifest manifest=new Manifest();var attributes=manifest.getMainAttributes();attributes.putValue("Manifest-Version","1.0");
                attributes.putValue(com.jlshell.plugin.loader.store.PluginInstaller.MANIFEST_PLUGIN_ID,descriptor.get("id").getAsString());
                attributes.putValue(com.jlshell.plugin.loader.store.PluginInstaller.MANIFEST_PLUGIN_VERSION,descriptor.get("version").getAsString());
                attributes.putValue(com.jlshell.plugin.loader.store.PluginInstaller.MANIFEST_PLUGIN_SCOPE,descriptor.get("scope").getAsString());
                try(var out=new JarOutputStream(Files.newOutputStream(jar),manifest);var paths=Files.walk(classes)) {
                    for(Path path:paths.filter(Files::isRegularFile).toList()) {
                        out.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));
                        Files.copy(path,out);out.closeEntry();
                    }
                }
            } finally {try(var paths=Files.walk(classes)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        }
        static HttpServer store() throws Exception {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            String listing="""
                    {"pluginId":"com.jlshell.fixture.monitor","scope":"SESSION","listingStatus":"APPROVED",
                     "displayName":"系统监控","description":"实时 CPU、内存、网络和磁盘监控，支持趋势图表。",
                     "author":"JLShell","latestVersion":"0.1.0","minHostVersion":"0.1.0",
                     "maxHostVersion":"0.1.999","downloads":6}
                    """;
            server.createContext("/api/v1/plugins",exchange->{
                String body=exchange.getRequestURI().getPath().equals("/api/v1/plugins")
                        ? "{\"content\":["+listing+"],\"number\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}"
                        : "{\"plugin\":"+listing+",\"versions\":[{\"version\":\"0.1.0\",\"status\":\"APPROVED\",\"size\":4718592,\"releaseNotes\":\"改进监控显示和趋势图表。\"}]}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();return server;
        }
        static CompletableFuture<Optional<ConnectionFormData>> form(I18nService i18n,ThemeService themes,
                ConnectionFormData initial,List<ProjectProfile> projects,VaultService vault,
                ConnectionDialog.ConnectionTester tester) {
            var result=new CompletableFuture<Optional<ConnectionFormData>>();Platform.runLater(()->{
                try{result.complete(ConnectionDialog.show(owner,i18n,themes,initial,projects,List.of(),vault,tester,10));}
                catch(Throwable error){result.completeExceptionally(error);}
            });return result;
        }
        static void click(JDialog dialog,String id)throws Exception {
            if (Boolean.getBoolean("jlshell.ui.realMouse")) {
                java.awt.Point point=edt(()->{
                    var button=(AbstractButton)named(dialog,id);var location=button.getLocationOnScreen();
                    location.translate(button.getWidth()/2,button.getHeight()/2);return location;
                });mouse(point);
            } else edt(()->{((AbstractButton)named(dialog,id)).doClick();return null;});
            fx(()->null);edt(()->null);
        }
        static void mouse(java.awt.Point point)throws Exception {
            if(robot==null){robot=new java.awt.Robot();robot.setAutoDelay(70);}
            robot.mouseMove(point.x,point.y);robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);robot.waitForIdle();Thread.sleep(250);
        }
        static void clickFx(SwingFxPanel panel,Button button)throws Exception {
            if(Boolean.getBoolean("jlshell.ui.realMouse")) {
                var bounds=fx(()->button.localToScene(button.getBoundsInLocal()));
                var origin=edt(panel::getLocationOnScreen);
                mouse(new java.awt.Point(origin.x+(int)((bounds.getMinX()+bounds.getMaxX())/2),
                        origin.y+(int)((bounds.getMinY()+bounds.getMaxY())/2)));
            } else fx(()->{button.fire();return null;});
            fx(()->null);edt(()->null);
        }
        @SuppressWarnings("unchecked") static <T> ComboBox<T> box(SwingFxPanel panel,String id) {
            return (ComboBox<T>)panel.getScene().getRoot().lookup("#connection-"+id);
        }
        static TextField input(SwingFxPanel panel,String id) {return (TextField)panel.getScene().getRoot().lookup("#connection-"+id);}
        static void connection(I18nService i18n,ThemeService themes,List<ProjectProfile> projects)throws Exception {
            String projectId=projects.getFirst().id();
            var profile=new VaultEntryProfile("fixture-vault","验收凭据",AuthenticationType.PASSWORD,VaultEncryptionMode.SYSTEM,projectId,null);
            VaultService vault=new VaultService(null,null,null) {
                @Override public List<VaultEntryProfile> listByProject(String id){return List.of(profile);}
                @Override public VaultEntryFormData loadForm(String id){return new VaultEntryFormData(id,profile.name(),
                        AuthenticationType.PASSWORD,VaultEncryptionMode.SYSTEM,"fixture-password","","",null,projectId);}
                @Override public com.jlshell.core.security.CredentialPayload toCredentialPayload(String id) {
                    return com.jlshell.core.security.CredentialPayload.forPassword("fixture-password".toCharArray());
                }
            };
            var tested=new AtomicReference<ConnectionDialog.ConnectionTestContext>();
            ConnectionDialog.ConnectionTester tester=value->{tested.set(value);return CompletableFuture.completedFuture("UI fixture OK");};
            ConnectionFormData accepted=null;
            for(AppTheme theme:List.of(AppTheme.DARK,AppTheme.LIGHT)) {
                fx(()->{themes.setTheme(theme);return null;});
                var result=form(i18n,themes,ConnectionFormData.empty(projectId),projects,vault,tester);
                JDialog dialog=window("jlshell.connection");SwingFxPanel panel=panel(dialog,"dialog.content");
                assertNotNull(edt(()->named(dialog,"dialog.header")));
                assertFalse(edt(()->((JButton)named(dialog,"dialog.ok")).isEnabled()),"Original required-field validation must disable native OK");
                fx(()->{
                    input(panel,"name").setText("验收连接");input(panel,"host").setText("ui.example.invalid");input(panel,"user").setText("fixture");
                    box(panel,"vault").setValue(profile);
                    assertEquals("fixture-password",input(panel,"password").getText());
                    assertTrue(input(panel,"password").isDisabled());assertTrue(box(panel,"auth").isDisabled());
                    box(panel,"vault").getSelectionModel().select(0);
                    assertFalse(input(panel,"password").isDisabled());assertFalse(box(panel,"auth").isDisabled());
                    box(panel,"auth").setValue(AuthenticationType.PRIVATE_KEY);
                    assertTrue(input(panel,"password").isDisabled());assertFalse(input(panel,"key-path").isDisabled());
                    box(panel,"vault").setValue(profile);
                    panel.getScene().getRoot().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button->i18n.get("action.testConnection").equals(button.getText())).findFirst().orElseThrow().fire();return null;
                });
                edt(()->null);assertTrue(edt(()->((JButton)named(dialog,"dialog.ok")).isEnabled()));
                assertNotNull(tested.get());assertEquals("ui.example.invalid",tested.get().request().target().host());
                capture(dialog,"connection-"+theme.name().toLowerCase());
                click(dialog,"dialog.ok");accepted=result.get(10,TimeUnit.SECONDS).orElseThrow();
                assertEquals("fixture-vault",accepted.vaultEntryId());assertEquals("fixture-password",accepted.password());
                assertEquals("验收连接",accepted.displayName());assertEquals(projectId,accepted.projectId());
            }
            // 编辑无操作必须返回原数据，取消则没有数据；本地终端字段退出布局。
            ConnectionFormData initial=new ConnectionFormData("fixture-id",accepted.displayName(),accepted.host(),accepted.port(),
                    accepted.username(),accepted.authenticationType(),accepted.password(),accepted.privateKeyPath(),accepted.passphrase(),
                    accepted.hostKeyVerificationMode(),accepted.description(),accepted.defaultRemotePath(),accepted.favorite(),projectId,
                    accepted.connectionType(),accepted.folderId(),accepted.vaultEntryId(),accepted.keyContent());
            var edit=form(i18n,themes,initial,projects,vault,tester);JDialog dialog=window("jlshell.connection");
            click(dialog,"dialog.ok");assertEquals(initial,edit.get(10,TimeUnit.SECONDS).orElseThrow());
            var cancel=form(i18n,themes,ConnectionFormData.empty(projectId),projects,vault,tester);dialog=window("jlshell.connection");
            SwingFxPanel panel=panel(dialog,"dialog.content");
            fx(()->{box(panel,"type").setValue(ConnectionType.LOCAL_SHELL);assertFalse(input(panel,"host").isManaged());return null;});
            click(dialog,"dialog.cancel");assertTrue(cancel.get(10,TimeUnit.SECONDS).isEmpty());
        }
        static void plugins(I18nService i18n,ThemeService themes,PluginManager sessions,ProgramPluginManager programs)throws Exception {
            assertTrue(sessions.getInstalledPlugins().stream().anyMatch(entry->SESSION_ID.equals(entry.id())));
            assertTrue(programs.getInstalledPlugins().stream().anyMatch(entry->PROGRAM_ID.equals(entry.id())));
            fx(()->{sessions.setPluginEnabled(SESSION_ID,false);return null;});
            for(AppTheme theme:List.of(AppTheme.DARK,AppTheme.LIGHT)) {
                fx(()->{themes.setTheme(theme);return null;});var closed=open(()->main.openPreferences(owner));
                JDialog dialog=window("jlshell.settings");
                edt(()->{((JTextField)named(dialog,"settings.search")).setText(i18n.get("preferences.tab.plugins"));return null;});Thread.sleep(350);
                SwingFxPanel panel=panel(dialog,"settings.content");
                for(int attempt=0;attempt<100;attempt++) {
                    if(fx(()->panel.getScene().getRoot().lookup(".plugin-detail-title")!=null))break;Thread.sleep(50);
                }
                assertNotNull(edt(()->named(dialog,"settings.pluginTab.0")));
                fx(()->{
                    var root=panel.getScene().getRoot();assertNotNull(root.lookup(".plugin-detail-title"));
                    assertTrue(root.lookup(".plugin-detail-tabs") instanceof TabPane);
                    assertEquals(0,root.lookup(".plugin-external-tabs > .tab-header-area").getBoundsInParent().getHeight(),.01);
                    return null;
                });capture(dialog,"plugins-marketplace-"+theme.name().toLowerCase());
                click(dialog,"settings.pluginTab.1");Thread.sleep(200);
                fx(()->{
                    var root=panel.getScene().getRoot();
                    @SuppressWarnings("unchecked") ListView<Object> list=(ListView<Object>)root.lookup(".plugin-manager-list");
                    assertEquals(2,list.getItems().size());list.getSelectionModel().select(1);return null;
                });Thread.sleep(200);
                readable(panel,".plugin-state-badge");capture(dialog,"plugins-installed-"+theme.name().toLowerCase());
                if(theme==AppTheme.DARK) {
                    Button enable=fx(()->panel.getScene().getRoot().lookupAll(".button").stream()
                            .filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button->i18n.get("plugins.store.enable").equals(button.getText())).findFirst().orElseThrow());
                    clickFx(panel,enable);
                    assertTrue(sessions.isPluginEnabled(SESSION_ID),"Original enable callback must still reach the unchanged plugin manager");
                }
                fx(()->{
                    @SuppressWarnings("unchecked") ListView<Object> list=(ListView<Object>)panel.getScene().getRoot().lookup(".plugin-manager-list");
                    list.getSelectionModel().select(0);return null;
                });Thread.sleep(100);
                Button save=fx(()->{
                    var root=panel.getScene().getRoot();TextArea note=(TextArea)root.lookupAll(".text-area").stream().findFirst().orElseThrow();
                    note.setText("UI 插件设置验收");return root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button->"Save note".equals(button.getText())).findFirst().orElseThrow();
                });
                clickFx(panel,save);
                assertEquals("UI 插件设置验收",programs.getInstalledPlugins().getFirst().context().storage().get("demo.note",""));
                click(dialog,"settings.cancel");closed.get(10,TimeUnit.SECONDS);
            }
        }
        public static void main(String[] args) {try{verify();System.exit(0);}catch(Throwable error){error.printStackTrace();System.exit(2);}}
        static void verify()throws Exception {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            originalPluginJar("plugin-demo","com/jlshell/demo/ScriptSnippetsPlugin.java","plugins");
            originalPluginJar("plugin-program-demo","com/jlshell/demo/program/HostToolsProgramPlugin.java","program-plugins");
            HttpServer server=store();
            try {
                Platform.startup(()->Platform.setImplicitExit(false));
                AppContext context=fx(()->{var value=new AppContext();main=value.getMainWindow();owner=new Stage();owner.setScene(main.createScene(owner));
                    main.showSwingWindow(owner,()->{});return value;});
                @SuppressWarnings("unchecked") SwingWorkbench workbench=((CompletableFuture<SwingWorkbench>)field(main,"swingWorkbench")).get(10,TimeUnit.SECONDS);
                edt(()->{workbench.show();return null;});
                if(Boolean.getBoolean("jlshell.ui.realMouse")) {
                    for(int attempt=0;attempt<12 && !edt(workbench.frame()::isActive);attempt++) {
                        edt(()->{java.awt.Desktop.getDesktop().requestForeground(true);
                            workbench.frame().toFront();workbench.frame().requestFocus();return null;});Thread.sleep(300);
                    }
                    assertTrue(edt(workbench.frame()::isActive),"Physical clicks require the foreground fixture, not the user's other apps");
                }
                I18nService i18n=(I18nService)field(main,"i18nService");ThemeService themes=(ThemeService)field(main,"themeService");
                ((AppSettingsService)field(main,"appSettingsService")).set(UpdateService.SETTINGS_BASE_URL,"http://127.0.0.1:"+server.getAddress().getPort());
                connection(i18n,themes,List.of(new ProjectProfile("ui-project","验收项目","")));
                plugins(i18n,themes,(PluginManager)field(main,"pluginManager"),(ProgramPluginManager)field(main,"programPluginManager"));
                edt(()->{workbench.close();return null;});fx(()->{context.close();return null;});
                System.out.println("PRESENTATION_UI_READY: typed connection results, vault binding, original tester, existing JAR loading and plugin settings callback verified");
            } finally{server.stop(0);}
        }
    }
}
