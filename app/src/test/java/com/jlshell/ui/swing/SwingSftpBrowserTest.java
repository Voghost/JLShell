package com.jlshell.ui.swing;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.model.*;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Swing navigation with an isolated local directory and an in-memory remote fixture. */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingSftpBrowserTest {
    @TempDir Path temporary;
    private static <T> T edt(Supplier<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        SwingUtilities.invokeLater(() -> { try { result.complete(action.get()); } catch (Throwable error) { result.completeExceptionally(error); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (Component child : parent.getComponents()) { Component match = named(child, name); if (match != null) return match; }
        return null;
    }
    private static void await(Supplier<Boolean> condition) throws Exception {
        for (int i = 0; i < 150; i++) { if (edt(condition)) return; Thread.sleep(25); }
        fail("Timed out waiting for file browser");
    }
    private static void snapshot(JFrame frame, String file) {
        try {
            BufferedImage image = new BufferedImage(frame.getContentPane().getWidth(), frame.getContentPane().getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.getContentPane().printAll(graphics); graphics.dispose();
            Path output = Path.of("../ui/target/review/" + file); Files.createDirectories(output.getParent()); javax.imageio.ImageIO.write(image, "png", output.toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Test void fileMenusCalculateLocalAndRemoteMd5AndRejectFolders() throws Exception {
        Files.writeString(temporary.resolve("abc.txt"), "abc");
        Files.createDirectory(temporary.resolve("folder"));
        var session = mock(SshSession.class);
        var service = mock(SftpService.class);
        when(service.listDirectory(eq(session), anyString())).thenReturn(CompletableFuture.completedFuture(
                new RemoteDirectoryListing("/fixture", List.of(dir("/fixture/folder", "folder"), file("/fixture/abc.txt", "abc.txt")))));
        when(service.md5(eq(session), eq("/fixture/abc.txt"), any()))
                .thenReturn(CompletableFuture.completedFuture("900150983cd24fb0d6963f7d28e17f72"));
        var browser = edt(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
            return new SwingSftpBrowser(session, service, new I18nService(Locale.SIMPLIFIED_CHINESE), ".", temporary);
        });
        var frame = edt(() -> { var f = new JFrame("MD5 menus"); f.setContentPane(browser); f.setSize(650, 760); f.setVisible(true); return f; });
        try {
            for (String side : List.of("local", "remote")) {
                var table = (JTable) edt(() -> named(browser, "sftp." + side + ".table"));
                await(() -> table.getRowCount() == 2);
                edt(() -> {
                    table.setRowSelectionInterval(0, 0);
                    showContext(table, 0);
                    assertFalse(md5Row().enabled(), "A directory cannot be hashed from the file menu");
                    SwingUtilities.getWindowAncestor(popupList()).setVisible(false);
                    table.setRowSelectionInterval(1, 1);
                    showContext(table, 1);
                    var row = md5Row(); assertTrue(row.enabled());
                    SwingUtilities.getWindowAncestor(popupList()).setVisible(false);
                    row.action().run();
                    return null;
                });
                await(() -> {
                    for (Window window : frame.getOwnedWindows()) if (window.isShowing() && "sftp.md5.dialog".equals(window.getName())) {
                        var results = (JTable) named(window, "sftp.md5.results");
                        if ("900150983cd24fb0d6963f7d28e17f72".equals(results.getValueAt(0, 1))) return true;
                    }
                    return false;
                });
                edt(() -> { for (Window window : frame.getOwnedWindows()) if ("sftp.md5.dialog".equals(window.getName())) window.dispose(); return null; });
            }
            verify(service).md5(eq(session), eq("/fixture/abc.txt"), any());
            edt(() -> { assertTrue(frame.isShowing()); assertEquals(0, frame.getExtendedState() & JFrame.ICONIFIED); return null; });
        } finally { edt(() -> { browser.close(); frame.dispose(); return null; }); }
    }

    private static void showContext(JTable table, int row) {
        Rectangle cell = table.getCellRect(row, 0, true);
        table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, cell.x + 30, cell.y + 10, 1, true, java.awt.event.MouseEvent.BUTTON3));
    }

    private static com.jlshell.ui.swing.idea.IdeaListPopup.Row md5Row() {
        JList<?> popup = popupList(); assertNotNull(popup);
        for (int index = 0; index < popup.getModel().getSize(); index++) {
            Object value = popup.getModel().getElementAt(index);
            if (value instanceof com.jlshell.ui.swing.idea.IdeaListPopup.Row row && row.title().contains("MD5")) return row;
        }
        throw new AssertionError("MD5 menu action missing");
    }

    @Test void followsCurrentTerminalOnOpenAndDirectoryChangesAndCanBePaused() throws Exception {
        var fxReady = new CompletableFuture<Void>();
        try { javafx.application.Platform.startup(() -> { javafx.application.Platform.setImplicitExit(false); fxReady.complete(null); }); }
        catch (IllegalStateException running) { javafx.application.Platform.runLater(() -> fxReady.complete(null)); }
        fxReady.get(10, TimeUnit.SECONDS);
        var session = mock(SshSession.class); var service = mock(SftpService.class);
        var slow = new CompletableFuture<RemoteDirectoryListing>();
        when(service.listDirectory(eq(session), anyString())).thenAnswer(call -> {
            String target = call.getArgument(1);
            if ("/slow".equals(target)) return slow;
            return CompletableFuture.completedFuture(new RemoteDirectoryListing(target, List.of()));
        });
        var cwd = new javafx.beans.property.SimpleStringProperty("/terminal/initial");
        Supplier<SwingSftpBrowser> factory = com.jlshell.ui.support.FxThread.supplyAsync(() ->
                SwingSftpBrowser.prepare(session, service, new I18nService(Locale.SIMPLIFIED_CHINESE), "/configured", cwd))
                .get(10, TimeUnit.SECONDS);
        SwingSftpBrowser browser = edt(() -> { SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13); return factory.get(); });
        try {
            JTextField path = (JTextField) edt(() -> named(browser, "sftp.remote.path"));
            JToggleButton follow = (JToggleButton) edt(() -> named(browser, "sftp.followTerminal"));
            await(() -> path.getText().equals("/terminal/initial"));
            assertTrue(edt(follow::isSelected));
            com.jlshell.ui.support.FxThread.run(() -> cwd.set("/slow"));
            await(() -> mockingDetails(service).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("listDirectory") && "/slow".equals(i.getArgument(1))));
            // Return to the displayed directory while the previous request is still pending.
            com.jlshell.ui.support.FxThread.run(() -> cwd.set("/terminal/initial"));
            await(() -> mockingDetails(service).getInvocations().stream().filter(i -> i.getMethod().getName().equals("listDirectory") && "/terminal/initial".equals(i.getArgument(1))).count() >= 2);
            slow.complete(new RemoteDirectoryListing("/slow", List.of()));
            edt(() -> { follow.doClick(); return null; });
            com.jlshell.ui.support.FxThread.supplyAsync(() -> { cwd.set("/中文 path%+"); return null; }).get(10, TimeUnit.SECONDS);
            edt(() -> { assertEquals("/terminal/initial", path.getText()); follow.doClick(); return null; });
            await(() -> path.getText().equals("/中文 path%+"));
            edt(() -> { browser.close(); return null; });
            com.jlshell.ui.support.FxThread.supplyAsync(() -> { cwd.set("/after-close"); return null; }).get(10, TimeUnit.SECONDS);
            edt(() -> null);
            verify(service, never()).listDirectory(session, "/after-close");
        } finally { edt(() -> { browser.close(); return null; }); }
    }

    @Test void browsesWithPopupTreeBreadcrumbsTransfersAndThemeChanges() throws Exception {
        Files.createDirectory(temporary.resolve("Documents"));
        Files.createDirectory(temporary.resolve("Projects"));
        Files.writeString(temporary.resolve("README.md"), "local fixture");
        SshSession session = mock(SshSession.class); SftpService service = mock(SftpService.class);
        CompletableFuture<RemoteDirectoryListing> slow = new CompletableFuture<>();
        when(service.listDirectory(eq(session), anyString())).thenAnswer(call -> {
            String path = call.getArgument(1);
            if (path.equals("/slow")) return slow;
            String canonical = path.equals(".") ? "/home/fixture" : path;
            List<RemoteFileEntry> rows = canonical.equals("/") ? List.of(dir("/home", "home"))
                    : canonical.equals("/home") ? List.of(dir("/home/fixture", "fixture"))
                    : canonical.endsWith("Documents") ? List.of(file(canonical + "/notes.txt", "notes.txt"))
                    : List.of(dir(canonical + "/Documents", "Documents"), dir(canonical + "/Projects", "Projects"), file(canonical + "/server.log", "server.log"), file(canonical + "/config.yaml", "config.yaml"));
            return CompletableFuture.completedFuture(new RemoteDirectoryListing(canonical, rows));
        });
        when(service.upload(eq(session), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(service.download(eq(session), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        SwingSftpBrowser browser = edt(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            return new SwingSftpBrowser(session, service, new I18nService(Locale.SIMPLIFIED_CHINESE), ".", temporary);
        });
        JFrame frame = edt(() -> { JFrame f = new JFrame("JLShell · 文件"); f.setContentPane(browser); f.setSize(650, 760); f.setLocation(80, 60); f.setVisible(true); return f; });
        try {
            JTable remote = (JTable) edt(() -> named(browser, "sftp.remote.table"));
            JTable local = (JTable) edt(() -> named(browser, "sftp.local.table"));
            JTextField path = (JTextField) edt(() -> named(browser, "sftp.remote.path"));
            await(() -> remote.getRowCount() == 4 && local.getRowCount() == 3);
            edt(() -> {
                assertTrue(named(browser, "sftp.remote").getY() > named(browser, "sftp.local").getY());
                assertTrue(all(browser, JTree.class).isEmpty(), "Directory trees must not occupy the main layout");
                assertTrue(all(browser, javafx.embed.swing.JFXPanel.class).isEmpty());
                snapshot(frame, "sftp-dark.png");
                ((JButton) named(browser, "sftp.remote.tree")).doClick(); return null;
            });
            await(() -> popupTree() != null && popupTree().getSelectionPath() != null);
            edt(() -> {
                JTree tree = popupTree(); assertEquals("fixture", tree.getSelectionPath().getLastPathComponent().toString());
                assertTrue(tree.getRowCount() >= 4);
                return null;
            });
            // Capture the actual popup as well as the frame for visual review.
            edt(() -> {
                try { var area = new Rectangle(frame.getLocationOnScreen(), frame.getSize()); javax.imageio.ImageIO.write(new Robot().createScreenCapture(area), "png", Path.of("../ui/target/review/sftp-tree-dark.png").toFile()); }
                catch (Exception error) { throw new RuntimeException(error); }
                return null;
            });
            clickTreeNode("Documents");
            await(() -> remote.getRowCount() == 1 && path.getText().equals("/home/fixture/Documents"));
            await(() -> popupTree() == null);
            // Upload uses the currently displayed remote directory, not the original start path.
            edt(() -> {
                for (int row = 0; row < local.getRowCount(); row++) if ("README.md".equals(local.getValueAt(row, 0))) local.setRowSelectionInterval(row, row);
                ((JButton) named(browser, "sftp.upload")).doClick(); return null;
            });
            await(() -> mockingDetails(service).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("upload")));
            verify(service).upload(eq(session), argThat(r -> r.localPath().equals(temporary.resolve("README.md")) && r.remotePath().equals("/home/fixture/Documents/README.md")), any());
            // Clicking a breadcrumb opens its tree; clicking the root selects that ancestor.
            edt(() -> { all(named(browser, "sftp.remote"), JButton.class).stream().filter(b -> "/home/fixture".equals(b.getToolTipText())).findFirst().orElseThrow().doClick(); return null; });
            await(() -> popupTree() != null && popupTree().getSelectionPath() != null);
            edt(() -> { assertEquals("/home/fixture/Documents", path.getText(), "Opening a breadcrumb must not navigate before choosing a node"); return null; });
            clickTreeNode("/home/fixture");
            await(() -> remote.getRowCount() == 4 && path.getText().equals("/home/fixture"));
            edt(() -> {
                var segments = all(named(browser, "sftp.remote"), JButton.class);
                assertTrue(segments.stream().noneMatch(button -> "子目录".equals(button.getToolTipText())), "No separate breadcrumb dropdown arrows");
                ((JButton) named(browser, "sftp.remote.tree")).doClick(); return null;
            });
            await(() -> popupTree() != null);
            escape();
            await(() -> popupTree() == null);
            // A late response may not overwrite a newer navigation.
            edt(() -> { path.setText("/slow"); path.postActionEvent(); path.setText("/home/fixture/Documents"); path.postActionEvent(); return null; });
            await(() -> remote.getRowCount() == 1);
            slow.complete(new RemoteDirectoryListing("/slow", List.of()));
            edt(() -> { assertEquals("/home/fixture/Documents", path.getText()); remote.setRowSelectionInterval(0, 0); ((JButton) named(browser, "sftp.download")).doClick(); return null; });
            await(() -> mockingDetails(service).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("download")));
            verify(service).download(eq(session), argThat(r -> r.localPath().equals(temporary.resolve("notes.txt")) && r.remotePath().equals("/home/fixture/Documents/notes.txt")), any());
            edt(() -> {
                // The same rounded menu host serves the context menu and ancestor list.
                var cell = remote.getCellRect(0, 0, true);
                remote.dispatchEvent(new java.awt.event.MouseEvent(remote, java.awt.event.MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, cell.x + 30, cell.y + 10, 1, true, java.awt.event.MouseEvent.BUTTON3));
                return null;
            });
            await(() -> popupList() != null);
            edt(() -> { assertRoundedPopup(popupList()); capture(frame, "sftp-context-rounded.png"); return null; });
            escape(); await(() -> popupList() == null);
            edt(() -> {
                path.setText("/home/fixture"); path.postActionEvent();
                frame.setSize(340, 680); return null;
            });
            await(() -> remote.getRowCount() == 4 && remote.getWidth() < 360);
            edt(() -> {
                JButton ancestors = all(named(browser, "sftp.local"), JButton.class).stream()
                        .filter(button -> "上级目录".equals(button.getToolTipText()) && button.isShowing()).findFirst().orElseThrow();
                ancestors.doClick(); return null;
            });
            await(() -> popupList() != null);
            edt(() -> { assertRoundedPopup(popupList()); capture(frame, "sftp-ancestors-rounded.png"); return null; });
            escape(); await(() -> popupList() == null);
            edt(() -> {
                assertFalse(all(browser, JScrollPane.class).stream().anyMatch(p -> p.getHorizontalScrollBar().isShowing()));
                for (JButton button : all(browser, JButton.class)) if (button.isShowing()) assertTrue(button.getWidth() >= 16);
                snapshot(frame, "sftp-narrow-dark.png");
                SwingTheme.install(AppTheme.LIGHT, "#3574f0", "SansSerif", 13); SwingTheme.update(frame); frame.validate();
                snapshot(frame, "sftp-narrow-light.png");
                browser.disconnected();
                assertFalse(((JButton) named(browser, "sftp.upload")).isEnabled());
                assertFalse(((JButton) named(browser, "sftp.download")).isEnabled());
                browser.close(); return null;
            });
        } finally { edt(() -> { browser.close(); frame.dispose(); return null; }); }
    }
    private static void escape() throws Exception {
        await(() -> {
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            return focused != null && (named(focused, "sftp.directoryTree") != null || named(focused, "popup.list") != null);
        });
        Robot robot = new Robot(); robot.setAutoDelay(60); robot.waitForIdle(); robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.waitForIdle();
    }
    private static JList<?> popupList() {
        for (Window window : Window.getWindows()) if (window.isShowing()) {
            Component match = named(window, "popup.list"); if (match instanceof JList<?> list && list.isShowing()) return list;
        }
        return null;
    }
    private static void assertRoundedPopup(Component popup) {
        Window host = SwingUtilities.getWindowAncestor(popup);
        assertNotNull(named(host, "idea.popupSurface"), "All file menus must share the rounded surface");
    }
    private static void capture(JFrame frame, String name) {
        try {
            JList<?> list = popupList();
            JRootPane root = list == null ? frame.getRootPane() : ((JWindow) SwingUtilities.getWindowAncestor(list)).getRootPane();
            BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", Path.of("../ui/target/review/" + name).toFile());
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void clickTreeNode(String name) throws Exception {
        Point point = edt(() -> {
            JTree tree = popupTree(); assertNotNull(tree);
            for (int row = 0; row < tree.getRowCount(); row++) {
                var path = tree.getPathForRow(row);
                if (name.equals(path.getLastPathComponent().toString())) {
                    Rectangle bounds = tree.getPathBounds(path); Point screen = tree.getLocationOnScreen();
                    return new Point(screen.x + bounds.x + Math.min(32, bounds.width - 2), screen.y + bounds.y + bounds.height / 2);
                }
            }
            throw new AssertionError("Missing visible tree node: " + name);
        });
        Robot robot = new Robot(); robot.mouseMove(point.x, point.y);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
        edt(() -> { assertNotNull(popupTree(), "A single click must only select; the tree stays open"); return null; });
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
    }
    private static RemoteFileEntry dir(String path, String name) { return new RemoteFileEntry(path, name, RemoteFileType.DIRECTORY, 0, "rwxr-xr-x", Instant.parse("2026-10-14T09:00:00Z"), 1000, 1000); }
    private static RemoteFileEntry file(String path, String name) { return new RemoteFileEntry(path, name, RemoteFileType.FILE, 24576, "rw-r--r--", Instant.parse("2026-10-14T09:00:00Z"), 1000, 1000); }
    private static JTree popupTree() {
        for (Window window : Window.getWindows()) if (window.isShowing()) {
            Component found = named(window, "sftp.directoryTree"); if (found instanceof JTree tree && tree.isShowing()) return tree;
        }
        return null;
    }
    private static <T> List<T> all(Component root, Class<T> type) {
        java.util.ArrayList<T> result = new java.util.ArrayList<>(); if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(all(child, type)); return result;
    }
}
