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
                "-Djlshell.ui.renderer=swing", "-cp", System.getProperty("java.class.path"), Probe.class.getName())
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
        static void editTimeout(JDialog dialog, String value) throws Exception {
            SwingFxPanel panel = edt(() -> (SwingFxPanel) named(dialog, "settings.content"));
            fx(() -> {
                Parent root = panel.getScene().getRoot();
                root.applyCss(); root.layout();
                ((TextField) root.lookup("#settings-connection-timeout")).setText(value);
                return null;
            });
            edt(() -> null);
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
            Thread.sleep(250);
            assertTrue(edt(() -> ((JLabel) named(current, "settings.breadcrumb")).getText().contains("插件")));
            SwingFxPanel selectedPanel = edt(() -> (SwingFxPanel) named(current, "settings.content"));
            assertNotNull(fx(() -> selectedPanel.getScene().getRoot()));
            edt(() -> { ((JTextField) named(current, "settings.search")).setText("终端"); return null; });
            Thread.sleep(250);
            var editorClosed = new CompletableFuture<Void>();
            Platform.runLater(() -> {
                try {
                    Parent root = selectedPanel.getScene().getRoot();
                    root.applyCss(); root.layout();
                    root.lookupAll(".button").stream().filter(javafx.scene.control.Button.class::isInstance)
                            .map(javafx.scene.control.Button.class::cast).filter(button -> button.getText().equals("新建"))
                            .findFirst().orElseThrow().fire();
                    editorClosed.complete(null);
                } catch (Throwable error) { editorClosed.completeExceptionally(error); }
            });
            javafx.scene.control.DialogPane schemeEditor = null;
            for (int attempt = 0; attempt < 100 && schemeEditor == null; attempt++) {
                schemeEditor = fx(() -> javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing)
                        .map(window -> window.getScene().getRoot()).filter(javafx.scene.control.DialogPane.class::isInstance)
                        .map(javafx.scene.control.DialogPane.class::cast).findFirst().orElse(null));
                if (schemeEditor == null) Thread.sleep(30);
            }
            assertNotNull(schemeEditor, "Color scheme editor must still open from the embedded page");
            assertFalse(edt(current::isEnabled), "Nested FX dialogs must block their Swing settings owner");
            javafx.scene.control.DialogPane editor = schemeEditor;
            fx(() -> { ((javafx.scene.control.Button) editor.lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire(); return null; });
            editorClosed.get(10, TimeUnit.SECONDS);
            assertTrue(edt(current::isEnabled));
            click(dialog, "settings.ok");
            finished.get(10, TimeUnit.SECONDS);
            assertEquals("31", fx(() -> persisted.get("connection.timeout", "10")));
            assertFalse(edt(current::isShowing));
            edt(() -> { workbench.close(); return null; });
            fx(() -> { context.close(); return null; });
            System.out.println("SETTINGS_UI_READY: submenu navigation, search, Apply/Cancel/OK, nested FX/EDT loops verified");
        }
    }
}
