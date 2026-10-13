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
