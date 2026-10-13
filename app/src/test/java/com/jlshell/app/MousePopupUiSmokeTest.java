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
            SwingFxPanel panel = edt(() -> (SwingFxPanel) named(dialog, "settings.content"));
            fx(() -> { panel.getScene().getRoot().applyCss(); panel.getScene().getRoot().layout(); return null; });
            ComboBox<?> combo = fx(() -> panel.getScene().getRoot().lookupAll(".combo-box").stream()
                    .filter(ComboBox.class::isInstance).map(ComboBox.class::cast)
                    .filter(candidate -> candidate.getItems().equals(javafx.collections.FXCollections.observableArrayList("Dark", "Light")))
                    .findFirst().orElseThrow());
            javafx.geometry.Bounds bounds = fx(() -> combo.localToScene(combo.getBoundsInLocal()));
            Point origin = edt(panel::getLocationOnScreen);
            mouse(new Point(origin.x + (int) bounds.getMaxX() - 15, origin.y + (int) ((bounds.getMinY() + bounds.getMaxY()) / 2)));
            assertTrue(fx(combo::isShowing), "Embedded theme dropdown must open on mouse click");
            ListCell<?> darkCell = fx(() -> javafx.stage.Window.getWindows().stream().filter(javafx.stage.PopupWindow.class::isInstance)
                    .filter(javafx.stage.Window::isShowing)
                    .flatMap(window -> window.getScene().getRoot().lookupAll(".list-cell").stream())
                    .filter(ListCell.class::isInstance).map(ListCell.class::cast)
                    .filter(cell -> "Dark".equals(cell.getText()) && cell.localToScreen(cell.getBoundsInLocal()) != null)
                    .findFirst().orElseThrow());
            javafx.geometry.Bounds option = fx(() -> darkCell.localToScreen(darkCell.getBoundsInLocal()));
            mouse(new Point((int) ((option.getMinX() + option.getMaxX()) / 2), (int) ((option.getMinY() + option.getMaxY()) / 2)));
            assertEquals("Dark", fx(combo::getValue), "Mouse must select the embedded theme option");
            JButton apply = edt(() -> (JButton) named(dialog, "settings.apply"));
            mouse(edt(() -> center(apply)));
            assertEquals(AppTheme.DARK, fx(themes::currentTheme), "Mouse Apply must update the host theme");
            assertEquals("DARK", fx(() -> persisted.get("ui.theme", "")));
            assertFalse(edt(workbench.frame()::isEnabled), "Apply must preserve settings modality");
            String chosenFamily = fx(() -> {
                @SuppressWarnings("unchecked") javafx.scene.control.Spinner<Integer> size =
                        (javafx.scene.control.Spinner<Integer>) panel.getScene().getRoot().lookup(".spinner");
                size.getValueFactory().setValue(15);
                @SuppressWarnings("unchecked") ComboBox<String> fonts = (ComboBox<String>) panel.getScene().getRoot()
                        .lookupAll(".combo-box").stream().filter(ComboBox.class::isInstance).map(ComboBox.class::cast)
                        .filter(candidate -> candidate.getItems().size() > 20).findFirst().orElseThrow();
                String family = fonts.getItems().contains("Menlo") ? "Menlo" : fonts.getItems().get(1);
                fonts.getSelectionModel().select(family);
                return family;
            });
            mouse(edt(() -> center(apply)));
            assertEquals("15", fx(() -> persisted.get("ui.font.size", "")));
            assertEquals(chosenFamily, fx(() -> persisted.get("ui.font.family", "")));
            assertEquals(chosenFamily, edt(() -> UIManager.getFont("defaultFont").getName()));
            assertEquals(15, edt(() -> UIManager.getFont("defaultFont").getSize()), "Font Apply must immediately update Swing");
            assertEquals(15, edt(() -> {
                try { return ((JTree) field(workbench, "tree")).getFont().getSize(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }), "Existing connection tree must receive the chosen font size");
            fx(() -> {
                assertTrue(panel.getScene().getRoot().getStyle().contains("15px"), "Font Apply must update embedded FX forms");
                assertTrue(panel.getScene().getRoot().getStyle().contains(chosenFamily));
                return null;
            });
            JTextField settingsSearch = edt(() -> (JTextField) named(dialog, "settings.search"));
            edt(() -> { settingsSearch.setText("快捷键"); return null; });
            Thread.sleep(300);
            javafx.scene.control.Button recording = fx(() -> {
                panel.getScene().getRoot().applyCss(); panel.getScene().getRoot().layout();
                @SuppressWarnings("unchecked") javafx.scene.control.TreeView<javafx.scene.Node> actions =
                        (javafx.scene.control.TreeView<javafx.scene.Node>) panel.getScene().getRoot().lookup(".idea-actions-tree");
                assertNotNull(actions, "Keymap page must use a virtualized actions tree");
                var firstAction = actions.getRoot().getChildren().getFirst().getChildren().getFirst();
                actions.scrollTo(actions.getRow(firstAction));
                javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) firstAction.getValue();
                var lastButton = ((javafx.scene.layout.HBox) row.getChildren().get(2)).getChildren().getLast();
                assertTrue(lastButton.localToScene(lastButton.getBoundsInLocal()).getMaxX()
                        <= actions.localToScene(actions.getBoundsInLocal()).getMaxX() - 6,
                        "Both shortcut columns must remain inside the actions viewport");
                return (javafx.scene.control.Button) ((javafx.scene.layout.HBox) row.getChildren().get(1)).getChildren().getFirst();
            });
            Thread.sleep(150);
            String oldBinding = fx(recording::getText);
            mouseFx(panel, recording);
            robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE); robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
            robot.waitForIdle(); Thread.sleep(200);
            assertTrue(edt(dialog::isShowing), "Escape must cancel recording, not close settings");
            assertEquals(oldBinding, fx(recording::getText));
            mouseFx(panel, recording);
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
