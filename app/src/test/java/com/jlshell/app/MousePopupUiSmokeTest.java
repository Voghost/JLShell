package com.jlshell.app;

import com.jlshell.ui.swing.SwingFxPanel;
import com.jlshell.ui.swing.SwingWorkbench;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static com.jlshell.app.SettingsUiSmokeTest.Probe.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实系统鼠标事件：不能只调用 actionPerformed/doClick 来代替弹窗点击验收。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class MousePopupUiSmokeTest {
    @TempDir Path temporary;
    @Test void clicksNativePopupAndEmbeddedThemeComboWithSystemMouse() throws Exception {
        Path log = temporary.resolve("mouse.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                "-Duser.home=" + temporary.resolve("home"), "-Djlshell.ui.preview=true",
                "-Djlshell.ui.renderer=swing", "-cp", System.getProperty("java.class.path"), Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(read(log).contains("MOUSE_POPUP_READY"), () -> read(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception error) { return error.toString(); }
    }
    public static class Probe {
        static Robot robot;
        static void mouse(Point point) throws Exception {
            robot.mouseMove(point.x, point.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            Thread.sleep(250);
        }
        static Point center(Component component) {
            Point point = component.getLocationOnScreen();
            point.translate(component.getWidth() / 2, component.getHeight() / 2);
            return point;
        }
        static void mouseFx(SwingFxPanel panel, javafx.scene.Node node) throws Exception {
            var bounds = fx(() -> node.localToScene(node.getBoundsInLocal()));
            Point origin = edt(panel::getLocationOnScreen);
            mouse(new Point(origin.x + (int) ((bounds.getMinX() + bounds.getMaxX()) / 2),
                    origin.y + (int) ((bounds.getMinY() + bounds.getMaxY()) / 2)));
        }
        static IdeaListPopup popup() throws Exception {
            for (int i = 0; i < 80; i++) {
                IdeaListPopup value = edt(() -> {
                    for (Window window : Window.getWindows()) {
                        Component found = named(window, "idea.listPopup");
                        if (found instanceof IdeaListPopup p && p.isShowing()) return p;
                    }
                    return null;
                });
                if (value != null) return value;
                Thread.sleep(30);
            }
            throw new AssertionError("Popup did not open");
        }
        static void mouseRow(IdeaListPopup popup, String title) throws Exception {
            mouse(edt(() -> {
                @SuppressWarnings("unchecked") JList<IdeaListPopup.Row> list = (JList<IdeaListPopup.Row>) named(popup, "popup.list");
                for (int i = 0; i < list.getModel().getSize(); i++) {
                    if (list.getModel().getElementAt(i).title().contains(title)) {
                        Rectangle cell = list.getCellBounds(i, i);
                        Point point = list.getLocationOnScreen();
                        // 分组标题在条目上方，点击底部正文，而不是分隔线。
                        point.translate(70, cell.y + cell.height - 10);
                        return point;
                    }
                }
                throw new AssertionError("Missing row: " + title);
            }));
        }
        public static void main(String[] args) {
            try { verify(); System.exit(0); }
            catch (Throwable error) { error.printStackTrace(); System.exit(2); }
        }
        static void verify() throws Exception {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            Platform.startup(() -> Platform.setImplicitExit(false));
            AppContext context = fx(() -> {
                AppContext value = new AppContext();
                main = value.getMainWindow(); owner = new Stage();
                owner.setScene(main.createScene(owner));
                main.showSwingWindow(owner, () -> {});
                return value;
            });
            @SuppressWarnings("unchecked") SwingWorkbench workbench = ((CompletableFuture<SwingWorkbench>) field(main, "swingWorkbench")).get(10, TimeUnit.SECONDS);
            robot = new Robot(); robot.setAutoDelay(70);
            edt(() -> { workbench.show(); workbench.frame().toFront(); return null; });
            // macOS 的应用激活/Space 切换是异步的，不能用一次 500ms 等待当作前台就绪。
            for (int attempt = 0; attempt < 8 && !edt(workbench.frame()::isActive); attempt++) {
                edt(() -> { Desktop.getDesktop().requestForeground(true); workbench.frame().toFront();
                    workbench.frame().requestFocus(); return null; });
                Thread.sleep(400);
                // 首次按下可能仅用于激活窗口，点击无动作的欢迎区后才开始测试菜单。
                if (!edt(workbench.frame()::isActive)) mouse(edt(() -> center(workbench.frame().getRootPane())));
            }
            assertTrue(edt(workbench.frame()::isActive), "Robot must target the foreground test window");
            JButton gear = (JButton) field(workbench, "settingsButton");
            // 前台激活早于 macOS 一体化标题栏完成布局，先等待实际点击位置稳定。
            fx(() -> null); edt(() -> null);
            Point previousGear = edt(() -> center(gear));
            for (int attempt = 0; attempt < 10; attempt++) {
                Thread.sleep(150);
                Point currentGear = edt(() -> center(gear));
                if (currentGear.equals(previousGear)) break;
                previousGear = currentGear;
            }
            mouse(edt(() -> center(gear)));
            IdeaListPopup root = popup();
            mouseRow(root, "主题");
            IdeaListPopup child = edt(() -> {
                try { return (IdeaListPopup) field(root, "child"); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertNotNull(child, "Mouse must open the theme submenu");
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.waitForIdle(); Thread.sleep(100);
            assertFalse(edt(child::isShowing));
            assertTrue(edt(root::isShowing));
            mouseRow(root, "主题");
            child = edt(() -> {
                try { return (IdeaListPopup) field(root, "child"); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue(edt(child::isShowing), "A submenu must reopen after Escape returns to its parent");
            mouseRow(child, "浅色");
            ThemeService themes = (ThemeService) field(main, "themeService");
            assertEquals(AppTheme.LIGHT, fx(themes::currentTheme), "Mouse click must dispatch original theme action");
            assertFalse(edt(root::isShowing));
            com.jlshell.core.service.AppSettingsService persisted = (com.jlshell.core.service.AppSettingsService) field(main, "appSettingsService");
            fx(() -> { persisted.set("ui.language", "zh_CN"); return null; });
            mouse(edt(() -> center(gear)));
            mouseRow(popup(), "偏好设置");
            JDialog dialog = settings();
            assertFalse(edt(workbench.frame()::isEnabled), "Settings must still block its host window");
            JComboBox<?> combo = edt(() -> (JComboBox<?>)named(dialog,"settings-theme"));
            mouse(edt(() -> center(combo)));
            assertTrue(edt(combo::isPopupVisible), "Native theme dropdown must open on mouse click");
            Point darkOption = edt(() -> {
                var popup = (javax.swing.plaf.basic.BasicComboPopup)combo.getUI().getAccessibleChild(combo,0);
                JList<?> list=popup.getList();
                var cell=list.getCellBounds(0,0); Point point=list.getLocationOnScreen();
                point.translate(cell.x+cell.width/2,cell.y+cell.height/2);return point;
            });
            mouse(darkOption);
            assertEquals("Dark", edt(combo::getSelectedItem), "Mouse must select the native theme option");
            assertFalse(edt(combo::isPopupVisible), "Selecting a row must dismiss the combo popup");
            JComboBox<?> fontPicker = edt(() -> (JComboBox<?>)named(dialog,"settings-ui-font"));
            mouse(edt(() -> center(fontPicker)));
            assertTrue(edt(fontPicker::isPopupVisible));
            mouse(edt(() -> center(named(dialog,"settings.search"))));
            assertFalse(edt(fontPicker::isPopupVisible), "Clicking outside must dismiss the rounded popup");
            mouse(edt(() -> center(fontPicker)));
            assertTrue(edt(fontPicker::isPopupVisible));
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.waitForIdle();Thread.sleep(150);
            assertFalse(edt(fontPicker::isPopupVisible), "Escape must dismiss the font popup");
            mouse(edt(() -> center(fontPicker)));
            assertTrue(edt(fontPicker::isPopupVisible));
            // Page replacement without a mouse click must also close an anchored popup.
            edt(() -> { ((JTextField)named(dialog,"settings.search")).setText("API");return null; });
            Thread.sleep(300);
            assertTrue(edt(() -> ((JLabel)named(dialog,"settings.breadcrumb")).getText().contains("API")));
            assertFalse(edt(fontPicker::isPopupVisible), "Changing settings page must dismiss a detached picker");
            assertFalse(edt(() -> java.util.Arrays.stream(Window.getWindows()).anyMatch(window ->
                    "settings.comboWindow".equals(window.getName()) && window.isShowing())), "No orphan popup window may remain");
            edt(() -> { ((JTextField)named(dialog,"settings.search")).setText("通用");return null; });
            Thread.sleep(300);
            JButton apply = edt(() -> (JButton) named(dialog, "settings.apply"));
            edt(() -> { assertTrue(dialog.isEnabled()); assertTrue(apply.isEnabled()); return null; });
            mouse(edt(() -> center(apply)));
            for (int attempt = 0; attempt < 50 && fx(themes::currentTheme) != AppTheme.DARK; attempt++) Thread.sleep(50);
            assertEquals(AppTheme.DARK, fx(themes::currentTheme), "Mouse Apply must update the host theme");
            assertEquals("DARK", fx(() -> persisted.get("ui.theme", "")));
            assertFalse(edt(workbench.frame()::isEnabled), "Apply must preserve settings modality");
            String chosenFamily = edt(() -> {
                ((JSpinner)named(dialog,"settings-size-8-24")).setValue(15);
                JComboBox<?> fonts=(JComboBox<?>)named(dialog,"settings-ui-font");
                String family="Menlo";
                boolean found=false;
                for(int i=0;i<fonts.getItemCount();i++) if(family.equals(fonts.getItemAt(i)))found=true;
                if(!found)family=(String)fonts.getItemAt(1);
                fonts.setSelectedItem(family);return family;
            });
            fx(() -> null);
            mouse(edt(() -> center(apply)));
            assertEquals("15", fx(() -> persisted.get("ui.font.size", "")));
            assertEquals(chosenFamily, fx(() -> persisted.get("ui.font.family", "")));
            assertEquals(chosenFamily, edt(() -> UIManager.getFont("defaultFont").getName()));
            assertEquals(15, edt(() -> UIManager.getFont("defaultFont").getSize()), "Font Apply must immediately update Swing");
            assertEquals(15, edt(() -> {
                try { return ((JTree) field(workbench, "tree")).getFont().getSize(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }), "Existing connection tree must receive the chosen font size");
            assertEquals(15,edt(() -> named(dialog,"settings-connection-timeout").getFont().getSize()),
                    "Font Apply must update native form controls");
            JTextField settingsSearch = edt(() -> (JTextField) named(dialog, "settings.search"));
            edt(() -> { settingsSearch.setText("快捷键"); return null; });
            Thread.sleep(300);
            JTree actions=edt(()->(JTree)named(dialog,"settings.actionsTree"));
            assertNotNull(actions);
            var path=edt(()->actions.getPathForRow(1));
            java.util.function.Supplier<JButton> recording=()->{
                var node=path.getLastPathComponent();
                Component row=actions.getCellRenderer().getTreeCellRendererComponent(actions,node,true,false,true,1,false);
                return (JButton)named(row,"shortcut.app.newConnection.primary");
            };
            String oldBinding=edt(()->recording.get().getText());
            java.util.function.Supplier<Point> keyPoint=()->{
                Component row=recording.get();
                // Renderer rows are not attached until editing, so use the tree row origin.
                Point local=SwingUtilities.convertPoint(row,row.getWidth()/2,row.getHeight()/2,row.getParent().getParent());
                Rectangle bounds=actions.getPathBounds(path);Point origin=actions.getLocationOnScreen();
                origin.translate(bounds.x+local.x,bounds.y+local.y);return origin;
            };
            mouse(edt(keyPoint));
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.waitForIdle(); Thread.sleep(200);
            assertTrue(edt(dialog::isShowing), "Escape must cancel recording, not close settings");
            assertEquals(oldBinding, edt(()->recording.get().getText()));
            mouse(edt(keyPoint));
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL); robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT);
            robot.keyPress(java.awt.event.KeyEvent.VK_K); robot.keyRelease(java.awt.event.KeyEvent.VK_K);
            robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT); robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            robot.waitForIdle(); Thread.sleep(200);
            var shortcuts = (com.jlshell.core.shortcut.ShortcutRegistry) field(main, "shortcutRegistry");
            assertEquals("ctrl shift K", fx(() -> shortcuts.getEffectivePrimary("app.newConnection")));
            JButton cancel = edt(() -> (JButton) named(dialog, "settings.cancel"));
            mouse(edt(() -> center(cancel)));
            fx(() -> null);
            edt(() -> null);
            var i18n = (com.jlshell.ui.service.I18nService) field(main, "i18nService");
            var widget = edt(() -> {
                var terminal = com.jlshell.terminal.support.JlshellJediTermWidget.create(100, 35,
                        new com.jlshell.terminal.support.JlshellSettingsProvider(
                                new com.jlshell.core.model.FontProfile("Monospaced", 13, false, 1),
                                com.jlshell.terminal.model.TerminalColorScheme.dark()), i18n::get);
                com.jlshell.ui.swing.TerminalPopupBridge.install(terminal);
                workbench.addTab("mouse-terminal", "Terminal UI fixture", terminal, () -> {});
                workbench.selectTab("mouse-terminal");
                for (int line = 0; line < 1200; line++) {
                    terminal.getTerminal().writeCharacters("Scroll fixture " + line);
                    terminal.getTerminal().newLine(); terminal.getTerminal().carriageReturn();
                }
                return terminal;
            });
            Thread.sleep(400);
            for (AppTheme theme : java.util.List.of(AppTheme.DARK, AppTheme.LIGHT)) {
                fx(() -> { themes.setTheme(theme); return null; });
                Thread.sleep(200);
                Point terminalPoint = edt(() -> center(widget.getTerminalPanel()));
                robot.mouseMove(terminalPoint.x, terminalPoint.y);
                robot.mousePress(InputEvent.BUTTON3_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                robot.waitForIdle(); Thread.sleep(200);
                IdeaListPopup terminalMenu = popup();
                mouseRow(terminalMenu, "全选");
                assertNotNull(edt(() -> widget.getTerminalPanel().getSelection()), "Terminal menu must execute the original Select All action");
                assertFalse(edt(terminalMenu::isShowing));
            }
            JScrollBar bar = edt(() -> findScrollBar(widget));
            assertTrue(edt(bar::isOpaque), "Dragging must not invalidate transparent ancestor surfaces");
            int before = edt(bar::getValue);
            Point thumb = edt(() -> {
                Point point = bar.getLocationOnScreen(); point.translate(bar.getWidth() / 2, bar.getHeight() - 8); return point;
            });
            robot.mouseMove(thumb.x, thumb.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int delta = 0; delta < 140; delta += 7) robot.mouseMove(thumb.x, thumb.y - delta);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
            assertTrue(edt(bar::getValue) < before, "Real mouse thumb dragging must move terminal history");
            if (com.formdev.flatlaf.util.SystemInfo.isMacOS) {
                assertTrue(fx(com.jlshell.ui.swing.MacMenuBridge::isInstalled));
                assertNull(edt(() -> workbench.frame().getJMenuBar()), "Native global menu must not add a second header row");
            }
            edt(() -> { widget.close(); return null; });
            edt(() -> { workbench.close(); return null; });
            fx(() -> { context.close(); return null; });
            System.out.println("MOUSE_POPUP_READY: theme/font Apply, shortcut recording, terminal context menus, thumb dragging and native menu verified");
        }
        static JScrollBar findScrollBar(Container parent) {
            for (Component child : parent.getComponents()) {
                if (child instanceof JScrollBar bar) return bar;
                if (child instanceof Container container) { JScrollBar result = findScrollBar(container); if (result != null) return result; }
            }
            return null;
        }
    }
}
