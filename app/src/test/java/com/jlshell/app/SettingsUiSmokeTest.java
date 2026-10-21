package com.jlshell.app;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.swing.SwingFxPanel;
import com.jlshell.ui.swing.SwingWorkbench;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import com.jlshell.ui.view.MainWindow;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** 独立数据库中的真实设置保存与菜单导航；不读取用户配置或建立 SSH 连接。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SettingsUiSmokeTest {
    @TempDir Path temporary;

    @Test void preservesApplyCancelAndOkWithSearchAndMenuNavigation() throws Exception {
        Path log = temporary.resolve("settings.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                "-Duser.home=" + temporary.resolve("home"), "-Djlshell.ui.preview=true",
                "-Djlshell.ui.renderer=swing", "-Djlshell.dialog.review="+Path.of("..","ui","target","review").toAbsolutePath(), "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("SETTINGS_UI_READY"), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }

    public static class Probe {
        static MainWindow main;
        static Stage owner;
        static <T> T fx(Supplier<T> action) throws Exception {
            return FxThread.supplyAsync(action).get(10, TimeUnit.SECONDS);
        }
        static <T> T edt(Supplier<T> action) throws Exception {
            var result = new CompletableFuture<T>();
            SwingUtilities.invokeLater(() -> {
                try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result.get(10, TimeUnit.SECONDS);
        }
        static Object field(Object object, String name) throws Exception {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        }
        static Component named(Component component, String name) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container parent) for (Component child : parent.getComponents()) {
                Component result = named(child, name);
                if (result != null) return result;
            }
            return null;
        }
        static JDialog settings() throws Exception {
            for (int attempt = 0; attempt < 100; attempt++) {
                JDialog result = edt(() -> {
                    for (Window window : Window.getWindows()) if (window instanceof JDialog dialog
                            && dialog.isShowing() && "jlshell.settings".equals(dialog.getName())) return dialog;
                    return null;
                });
                if (result != null) return result;
                Thread.sleep(50);
            }
            throw new AssertionError("Settings window did not open");
        }
        static CompletableFuture<Void> open() {
            var result = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try { main.openPreferences(owner); result.complete(null); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
            return result;
        }
        static void awaitPage(JDialog dialog, String query) throws Exception {
            int index = switch (query) {
                case "通用" -> 0; case "账号" -> 1; case "终端" -> 2; case "导入" -> 3;
                case "API" -> 4; case "插件" -> 5; case "快捷键" -> 6; case "关于" -> 7;
                default -> throw new IllegalArgumentException(query);
            };
            for (int attempt = 0; attempt < 200; attempt++) {
                if (edt(() -> { Component page = named(dialog, "settings.nativePage." + index);
                    return page != null && page.isShowing(); })) return;
                Thread.sleep(25);
            }
            throw new AssertionError("Settings page did not load: " + query);
        }

        static void editTimeout(JDialog dialog, String value) throws Exception {
            edt(() -> { ((JTextField)named(dialog,"settings-connection-timeout")).setText(value); return null; });
            fx(() -> null); edt(() -> null);
        }

        static boolean applyEnabled(JDialog dialog) throws Exception {
            return edt(() -> ((JButton) named(dialog, "settings.apply")).isEnabled());
        }
        static void click(JDialog dialog, String name) throws Exception {
            edt(() -> { ((JButton) named(dialog, name)).doClick(); return null; });
        }
        public static void main(String[] args) {
            try { verify(); System.exit(0); }
            catch (Throwable error) { error.printStackTrace(); System.exit(2); }
        }
        @SuppressWarnings({"rawtypes","unchecked"})
        static void verifyPickers(JDialog dialog,String theme) throws Exception {
            for(String id:List.of("settings-theme","settings-ui-font")) {
                JComboBox box=edt(()->(JComboBox)named(dialog,id));
                edt(()->{box.showPopup();return null;});Thread.sleep(150);
                JWindow popup=edt(()->{
                    var model=(javax.swing.plaf.basic.BasicComboPopup)box.getUI().getAccessibleChild(box,0);
                    JList list=model.getList();JWindow window=(JWindow)SwingUtilities.getWindowAncestor(list);
                    assertEquals("settings.comboWindow",window.getName());assertTrue(window.isShowing());
                    assertTrue(window.getContentPane().getName().equals("idea.popupSurface"));
                    if(id.equals("settings-ui-font")) {
                        Component closed=box.getRenderer().getListCellRendererComponent(list,"Menlo",-1,false,false);
                        assertEquals(UIManager.getFont("defaultFont").getFamily(),closed.getFont().getFamily(),"Closed picker must keep interface font");
                        boolean groups=false;
                        for(int i=0;i<box.getItemCount();i++) {
                            Component row=box.getRenderer().getListCellRendererComponent(list,box.getItemAt(i),i,true,false);
                            if(row instanceof JComponent component&&component.getClientProperty("jlshell.fontGroup")!=null) {
                                groups=true;list.setSelectedIndex(i);list.ensureIndexIsVisible(Math.min(box.getItemCount()-1,i+4));
                            }
                            if("Menlo".equals(box.getItemAt(i))) {
                                JLabel sample=NativeSettingsProbe.all(row,JLabel.class).getFirst();
                                // 字体只在真实绘制时加载，测量列表行不能预加载整个系统字体库。
                                sample.setSize(220, 28);
                                var image = new java.awt.image.BufferedImage(220, 28, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                var graphics = image.createGraphics();
                                try { sample.paint(graphics); } finally { graphics.dispose(); }
                                assertEquals(new java.awt.Font("Menlo",0,13).getFamily(),sample.getFont().getFamily());
                            }
                        }
                        Component symbols=box.getRenderer().getListCellRendererComponent(list,"Wingdings",2,true,false);
                        assertEquals(UIManager.getFont("defaultFont").getFamily(),NativeSettingsProbe.all(symbols,JLabel.class).getFirst().getFont().getFamily(),"Symbol font names must remain readable");
                        assertTrue(groups,"Font menu must contain font-type groups");
                    }return window;
                });
                edt(()->{
                    var root=popup.getRootPane();var image=new java.awt.image.BufferedImage(root.getWidth()*2,root.getHeight()*2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics=image.createGraphics();graphics.scale(2,2);root.printAll(graphics);graphics.dispose();
                    try{javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("jlshell.dialog.review"),id+"-popup-"+theme+".png").toFile());}catch(Exception error){throw new RuntimeException(error);}
                    box.hidePopup();return null;
                });
            }
        }
        static void verifyTerminalFontPreview(JDialog dialog) throws Exception {
            edt(()->{((JTextField)named(dialog,"settings.search")).setText("终端");return null;});
            awaitPage(dialog, "终端");
            int originalHeight=edt(()->(Integer)((JComponent)named(dialog,"settings-terminal-preview")).getClientProperty("terminal.previewLineHeight"));
            edt(()->{((JSpinner)named(dialog,"settings-terminal-spacing")).setValue(1.6);return null;});
            fx(()->null);edt(()->null);
            edt(()->{
                var preview=(JComponent)named(dialog,"settings-terminal-preview");
                assertTrue((Integer)preview.getClientProperty("terminal.previewLineHeight")>originalHeight,"Line spacing must immediately change preview baselines");
                return null;
            });
            String family=edt(()->{
                JComboBox<?> chinese=(JComboBox<?>)named(dialog,"settings-terminal-chinese-font");
                String selected=chinese.getItemCount()>1?String.valueOf(chinese.getItemAt(1)):String.valueOf(chinese.getItemAt(0));
                chinese.setSelectedItem(selected);return chinese.getSelectedIndex()>0?selected:"";
            });
            fx(()->null);edt(()->null);
            edt(()->{
                var profile=(com.jlshell.core.model.FontProfile)((JComponent)named(dialog,"settings-terminal-preview")).getClientProperty("terminal.previewProfile");
                assertEquals(family,profile.chineseFamily());assertEquals(1.6,profile.lineSpacing());return null;
            });
            DialogUiSmokeTest.Probe.capture(dialog,"settings-terminal-cjk-spacing");
            click(dialog,"settings.apply");fx(()->null);edt(()->null);
            var persisted=(AppSettingsService)field(main,"appSettingsService");
            assertEquals(family,fx(()->persisted.get("terminal.font.chineseFamily","")));
            assertEquals("1.6",fx(()->persisted.get("terminal.font.lineSpacing","")));
        }

        static void verifyCachedPageThemes(JDialog dialog) throws Exception {
            List<String> queries=List.of("账号","终端","导入","API","插件","快捷键","关于");
            java.util.Map<String,Component> retained=new java.util.HashMap<>();
            for(String theme:List.of("Dark","Light")) {
                edt(()->{((JTextField)named(dialog,"settings.search")).setText("通用");return null;});
                awaitPage(dialog, "通用");
                edt(()->{((JComboBox<?>)named(dialog,"settings-theme")).setSelectedItem(theme);return null;});
                fx(()->null);click(dialog,"settings.apply");fx(()->null);edt(()->null);
                edt(()->{
                    JComponent footer=(JComponent)named(dialog,"settings.footer");
                    var image=new java.awt.image.BufferedImage(footer.getWidth(),footer.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics=image.createGraphics();footer.printAll(graphics);graphics.dispose();
                    assertEquals(com.jlshell.ui.swing.SwingTheme.color("border").getRGB(),image.getRGB(footer.getWidth()/2,0),"Footer separator must follow "+theme);
                    return null;
                });
                for(String query:queries) {
                    edt(()->{((JTextField)named(dialog,"settings.search")).setText(query);return null;});
                    awaitPage(dialog, query);
                    edt(()->{
                        JComponent page=NativeSettingsProbe.all(dialog,JComponent.class).stream()
                            .filter(c->c.getName()!=null&&c.getName().startsWith("settings.nativePage.")&&c.isShowing()).findFirst().orElseThrow();
                        if(retained.containsKey(query))assertSame(retained.get(query),page,"Theme refresh must retain page editors");
                        retained.put(query,page);
                        for(JViewport viewport:NativeSettingsProbe.all(page,JViewport.class))
                            assertEquals(UIManager.getColor("Viewport.background"),viewport.getBackground(),query+" retains stale viewport background after switching to "+theme);
                        for(JTextField field:NativeSettingsProbe.all(page,JTextField.class))
                            assertEquals(UIManager.getColor("TextField.foreground"),field.getForeground(),query+" retains stale text color");
                        return null;
                    });
                    if(query.equals("账号"))DialogUiSmokeTest.Probe.capture(dialog,"settings-account-switched-"+theme.toLowerCase());
                }
            }
        }
        static void verifyImport(JDialog dialog) throws Exception {
            var i18n=(com.jlshell.ui.service.I18nService)field(main,"i18nService");
            edt(()->{NativeSettingsProbe.button(dialog,i18n.get("import.manual.addRow")).doClick();return null;});fx(()->null);edt(()->null);
            JTable table=edt(()->(JTable)named(dialog,"settings.importTable"));assertNotNull(table);
            int row=edt(()->table.getRowCount()-1);
            edt(()->{table.getModel().setValueAt("native-table-fixture",row,1);table.getModel().setValueAt("fixture-password",row,6);return null;});fx(()->null);edt(()->null);
            edt(()->{assertEquals("native-table-fixture",table.getValueAt(row,1));assertEquals("•••",table.getValueAt(row,6));
                assertTrue(table.editCellAt(row,6));assertEquals("fixture-password",new String(((JPasswordField)table.getEditorComponent()).getPassword()));table.getCellEditor().cancelCellEditing();
                NativeSettingsProbe.button(dialog,i18n.get("import.manual.pasteJson")).doClick();return null;});
            JDialog paste=DialogUiSmokeTest.Probe.window("jlshell.import.popup");
            edt(()->{NativeSettingsProbe.assertNative(paste);NativeSettingsProbe.all(paste,JTextArea.class).stream().filter(JTextArea::isEditable).findFirst().orElseThrow()
                .setText("[{\"name\":\"json-fixture\",\"host\":\"127.0.0.1\",\"port\":22,\"user\":\"fixture\",\"authType\":\"PASSWORD\"}]");return null;});fx(()->null);
            edt(()->{NativeSettingsProbe.button(paste,i18n.get("import.button.import")).doClick();return null;});fx(()->null);edt(()->null);
            assertFalse(edt(paste::isShowing));
            edt(()->{assertEquals(1,table.getRowCount());assertEquals("json-fixture",table.getValueAt(0,1));return null;});
        }
        static void verifyFolderAndShareForms() throws Exception {
            var prompt = MainWindow.class.getDeclaredMethod("promptFolderName", Stage.class, String.class, String.class);
            prompt.setAccessible(true);
            var result = new CompletableFuture<Object>();
            Platform.runLater(() -> {
                try { result.complete(prompt.invoke(main, owner, "重命名分组", "原分组")); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
            JDialog folder = DialogUiSmokeTest.Probe.window("jlshell.folder.name");
            edt(() -> {
                NativeSettingsProbe.assertNative(folder);
                ((JTextField)named(folder, "folder.name")).setText("  服务器分组  ");
                return null;
            });
            fx(() -> null); edt(() -> null);
            DialogUiSmokeTest.Probe.capture(folder, "folder-name-swing");
            click(folder, "dialog.ok");
            assertEquals(java.util.Optional.of("服务器分组"), result.get(10, TimeUnit.SECONDS));
            var share = MainWindow.class.getDeclaredMethod("showConnectionShareDialog", Stage.class, com.jlshell.ui.model.ConnectionFormData.class);
            share.setAccessible(true);
            var closed = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try { share.invoke(main, owner, com.jlshell.ui.model.ConnectionFormData.empty("test")); closed.complete(null); }
                catch (Throwable error) { closed.completeExceptionally(error); }
            });
            JDialog export = DialogUiSmokeTest.Probe.window("jlshell.connection.share.export");
            edt(() -> {
                NativeSettingsProbe.assertNative(export);
                assertFalse(((JTextField)named(export,"connection.share.code")).getText().isBlank());
                assertFalse(((JTextArea)((JScrollPane)named(export,"connection.share.preview")).getViewport().getView()).getText().isBlank());
                return null;
            });
            DialogUiSmokeTest.Probe.capture(export,"connection-share-swing");
            click(export,"dialog.cancel"); closed.get(10,TimeUnit.SECONDS);
        }

        static void verify() throws Exception {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            Platform.startup(() -> Platform.setImplicitExit(false));
            AppContext context = fx(() -> {
                AppContext result = new AppContext();
                main = result.getMainWindow();
                owner = new Stage();
                owner.setScene(main.createScene(owner));
                main.showSwingWindow(owner, () -> {});
                return result;
            });
            @SuppressWarnings("unchecked")
            SwingWorkbench workbench = ((CompletableFuture<SwingWorkbench>) field(main, "swingWorkbench")).get(10, TimeUnit.SECONDS);
            edt(() -> { workbench.show(); return null; });
            // 齿轮菜单从真实动态菜单快照构建，分隔线没有 text，也必须正常显示。
            edt(() -> {
                try {
                    var show = SwingWorkbench.class.getDeclaredMethod("showSettingsMenu");
                    show.setAccessible(true); show.invoke(workbench);
                } catch (Exception error) { throw new RuntimeException(error); }
                return null;
            });
            IdeaListPopup popup = null;
            for (int attempt = 0; attempt < 100 && popup == null; attempt++) {
                popup = edt(() -> {
                    for (Window window : Window.getWindows()) {
                        Component found = named(window, "idea.listPopup");
                        if (found instanceof IdeaListPopup p && p.isShowing()) return p;
                    }
                    return null;
                });
                if (popup == null) Thread.sleep(30);
            }
            assertNotNull(popup);
            IdeaListPopup menu = popup;
            edt(() -> {
                @SuppressWarnings("unchecked") JList<IdeaListPopup.Row> list = (JList<IdeaListPopup.Row>) named(menu, "popup.list");
                int settingsIndex = -1, themeIndex = -1;
                for (int index = 0; index < list.getModel().getSize(); index++) {
                    var row = list.getModel().getElementAt(index);
                    if (row.shortcut().contains(",") || row.shortcut().equals("Ctrl+Alt+S")) settingsIndex = index;
                    if (!row.children().isEmpty() && row.title().contains("主题")) themeIndex = index;
                }
                assertTrue(settingsIndex >= 0);
                assertTrue(themeIndex >= 0);
                list.setSelectedIndex(themeIndex);
                list.getActionMap().get("RIGHT").actionPerformed(null);
                try {
                    IdeaListPopup child = (IdeaListPopup) field(menu, "child");
                    assertTrue(menu.isShowing() && child.isShowing());
                    JList<?> childList = (JList<?>) named(child, "popup.list");
                    childList.getActionMap().get("LEFT").actionPerformed(null);
                    assertFalse(child.isShowing());
                } catch (Exception error) { throw new RuntimeException(error); }
                menu.setVisible(false);
                MenuSelectionManager.defaultManager().clearSelectedPath();
                return null;
            });

            AppSettingsService persisted = (AppSettingsService) field(main, "appSettingsService");
            fx(() -> { persisted.set("ui.language", "zh_CN"); return null; });
            var finished = open();
            JDialog dialog = settings();
            assertFalse(finished.isDone(), "Callers must wait for settings to close");
            assertFalse(applyEnabled(dialog));
            verifyPickers(dialog,"dark");
            JDialog nativeSettings=dialog;
            assertNotNull(edt(() -> named(nativeSettings,"settings-size-8-24")));
            edt(() -> {
                assertTrue(((JComponent)named(nativeSettings,"settings-theme")).getVisibleRect().height>0,
                        "The first setting must not be clipped under the breadcrumb");
                assertFalse(((JButton)named(nativeSettings,"settings.back")).isEnabled());
                assertFalse(((JButton)named(nativeSettings,"settings.forward")).isEnabled());
                assertEquals(named(nativeSettings,"settings-ui-font").getY(),named(nativeSettings,"settings-size-8-24").getY(),
                        "Font and size should share the same settings row");
                assertTrue(named(nativeSettings,"settings-connection-timeout").getWidth()<=100,
                        "Numeric settings should keep a compact editor width");
                return null;
            });
            DialogUiSmokeTest.Probe.capture(dialog,"settings-general-dark");
            editTimeout(dialog, "17");
            assertTrue(applyEnabled(dialog));
            click(dialog, "settings.apply");
            fx(() -> null);
            assertEquals("17", fx(() -> persisted.get("connection.timeout", "10")));
            assertTrue(edt(dialog::isShowing));
            editTimeout(dialog, "29");
            click(dialog, "settings.cancel");
            finished.get(10, TimeUnit.SECONDS);
            assertEquals("17", fx(() -> persisted.get("connection.timeout", "10")));

            finished = open();
            dialog = settings();
            editTimeout(dialog, "31");
            JDialog current = dialog;
            edt(() -> { ((JTextField) named(current, "settings.search")).setText("插件"); return null; });
            awaitPage(current, "插件");
            assertTrue(edt(() -> ((JLabel) named(current, "settings.breadcrumb")).getText().contains("插件")));
            click(dialog,"settings.back");
            assertTrue(edt(() -> ((JLabel)named(current,"settings.breadcrumb")).getText().contains("通用")));
            assertEquals("31",edt(() -> ((JTextField)named(current,"settings-connection-timeout")).getText()),
                    "History navigation must preserve unsaved settings");
            assertTrue(edt(() -> ((JButton)named(current,"settings.forward")).isEnabled()));
            click(dialog,"settings.forward");
            fx(() -> null);edt(() -> null);
            assertTrue(edt(() -> ((JLabel)named(current,"settings.breadcrumb")).getText().contains("插件")));

            edt(() -> {NativeSettingsProbe.assertNative(current);return null;});
            edt(() -> { ((JTextField) named(current, "settings.search")).setText("终端"); return null; });
            awaitPage(current, "终端");
            edt(() -> {NativeSettingsProbe.button(current,"新建").doClick();return null;});
            JDialog schemeEditor=DialogUiSmokeTest.Probe.window("jlshell.colors");
            edt(() -> {NativeSettingsProbe.assertNative(schemeEditor);return null;});
            assertFalse(edt(current::isEnabled),"Native color editor must block its settings owner");
            click(schemeEditor,"dialog.cancel");fx(() -> null);edt(() -> null);
            assertTrue(edt(current::isEnabled));
            click(dialog, "settings.ok");
            finished.get(10, TimeUnit.SECONDS);
            assertEquals("31", fx(() -> persisted.get("connection.timeout", "10")));
            assertFalse(edt(current::isShowing));
            finished=open(); dialog=settings();
            JDialog themed=dialog;
            edt(() -> { ((JComboBox<?>)named(themed,"settings-theme")).setSelectedItem("Light"); return null; });
            fx(() -> null);click(dialog,"settings.apply");fx(() -> null);edt(() -> null);
            DialogUiSmokeTest.Probe.capture(dialog,"settings-general-light");
            verifyPickers(dialog,"light");
            for(String query:List.of("账号","终端","导入","API","插件","快捷键","关于")) {
                edt(() -> { ((JTextField)named(themed,"settings.search")).setText(query); return null; });
                awaitPage(themed, query);
                edt(() -> {NativeSettingsProbe.assertNative(themed);return null;});
                if(query.equals("导入"))verifyImport(themed);
                DialogUiSmokeTest.Probe.capture(dialog,"settings-page-"+query);
            }
            verifyCachedPageThemes(dialog);
            verifyTerminalFontPreview(dialog);
            click(dialog,"settings.cancel");finished.get(10,TimeUnit.SECONDS);
            verifyFolderAndShareForms();
            edt(() -> { workbench.close(); return null; });
            fx(() -> { context.close(); return null; });
            System.out.println("SETTINGS_UI_READY: submenu navigation, search, Apply/Cancel/OK, nested FX/EDT loops verified");
        }
    }
}
