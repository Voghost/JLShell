package com.jlshell.ui.swing;

import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.support.JlshellJediTermWidget;
import com.jlshell.terminal.support.JlshellSettingsProvider;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingWorkspaceSplitTest {
    @Test void splitTabsDistinguishTheActiveGroupAndFollowBodyAndHeaderFocus() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<SwingWorkbench>();
        var first = new JTextArea("First terminal body");
        var second = new JTextArea("Second terminal body");
        var active = new java.util.concurrent.atomic.AtomicReference<Object>();
        Runnable noop = () -> {};
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> fail("Changing group focus must not connect"), items -> {}, project -> {}, folders -> {}, active::set,
                        (tab, position) -> {}, (items, folder) -> {},
                        (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
                var view = new SwingWorkbench(actions, Map.of()); reference.set(view);
                view.addTab("first", "First", first, noop); view.addTab("second", "Second", second, noop);
                view.frame().setAlwaysOnTop(true);
                view.show(); view.splitTab("first", JSplitPane.VERTICAL_SPLIT); view.frame().validate();
                assertEquals(true, group(first).getClientProperty("JLShell.activeGroup"));
                assertEquals(false, group(second).getClientProperty("JLShell.activeGroup"));
                second.requestFocusInWindow();
            }).get(15, TimeUnit.SECONDS);
            Point body = SwingExecutors.supplyOnEdtAsync(() -> {
                reference.get().frame().validate();
                Point point = second.getLocationOnScreen();
                point.translate(second.getWidth() / 2, Math.min(30, second.getHeight() / 2));
                return point;
            }).get(10, TimeUnit.SECONDS);
            java.awt.Robot robot = new java.awt.Robot();
            robot.mouseMove(body.x, body.y);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            boolean focused = false;
            for (int attempt = 0; attempt < 80; attempt++) {
                focused = SwingExecutors.supplyOnEdtAsync(() -> second.isFocusOwner()
                        && Boolean.TRUE.equals(group(second).getClientProperty("JLShell.activeGroup"))).get(5, TimeUnit.SECONDS);
                if (focused) break;
                Thread.sleep(25);
            }
            assertTrue(focused, "Focusing a split body must activate its tab group");
            SwingExecutors.runOnEdtAsync(() -> {
                assertEquals("second", active.get());
                assertEquals(false, group(first).getClientProperty("JLShell.activeGroup"));
                assertNotEquals(UIManager.getColor("JLShell.tabSelection"), UIManager.getColor("JLShell.tabInactiveSelection"));
                Component header = ((Container) group(first).getTabComponentAt(0)).getComponent(0);
                header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        MouseEvent.BUTTON1_DOWN_MASK, 8, 8, 1, false, MouseEvent.BUTTON1));
                header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 8, 8, 1, false, MouseEvent.BUTTON1));
                assertEquals(true, group(first).getClientProperty("JLShell.activeGroup"));
                assertEquals(false, group(second).getClientProperty("JLShell.activeGroup"));
                assertEquals("first", active.get());
                assertSame(first.getParent(), group(first).getSelectedComponent());
                assertSame(second.getParent(), group(second).getSelectedComponent());
                for (AppTheme theme : List.of(AppTheme.DARK, AppTheme.LIGHT)) {
                    if (theme == AppTheme.LIGHT) reference.get().applyTheme(theme, "#3574f0", "SansSerif", 13);
                    var frame = reference.get().frame();
                    var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); frame.paintAll(graphics); graphics.dispose();
                    var output = java.nio.file.Path.of("target/review/split-tab-focus-" + theme.name().toLowerCase() + ".png");
                    try { java.nio.file.Files.createDirectories(output.getParent()); javax.imageio.ImageIO.write(image, "png", output.toFile()); }
                    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            }).get(15, TimeUnit.SECONDS);
        } finally { if (reference.get() != null) SwingExecutors.runOnEdtAsync(() -> reference.get().close()).get(15, TimeUnit.SECONDS); }
    }

    @Test
    void workspaceDropZonesResolveFourSplitDirectionsAndCenterTransfer() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            JTabbedPane group = new JTabbedPane();
            group.addTab("Session", new JPanel());
            WorkspaceSplitLayout layout = new WorkspaceSplitLayout(group, JTabbedPane::new);
            layout.setSize(800, 500);
            layout.doLayout();
            group.doLayout();
            Rectangle bounds = SwingUtilities.convertRectangle(group.getParent(), group.getBounds(), layout);
            Rectangle content = group.getComponentAt(0).getBounds();
            for (int x : new int[]{2, bounds.width / 2, bounds.width - 2})
                assertEquals(WorkspaceSplitLayout.DropSide.CENTER,
                        layout.dropLocation(new Point(bounds.x + x, bounds.y + content.y / 2)).side(),
                        "The full tab header, including both edges, must never be a split target");
            assertEquals(WorkspaceSplitLayout.DropSide.LEFT,
                    layout.dropLocation(new Point(bounds.x + 2, bounds.y + bounds.height / 2)).side());
            assertEquals(WorkspaceSplitLayout.DropSide.RIGHT,
                    layout.dropLocation(new Point(bounds.x + bounds.width - 2, bounds.y + bounds.height / 2)).side());
            assertEquals(WorkspaceSplitLayout.DropSide.TOP,
                    layout.dropLocation(new Point(bounds.x + bounds.width / 2, bounds.y + content.y + 2)).side());
            assertEquals(WorkspaceSplitLayout.DropSide.BOTTOM,
                    layout.dropLocation(new Point(bounds.x + bounds.width / 2, bounds.y + content.y + content.height - 2)).side());
            assertEquals(WorkspaceSplitLayout.DropSide.CENTER,
                    layout.dropLocation(new Point(bounds.x + bounds.width / 2, bounds.y + content.y + content.height / 2)).side());
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void rightClickTabAndClickPopupActionSplitsTheWorkspace() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<SwingWorkbench>();
        JPanel first = new JPanel();
        Runnable noop = () -> {};
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> fail("Splitting must not create a connection"), items -> {}, project -> {}, folders -> {}, tab -> {},
                        (tab, position) -> {}, (items, folder) -> {},
                        (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
                var view = new SwingWorkbench(actions, Map.of());
                reference.set(view);
                view.addTab("a", "First", first, noop);
                view.addTab("b", "Second", new JPanel(), noop);
                view.show();
                JTabbedPane group = group(first);
                Component label = ((Container) group.getTabComponentAt(0)).getComponent(0);
                label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                        8, 8, 1, true, MouseEvent.BUTTON3));
            }).get(15, TimeUnit.SECONDS);
            // 右键回调在下一轮 EDT 打开弹窗，沿用户的鼠标释放路径执行菜单项。
            SwingExecutors.runOnEdtAsync(() -> {
                Window popup = java.util.Arrays.stream(Window.getWindows())
                        .filter(window -> "idea.popupWindow".equals(window.getName()) && window.isShowing())
                        .findFirst().orElseThrow();
                JList<?> list = findList(popup);
                int split = -1;
                for (int i = 0; i < list.getModel().getSize(); i++) {
                    Object row = list.getModel().getElementAt(i);
                    if (row instanceof com.jlshell.ui.swing.idea.IdeaListPopup.Row item
                            && item.title().equals("tab.splitRight")) { split = i; break; }
                }
                assertTrue(split >= 0, "The tab context menu must expose Split Right");
                Rectangle cell = list.getCellBounds(split, split);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                        cell.x + 12, cell.y + cell.height / 2, 1, false, MouseEvent.BUTTON1));
                assertEquals(2, layout(reference.get().frame()).groups().size());
            }).get(15, TimeUnit.SECONDS);
        } finally {
            if (reference.get() != null) SwingExecutors.runOnEdtAsync(() -> reference.get().close()).get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void draggingAnExistingTabAcrossGroupsCommitsOnceAndKeepsItsView() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<SwingWorkbench>();
        JPanel first = new JPanel(), moving = new JPanel(), remaining = new JPanel();
        var committed = new java.util.ArrayList<String>();
        Runnable noop = () -> {};
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> fail("Dragging must not create a connection"), items -> {}, project -> {}, folders -> {}, tab -> {},
                        (tab, position) -> committed.add(tab + ":" + position), (items, folder) -> {},
                        (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
                var view = new SwingWorkbench(actions, Map.of());
                reference.set(view);
                view.addTab("a", "First", first, noop);
                view.addTab("b", "Moving", moving, noop);
                view.addTab("c", "Remaining", remaining, noop);
                view.show();
                view.splitTab("a", JSplitPane.HORIZONTAL_SPLIT);
            }).get(15, TimeUnit.SECONDS);
            // 让 split 的初始 50/50 布局完成后再发送组件内的拖放事件。
            SwingExecutors.runOnEdtAsync(() -> {
                var view = reference.get();
                view.frame().validate();
                Container originalHost = moving.getParent();
                JTabbedPane source = group(moving), destination = group(first);
                Component label = ((Container) source.getTabComponentAt(source.indexOfComponent(originalHost))).getComponent(0);
                long time = System.currentTimeMillis();
                label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_PRESSED, time, MouseEvent.BUTTON1_DOWN_MASK,
                        8, 8, 1, false, MouseEvent.BUTTON1));
                Point drop = SwingUtilities.convertPoint(destination, destination.getWidth() / 2, destination.getHeight() / 2, label);
                label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_DRAGGED, time + 1, MouseEvent.BUTTON1_DOWN_MASK,
                        drop.x, drop.y, 0, false, MouseEvent.NOBUTTON));
                label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_RELEASED, time + 2, 0,
                        drop.x, drop.y, 1, false, MouseEvent.BUTTON1));
                assertSame(originalHost, moving.getParent());
                assertSame(destination, group(moving));
                assertEquals(1, source.getTabCount());
                assertSame(originalHost, destination.getSelectedComponent());
                assertEquals(List.of("b:2"), committed);
            }).get(15, TimeUnit.SECONDS);
        } finally {
            if (reference.get() != null) SwingExecutors.runOnEdtAsync(() -> reference.get().close()).get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void draggingTabToWorkspaceEdgesCreatesRightAndTopSplits() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<SwingWorkbench>();
        JPanel first = new JPanel(), second = new JPanel();
        Runnable noop = () -> {};
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> fail("Dropping to split must reuse the open session"), items -> {}, project -> {}, folders -> {}, tab -> {},
                        (tab, position) -> {}, (items, folder) -> {},
                        (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
                var view = new SwingWorkbench(actions, Map.of());
                reference.set(view);
                view.addTab("first", "First", first, noop);
                view.addTab("second", "Second", second, noop);
                view.show();
            }).get(15, TimeUnit.SECONDS);

            SwingExecutors.runOnEdtAsync(() -> {
                var view = reference.get();
                view.frame().validate();
                WorkspaceSplitLayout layout = layout(view.frame());
                Component firstHost = first.getParent();
                JTabbedPane initial = group(first);
                int headerY = Math.max(1, initial.getSelectedComponent().getY() / 2);
                for (int x : new int[]{2, initial.getWidth() - 2}) {
                    dragToWorkspacePoint(initial, firstHost, x, headerY);
                    assertEquals(1, layout.groups().size(), "Dragging along either end of the tab header must only reorder");
                    assertSame(initial, group(first));
                    assertSame(firstHost, first.getParent(), "Reordering must preserve the existing terminal host");
                }
                dragToWorkspacePoint(initial, firstHost, initial.getWidth() - 2, initial.getHeight() / 2);
                assertEquals(2, layout.groups().size());
                assertEquals(JSplitPane.HORIZONTAL_SPLIT, ((JSplitPane) group(first).getParent()).getOrientation());
                assertNotSame(group(first), group(second));

                JTabbedPane rightSide = group(first);
                view.frame().validate();
                rightSide.doLayout();
                Rectangle content = firstHost.getBounds();
                Point topPoint = SwingUtilities.convertPoint(rightSide, rightSide.getWidth() / 2, content.y + 40, layout);
                assertEquals(WorkspaceSplitLayout.DropSide.TOP, layout.dropLocation(topPoint).side(),
                        "Expected top-edge point " + topPoint + " using content bounds " + content + " in " + rightSide.getBounds());
                dragToWorkspacePoint(rightSide, firstHost, rightSide.getWidth() / 2, content.y + 40);
                assertEquals(3, layout.groups().size());
                assertEquals(JSplitPane.VERTICAL_SPLIT, ((JSplitPane) group(first).getParent()).getOrientation());
                assertSame(first.getParent(), group(first).getSelectedComponent());
            }).get(15, TimeUnit.SECONDS);
        } finally {
            if (reference.get() != null) SwingExecutors.runOnEdtAsync(() -> reference.get().close()).get(15, TimeUnit.SECONDS);
        }
    }

    private static void dragToWorkspacePoint(JTabbedPane group, Component host, int x, int y) {
        Component label = ((Container) group.getTabComponentAt(group.indexOfComponent(host))).getComponent(0);
        long time = System.currentTimeMillis();
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_PRESSED, time, MouseEvent.BUTTON1_DOWN_MASK,
                8, 8, 1, false, MouseEvent.BUTTON1));
        Point point = SwingUtilities.convertPoint(group, x, y, label);
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_DRAGGED, time + 1, MouseEvent.BUTTON1_DOWN_MASK,
                point.x, point.y, 0, false, MouseEvent.NOBUTTON));
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_RELEASED, time + 2, 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1));
    }

    @Test
    void nestedSplitsMoveAndMergeKeepTerminalBuffersPluginComponentsAndSelectedSession() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            var connections = new AtomicInteger();
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> connections.incrementAndGet(), items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 13, false, 1), TerminalColorScheme.dark());
            var terminal = JlshellJediTermWidget.create(80, 24, settings, key -> key);
            JPanel second = new JPanel();
            JPanel plugin = new JPanel();
            try {
                terminal.getTerminal().writeCharacters("original session buffer 中文");
                var buffer = terminal.getTerminalTextBuffer();
                String screen = buffer.getScreenLines();
                view.addTab("terminal", "Terminal", terminal, noop);
                view.addTab("second", "Second", second, noop);
                view.addTab("plugin", "Plugin", plugin, noop);
                view.show();
                Container originalHost = terminal.getParent();
                WorkspaceSplitLayout layout = layout(view.frame());
                assertEquals(1, layout.groups().size());
                view.splitTab("terminal", JSplitPane.HORIZONTAL_SPLIT);
                assertEquals(2, layout.groups().size());
                assertNotSame(group(second), group(terminal));
                view.selectTab("plugin");
                view.splitTab("plugin", JSplitPane.VERTICAL_SPLIT);
                assertEquals(3, layout.groups().size());
                var parent = (JSplitPane) group(plugin).getParent();
                assertEquals(JSplitPane.VERTICAL_SPLIT, parent.getOrientation());
                layout.changeOrientation(group(plugin));
                assertEquals(JSplitPane.HORIZONTAL_SPLIT, parent.getOrientation());
                view.moveTabToOppositeGroup("plugin");
                assertEquals(2, layout.groups().size());
                assertSame(group(second), group(plugin));
                view.selectTab("terminal");
                view.unsplitTab("terminal", true);
                assertEquals(1, layout.groups().size());
                assertEquals(3, group(terminal).getTabCount());
                assertSame(originalHost, terminal.getParent());
                assertSame(group(terminal), group(plugin));
                assertSame(originalHost, group(terminal).getSelectedComponent());
                assertSame(buffer, terminal.getTerminalTextBuffer());
                assertEquals(screen, buffer.getScreenLines());
                assertSame(view.frame(), SwingUtilities.getWindowAncestor(plugin));
                assertEquals(0, connections.get());
                view.splitTab("terminal", JSplitPane.VERTICAL_SPLIT);
                view.removeTab("terminal");
                assertEquals(1, layout.groups().size(), "Closing the last tab must collapse its empty group");
                assertSame(view.frame(), SwingUtilities.getWindowAncestor(plugin));
            } finally { view.close(); terminal.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test
    void contextCloseActionsAreLimitedToClickedGroupAndSplitRowsReflectCurrentLayout() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            SwingTheme.install(AppTheme.LIGHT, "#3574f0", "SansSerif", 13);
            var closed = new java.util.ArrayList<String>();
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            try {
                view.addTab("a", "Same server", new JPanel(), () -> closed.add("a"));
                view.addTab("b", "Same server", new JPanel(), () -> closed.add("b"));
                view.addTab("c", "Other", new JPanel(), () -> closed.add("c"));
                var initial = view.tabContextRows("a", List.of());
                assertFalse(initial.stream().filter(row -> row.title().equals("tab.unsplit")).findFirst().orElseThrow().enabled());
                assertNotNull(initial.stream().filter(row -> row.title().equals("tab.splitRight")).findFirst().orElseThrow().icon());
                view.splitTab("a", JSplitPane.HORIZONTAL_SPLIT);
                var isolated = view.tabContextRows("a", List.of());
                assertFalse(isolated.stream().filter(row -> row.title().equals("tab.closeOthersInGroup")).findFirst().orElseThrow().enabled());
                var group = view.tabContextRows("b", List.of());
                assertTrue(group.stream().filter(row -> row.title().equals("tab.unsplit")).findFirst().orElseThrow().enabled());
                group.stream().filter(row -> row.title().equals("tab.closeAllInGroup")).findFirst().orElseThrow().action().run();
                assertEquals(List.of("b", "c"), closed);
            } finally { view.close(); }
        }).get(15, TimeUnit.SECONDS);
    }

    private static JTabbedPane group(Component view) {
        return (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, view);
    }

    private static JList<?> findList(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JList<?> list) return list;
            if (child instanceof Container container) {
                JList<?> found = findList(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static WorkspaceSplitLayout layout(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof WorkspaceSplitLayout layout) return layout;
            if (child instanceof Container container) {
                WorkspaceSplitLayout found = layout(container);
                if (found != null) return found;
            }
        }
        return null;
    }
}
