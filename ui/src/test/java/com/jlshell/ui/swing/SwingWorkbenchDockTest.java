package com.jlshell.ui.swing;

import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.support.JlshellJediTermWidget;
import com.jlshell.terminal.support.JlshellSettingsProvider;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真正挂载同一个终端；确保工具窗显隐、排序和切换插件页不替换主终端及缓冲区。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingWorkbenchDockTest {
    @Test void longConnectionAndFolderNamesEllipsizeWithinTheSidebarAndKeepFullTooltips() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            String name = "nas 的服务器——生产环境与测试服务器的超长连接名称 <html> & database";
            try {
                view.updateConnections(List.of(new com.jlshell.ui.model.FolderProfile("folder", "生产环境与测试环境的超长分组文件夹名称", null, null, 0)),
                        List.of(new com.jlshell.ui.model.ConnectionProfile("server", name, "192.0.2.10", 22, "user",
                                null, null, "", "", false, null, com.jlshell.core.model.ConnectionType.SSH, "folder", null)), java.util.Set.of());
                view.show();
                view.frame().validate();
                var tree = descendants(view.frame()).stream().filter(javax.swing.JTree.class::isInstance)
                        .map(javax.swing.JTree.class::cast).findFirst().orElseThrow();
                tree.setSelectionRow(1);
                var scroll = (javax.swing.JScrollPane) SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, tree);
                assertEquals(scroll.getViewport().getExtentSize().width, tree.getWidth());
                assertFalse(scroll.getHorizontalScrollBar().isVisible());
                for (int row = 0; row < 2; row++) {
                    Object value = tree.getPathForRow(row).getLastPathComponent();
                    var label = (javax.swing.JLabel) tree.getCellRenderer().getTreeCellRendererComponent(tree, value, row == 1, true, row == 1, row, false);
                    Rectangle bounds = tree.getRowBounds(row);
                    label.setBounds(bounds);
                    assertTrue(label.getWidth() <= tree.getWidth() - bounds.x - tree.getInsets().right);
                    var insets = label.getInsets();
                    String painted = SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()),
                            label.getText(), label.getIcon(), label.getVerticalAlignment(), label.getHorizontalAlignment(),
                            label.getVerticalTextPosition(), label.getHorizontalTextPosition(),
                            new Rectangle(insets.left, insets.top, label.getWidth() - insets.left - insets.right,
                                    label.getHeight() - insets.top - insets.bottom), new Rectangle(), new Rectangle(), label.getIconTextGap());
                    assertNotEquals(label.getText(), painted, "Long names must actually be abbreviated for painting");
                    assertTrue(painted.endsWith("..."), painted);
                    assertNotNull(label.getIcon());
                    if (row == 1) {
                        assertEquals(name, label.getText(), "Rendering must not truncate the stored name");
                        assertTrue(label.getToolTipText().contains("&lt;html&gt; &amp; database"));
                        assertTrue(label.getToolTipText().contains("user@192.0.2.10:22"));
                    }
                }
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(view.frame().getWidth(), view.frame().getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); view.frame().paintAll(graphics); graphics.dispose();
                var output = java.nio.file.Path.of("target/review/sidebar-long-names.png");
                java.nio.file.Files.createDirectories(output.getParent());
                javax.imageio.ImageIO.write(image, "png", output.toFile());
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void sessionDropdownSwitchesExistingDuplicateTitlesWithoutConnectingAndConnectButtonIsInFooter() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.LIGHT, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var connections = new AtomicInteger();
            var selected = new AtomicReference<Object>();
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> connections.incrementAndGet(), items -> {}, project -> {}, folders -> {}, selected::set,
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            try {
                var rightSplit = descendants(view.frame()).stream().filter(javax.swing.JSplitPane.class::isInstance)
                        .map(javax.swing.JSplitPane.class::cast).filter(pane -> "workspace.rightSplit".equals(pane.getName()))
                        .findFirst().orElseThrow();
                assertNull(rightSplit.getRightComponent(), "Welcome screen must have no right sidebar pane");
                assertEquals(0, rightSplit.getDividerSize());
                assertFalse(rightSplit.isEnabled());
                view.addTab("first", "Same server", new JPanel(), noop);
                view.addTab("second", "Same server", new JPanel(), noop);
                view.selectTab("first");
                view.show();
                javax.swing.JButton selector = descendants(view.frame()).stream()
                        .filter(javax.swing.JButton.class::isInstance).map(javax.swing.JButton.class::cast)
                        .filter(button -> "workspace.openSessions".equals(button.getToolTipText())).findFirst().orElseThrow();
                assertTrue(selector.isEnabled());
                selector.doClick();
                javax.swing.JList<?> list = java.util.Arrays.stream(view.frame().getOwnedWindows())
                        .filter(java.awt.Window::isShowing).flatMap(window -> descendants(window).stream())
                        .filter(javax.swing.JList.class::isInstance).map(component -> (javax.swing.JList<?>) component)
                        .findFirst().orElseThrow();
                assertEquals(2, list.getModel().getSize());
                Rectangle cell = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                        cell.x + 5, cell.y + cell.height / 2, 1, false, MouseEvent.BUTTON1));
                assertEquals("second", selected.get());
                assertEquals(0, connections.get());
                javax.swing.JButton connect = descendants(view.frame()).stream()
                        .filter(javax.swing.JButton.class::isInstance).map(javax.swing.JButton.class::cast)
                        .filter(button -> "action.connect".equals(button.getToolTipText())).findFirst().orElseThrow();
                assertTrue(SwingUtilities.convertPoint(connect, 0, 0, view.frame()).y > view.frame().getHeight() / 2);
                view.removeTab("first"); view.removeTab("second");
                assertFalse(selector.isEnabled());
            } finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    private static List<Component> descendants(Container parent) {
        var result = new java.util.ArrayList<Component>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    @Test
    void dragPreviewsOrderThenCommitsOnceAndOutsideDropCancelsWithoutReplacingViews() throws Exception {
        var commits = new AtomicInteger();
        var target = new AtomicInteger(-1);
        var workbench = new AtomicReference<SwingWorkbench>();
        var pane = new AtomicReference<javax.swing.JTabbedPane>();
        var firstHost = new AtomicReference<Component>();
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> { assertEquals("first", tab); target.set(position); commits.incrementAndGet(); },
                    (items, folder) -> {}, (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            workbench.set(view);
            var first = new JPanel();
            view.addTab("first", "First", first, noop);
            view.addTab("second", "Second", new JPanel(), noop);
            view.addTab("third", "Third", new JPanel(), noop);
            view.show();
            var tabs = (javax.swing.JTabbedPane) SwingUtilities.getAncestorOfClass(javax.swing.JTabbedPane.class, first);
            pane.set(tabs); firstHost.set(first.getParent());
            Component label = ((Container) tabs.getTabComponentAt(0)).getComponent(0);
            send(label, tabs, MouseEvent.MOUSE_PRESSED, tabs.getBoundsAt(0), 5, 0);
            send(label, tabs, MouseEvent.MOUSE_DRAGGED, tabs.getBoundsAt(2), 30, MouseEvent.BUTTON1_DOWN_MASK);
            assertSame(firstHost.get(), tabs.getComponentAt(2));
            assertSame(firstHost.get(), first.getParent());
            assertTrue(label.isShowing(), "The pressed label must retain its native mouse event target during drag");
            assertEquals(0, commits.get(), "Dragging must not flood the FX session model");
            send(label, tabs, MouseEvent.MOUSE_RELEASED, tabs.getBoundsAt(2), 30, 0);
            assertEquals(1, commits.get());
            assertEquals(2, target.get());
        }).get(15, TimeUnit.SECONDS);
        try {
            Thread.sleep(250); // 等待真实 EDT 动画定时器完成，不能阻塞 EDT。
            SwingExecutors.runOnEdtAsync(() -> {
                var tabs = pane.get();
                assertEquals(0, workbench.get().frame().getLayeredPane().getComponentsInLayer(javax.swing.JLayeredPane.DRAG_LAYER).length);
                Component label = ((Container) tabs.getTabComponentAt(2)).getComponent(0);
                send(label, tabs, MouseEvent.MOUSE_PRESSED, tabs.getBoundsAt(2), 5, 0);
                send(label, tabs, MouseEvent.MOUSE_DRAGGED, tabs.getBoundsAt(0), 5, MouseEvent.BUTTON1_DOWN_MASK);
                assertSame(firstHost.get(), tabs.getComponentAt(0));
                Rectangle outside = new Rectangle(-800, -800, 20, 20); // 真正移出整个工作区，不能命中新增的边缘分屏区。
                send(label, tabs, MouseEvent.MOUSE_RELEASED, outside, 5, 0);
                assertSame(firstHost.get(), tabs.getComponentAt(2));
                assertEquals(1, commits.get(), "Dropping outside must preserve the committed order");
            }).get(15, TimeUnit.SECONDS);
        } finally { SwingExecutors.runOnEdtAsync(() -> workbench.get().close()).get(15, TimeUnit.SECONDS); }
    }

    @Test
    void rightClickingTabHeaderOpensItsContextMenu() throws Exception {
        var contexts = new AtomicInteger();
        var workbench = new AtomicReference<SwingWorkbench>();
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, collapsed -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {}, (item, reply) -> reply.accept(List.of()),
                    (item, reply) -> { contexts.incrementAndGet(); reply.accept(List.of()); });
            var view = new SwingWorkbench(actions, Map.of());
            workbench.set(view);
            view.addTab("one", "One", new JPanel(), noop);
            view.show();
            try {
                var tabs = descendants(view.frame()).stream().filter(javax.swing.JTabbedPane.class::isInstance)
                        .map(javax.swing.JTabbedPane.class::cast).findFirst().orElseThrow();
                Component label = ((Container) tabs.getTabComponentAt(0)).getComponent(0);
                label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        MouseEvent.BUTTON3_DOWN_MASK, 4, 8, 1, true, MouseEvent.BUTTON3));
                assertEquals(1, contexts.get(), "A secondary-button press on the tab label must request its context menu");
            } finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void tabContextAndGroupMenuIgnoreSeparatorRowsWithNullText() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, collapsed -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {}, (item, reply) -> reply.accept(List.of()),
                    (item, reply) -> reply.accept(List.of(new com.jlshell.ui.swing.SwingMenuBridge.Item(
                            null, "", null, false, false, 1, noop, null, List.of()))));
            var view = new SwingWorkbench(actions, Map.of());
            try {
                view.addTab("one", "One", new JPanel(), noop);
                var rows = view.tabContextRows("one", List.of(new com.jlshell.ui.swing.SwingMenuBridge.Item(
                        null, "", null, false, false, 1, noop, null, List.of())));
                assertFalse(rows.isEmpty());
                assertTrue(rows.stream().noneMatch(row -> "tab.duplicateSession".equals(row.title())));
            } finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void rightToolDockDefaultsToHalfOfWorkspaceAfterSidebarAndRails() {
        assertEquals(638, SwingWorkbench.rightDockContentWidth(1600, 280, 36, 8));
        assertEquals(320, SwingWorkbench.rightDockContentWidth(1600, 280, 36, 8, true));
        assertEquals(638, SwingWorkbench.rightDockContentWidth(1600, 280, 36, 8, false));
        assertEquals(180, SwingWorkbench.rightDockContentWidth(300, 240, 36, 8, true));
        assertEquals(180, SwingWorkbench.rightDockContentWidth(300, 240, 36, 8));
        assertEquals(36, SwingWorkbench.rightDockContainerWidth(0, 36),
                "Closing the tool content must leave its navigation rail visible");
        assertEquals(674, SwingWorkbench.rightDockContainerWidth(638, 36));
    }

    private static void send(Component source, javax.swing.JTabbedPane tabs, int kind, Rectangle bounds, int offset, int modifiers) {
        Point point = SwingUtilities.convertPoint(tabs, new Point(bounds.x + offset, bounds.y + bounds.height / 2), source);
        source.dispatchEvent(new MouseEvent(source, kind, System.currentTimeMillis(), modifiers,
                point.x, point.y, 0, false, kind == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }

    @Test
    void retainsMainTerminalAcrossToolWindowToggleTabReorderAndPluginSelection() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 13, false, 1), TerminalColorScheme.dark());
            var first = JlshellJediTermWidget.create(80, 24, settings, key -> key);
            var second = JlshellJediTermWidget.create(80, 24, settings, key -> key);
            try {
                first.getTerminal().writeCharacters("history preserved 中文");
                var buffer = first.getTerminalTextBuffer();
                String originalScreen = buffer.getScreenLines();
                assertTrue(originalScreen.contains("history preserved"));
                view.addTab("first", "First", first, noop);
                Container workspace = first.getParent();
                assertSame(view.frame(), SwingUtilities.getWindowAncestor(first));
                view.toggleToolWindow();
                assertSame(workspace, first.getParent());
                view.toggleToolWindow();
                assertSame(workspace, first.getParent());
                view.addTab("second", "Second", second, noop);
                view.selectTab("second");
                assertNotSame(workspace, second.getParent());
                view.reorderTabs(List.of("second", "first"));
                view.selectTab("first");
                assertSame(workspace, first.getParent());
                var plugin = new JPanel();
                view.addTab("plugin", "Plugin", plugin, noop);
                view.selectTab("plugin");
                assertSame(workspace, first.getParent());
                assertSame(view.frame(), SwingUtilities.getWindowAncestor(plugin));
                view.removeTab("plugin");
                view.selectTab("first");
                assertSame(workspace, first.getParent());
                assertSame(buffer, first.getTerminalTextBuffer());
                assertEquals(originalScreen, buffer.getScreenLines());
                view.removeTab("first");
                view.removeTab("second");
                assertNull(SwingUtilities.getWindowAncestor(first));
                assertNull(SwingUtilities.getWindowAncestor(second));
            } finally {
                view.close(); first.close(); second.close();
            }
        }).get(15, TimeUnit.SECONDS);
    }
}
