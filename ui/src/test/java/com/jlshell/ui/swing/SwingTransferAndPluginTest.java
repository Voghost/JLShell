package com.jlshell.ui.swing;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.plugin.api.*;
import com.jlshell.plugin.loader.*;
import com.jlshell.sftp.model.RemoteDirectoryListing;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.*;
import com.jlshell.ui.view.PluginsTabView;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingTransferAndPluginTest {
    @TempDir Path temporary;
    private static final I18nService I18N = new I18nService(Locale.ENGLISH);

    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
        edt(() -> { SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13); return null; });
    }

    @Test void directoryPopupListsOtherRootsAndNavigatesWithoutChangingRemotePath() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("drive-one"));
        Path second = Files.createDirectory(temporary.resolve("drive-two"));
        Files.createDirectory(first.resolve("folder")); Files.writeString(second.resolve("second-disk-file"), "data");
        var browser = edt(() -> new SwingSftpBrowser(null, remoteService(), I18N, "/", first, () -> List.of(first, second)));
        var frame = edt(() -> { var f = new JFrame(); f.add(browser); f.setSize(750, 750); f.setVisible(true); return f; });
        try {
            await(() -> named(browser, "sftp.local.path", JTextField.class).getText().equals(first.toString()));
            edt(() -> { named(browser, "sftp.local.tree", JButton.class).doClick(); return null; });
            await(() -> popupTree() != null && path(popupTree(), second.toString()) != null);
            edt(() -> {
                JTree tree = popupTree();
                assertEquals(I18N.get("sftp.localComputer"), tree.getModel().getRoot().toString());
                tree.setSelectionPath(path(tree, second.toString()));
                tree.getActionMap().get("choose").actionPerformed(new ActionEvent(tree, 0, "choose"));
                return null;
            });
            await(() -> named(browser, "sftp.local.path", JTextField.class).getText().equals(second.toString())
                    && named(browser, "sftp.local.table", JTable.class).getRowCount() == 1);
            assertEquals("/", edt(() -> named(browser, "sftp.remote.path", JTextField.class).getText()));
        } finally { edt(() -> { browser.close(); frame.dispose(); return null; }); }
    }

    @Test void defaultDirectoryPopupIncludesEverySystemDrive() throws Exception {
        var browser = edt(() -> new SwingSftpBrowser(null, remoteService(), I18N, "/", temporary));
        var frame = edt(() -> { var f = new JFrame(); f.add(browser); f.setSize(650, 650); f.setVisible(true); return f; });
        try {
            await(() -> named(browser, "sftp.local.path", JTextField.class).getText().equals(temporary.toString()));
            edt(() -> { named(browser, "sftp.local.tree", JButton.class).doClick(); return null; });
            List<String> roots = new ArrayList<>();
            FileSystems.getDefault().getRootDirectories().forEach(root -> roots.add(root.toString()));
            await(() -> popupTree() != null && roots.stream().allMatch(root -> path(popupTree(), root) != null));
            String mapped = System.getenv("JLSHELL_TEST_DRIVE");
            if (mapped != null) {
                assertTrue(roots.contains(mapped), "Windows must enumerate the additional mapped drive");
                edt(() -> {
                    JTree tree = popupTree(); tree.setSelectionPath(path(tree, mapped));
                    tree.getActionMap().get("choose").actionPerformed(new ActionEvent(tree, 0, "choose")); return null;
                });
                await(() -> named(browser, "sftp.local.path", JTextField.class).getText().equals(mapped)
                        && named(browser, "sftp.local.table", JTable.class).getRowCount() == 1);
                assertEquals("drive-marker.txt", edt(() -> named(browser, "sftp.local.table", JTable.class).getValueAt(0, 0)));
            }
        } finally { edt(() -> { browser.close(); frame.dispose(); return null; }); }
    }

    @Test void connectingStateIsRemovedWhenTerminalArrives() throws Exception {
        TabPane model = FxThread.supplyAsync(() -> new TabPane(new Tab("Terminal", new Pane())))
                .get(10, TimeUnit.SECONDS);
        JPanel terminal = edt(JPanel::new);
        var session = edt(() -> new SwingSessionView(model, null, "Monitor", null, "Settings", I18N));
        var frame = edt(() -> { var f = new JFrame(); f.add(session); f.setSize(500, 400); f.setVisible(true); return f; });
        var handle = (com.jlshell.terminal.service.TerminalViewHandle) Proxy.newProxyInstance(
                com.jlshell.terminal.service.TerminalViewHandle.class.getClassLoader(),
                new Class<?>[]{com.jlshell.terminal.service.TerminalViewHandle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("component")) return terminal;
                    if (method.getName().equals("closeAsync")) return CompletableFuture.completedFuture(null);
                    return null;
                });
        try {
            assertTrue(edt(() -> named(session, "workspace.loading", JPanel.class).isShowing()));
            session.attach(handle);
            await(terminal::isShowing);
            assertNull(edt(() -> find(session, "workspace.loading", JPanel.class)));
        } finally { edt(() -> { session.close(); frame.dispose(); return null; }); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void nativePluginCanPaintBeforeItsActivationReturns() throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch afterOpen = new CountDownLatch(1), releaseActivation = new CountDownLatch(1), painted = new CountDownLatch(1);
        var plugin = new JlShellPlugin() {
            public String id() { return "test.native.delayed-activation"; }
            public String displayName() { return "Native page"; }
            public String version() { return "1.0"; }
            public String description() { return "Native test"; }
            public boolean requiresSshSession() { return false; }
            public void deactivate() {}
            public void activate(PluginContext context) {
                assertTrue(Platform.isFxApplicationThread());
                context.openSwingTab("Native page", () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    created.countDown();
                    return new JPanel() {
                        @Override protected void paintComponent(Graphics g) { super.paintComponent(g); painted.countDown(); }
                    };
                });
                afterOpen.countDown();
                try { assertTrue(releaseActivation.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        };
        var descriptor = new PluginDescriptor(plugin.id(), plugin.displayName(), "1.0", "test", "test", false,
                "", "", PluginCompatibilityStatus.COMPATIBLE, "", plugin);
        var manager = new PluginManager(temporary.toString()) {
            @Override public List<PluginDescriptor> getAvailablePlugins() { return List.of(descriptor); }
            @Override public void activatePlugin(String id, PluginContext context,
                                                 com.jlshell.core.support.PerformanceTrace timing) { plugin.activate(context); }
        };
        var executor = Executors.newSingleThreadExecutor();
        var themes = FxThread.supplyAsync(() -> new ThemeService(new AppSettingsService() {
            public Optional<String> get(String key) { return Optional.empty(); }
            public void set(String key, String value) {}
            public void remove(String key) {}
        }, new ColorSchemeRegistry(null, null), executor)).get(10, TimeUnit.SECONDS);
        TabPane model = FxThread.supplyAsync(() -> new TabPane(new Tab("Terminal", new Pane()), new Tab("Files", new Pane())))
                .get(10, TimeUnit.SECONDS);
        var plugins = FxThread.supplyAsync(() -> {
            var list = new PluginsTabView(manager, null, "test", null, model, I18N, themes, remoteService(), null, null, null);
            model.getTabs().add(new Tab("Plugins", list)); model.getSelectionModel().select(2); return list;
        }).get(10, TimeUnit.SECONDS);
        var session = edt(() -> new SwingSessionView(model, themes, "Monitor", null, "Settings", I18N));
        FxThread.supplyAsync(() -> {
            model.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> session.publishPages());
            model.getSelectionModel().selectedIndexProperty().addListener((o, old, selected) ->
                    SwingUtilities.invokeLater(() -> session.selectPage(selected.intValue(), false)));
            session.publishPages(); return null;
        }).get(10, TimeUnit.SECONDS);
        var frame = edt(() -> { var f = new JFrame(); f.add(session.rightToolWindow()); f.setSize(500, 550); f.setVisible(true); f.getInputContext(); return f; });
        long clicked = System.nanoTime();
        try {
            edt(() -> { named(frame, "plugin-open-session:" + plugin.id(), JButton.class).doClick(); return null; });
            assertTrue(afterOpen.await(5, TimeUnit.SECONDS));
            assertTrue(created.await(5, TimeUnit.SECONDS), "Native factory must run while activate is blocked");
            assertTrue(painted.await(5, TimeUnit.SECONDS), "Swing page must paint while FX activate() is still blocked after openSwingTab");
            assertEquals(1L, releaseActivation.getCount());
            System.out.printf("Native plugin click-to-paint: %.1f ms; activation deliberately still blocked%n", (System.nanoTime() - clicked) / 1e6);
        } finally {
            releaseActivation.countDown();
            FxThread.supplyAsync(() -> { plugins.dispose(); return null; }).get(10, TimeUnit.SECONDS);
            edt(() -> { session.close(); frame.dispose(); return null; }); executor.shutdownNow();
        }
    }

    private static SftpService remoteService() {
        return (SftpService) Proxy.newProxyInstance(SftpService.class.getClassLoader(), new Class<?>[]{SftpService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("listDirectory")) return CompletableFuture.completedFuture(new RemoteDirectoryListing("/", List.of()));
                    throw new UnsupportedOperationException(method.getName());
                });
    }
    private static JTree popupTree() {
        for (Window window : Window.getWindows()) if (window.isShowing()) {
            JTree tree = find(window, "sftp.directoryTree", JTree.class); if (tree != null) return tree;
        }
        return null;
    }
    private static TreePath path(JTree tree, String label) {
        var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) { var node = (DefaultMutableTreeNode) nodes.nextElement(); if (label.equals(node.toString())) return new TreePath(node.getPath()); }
        return null;
    }
    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) return type.cast(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) { T match = find(child, name, type); if (match != null) return match; }
        return null;
    }
    private static <T extends Component> T named(Component root, String name, Class<T> type) { return Objects.requireNonNull(find(root, name, type), name); }
    private static <T> T edt(java.util.function.Supplier<T> action) throws Exception { return SwingExecutors.supplyOnEdtAsync(action).get(10, TimeUnit.SECONDS); }
    private static void await(java.util.function.BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!edt(check::getAsBoolean)) { if (System.nanoTime() > deadline) fail("Timed out waiting for UI"); Thread.sleep(25); }
    }
}
