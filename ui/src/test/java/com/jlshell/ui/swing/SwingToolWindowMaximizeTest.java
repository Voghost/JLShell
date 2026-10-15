package com.jlshell.ui.swing;

import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.view.PluginsTabView;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingToolWindowMaximizeTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest @EnumSource(AppTheme.class)
    void fillsWorkspaceRestoresLayoutAndRetainsPluginEditorAcrossSessionSwitches(AppTheme theme) throws Exception {
        Fixture first = fixture(false);
        var creates = new java.util.concurrent.atomic.AtomicInteger();
        Fixture second = fixture(true); // 相同插件在不同下标，不能按页码误选其他插件。
        var workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(theme, "#3574f0", null, 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(creates::incrementAndGet, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            view.addTab("first", "First", first.view, noop);
            view.addTab("second", "Second", second.view, noop);
            view.frame().setSize(850, 640); // 窄窗口：旧外层宽度不足以容纳铺满时被拉宽的侧栏。
            view.selectTab("first"); view.show();
            return view;
        }).get(10, TimeUnit.SECONDS);
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                first.view.selectPage(3, true);
                var frame = workbench.frame(); frame.validate();
                var rightSplit = named(frame, "workspace.rightSplit", JSplitPane.class);
                int originalDivider = rightSplit.getDividerLocation();
                Container terminalHost = first.view.getParent();
                first.editor.setText("unsaved plugin text");
                var fill = named(frame, "workspace.maximizeTool", JButton.class);
                var panel = named(frame, "right.toolWindow", JPanel.class);
                fill.doClick(); frame.validate();
                assertEquals("workspace.restoreTool", fill.getToolTipText());
                assertFalse(first.view.isShowing());
                assertFalse(named(frame, "monitor.dock", JPanel.class).isShowing());
                assertTrue(first.editor.isShowing());
                var tree = descendants(frame).stream().filter(JTree.class::isInstance).map(JTree.class::cast).findFirst().orElseThrow();
                assertTrue(tree.isShowing(), "Filling workspace must keep the connection sidebar visible and usable");
                var expandedSplit = named(frame, "workspace.maximizedSplit", JSplitPane.class);
                assertEquals(280, expandedSplit.getDividerLocation());
                assertTrue(panel.getWidth() > (frame.getWidth() - 320) * .85);
                assertTrue(SwingUtilities.convertPoint(panel, 0, 0, frame).x > 280);
                var create = descendants(frame).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> "action.newConnection".equals(button.getToolTipText())).findFirst().orElseThrow();
                assertTrue(create.isShowing() && create.isEnabled());
                create.doClick();
                assertEquals(1, creates.get(), "The connection sidebar must remain interactive while the tool is expanded");
                expandedSplit.setDividerLocation(310); frame.validate();
                assertEquals(310, expandedSplit.getDividerLocation());
                workbench.setSidebarVisible(false); frame.validate();
                assertFalse(tree.isShowing());
                workbench.setSidebarVisible(true); frame.validate();
                assertTrue(tree.isShowing());
                assertEquals(310, expandedSplit.getDividerLocation());
                assertSame(terminalHost, first.view.getParent(), "Terminal and its buffer must stay mounted");
                assertTrue(descendants(frame).stream().filter(JButton.class::isInstance)
                        .map(JButton.class::cast).anyMatch(button -> "workspace.openSessions".equals(button.getToolTipText()) && button.isShowing()));
                workbench.selectTab("second"); frame.validate();
                assertEquals(4, second.view.selectedPageIndex());
                assertTrue(second.editor.isShowing());
                assertEquals("workspace.restoreTool", fill.getToolTipText());
                workbench.selectTab("first"); frame.validate();
                assertTrue(first.editor.isShowing());
                assertEquals("unsaved plugin text", first.editor.getText());
                fill.doClick(); frame.validate();
                assertTrue(first.view.isShowing());
                assertTrue(named(frame, "monitor.dock", JPanel.class).isShowing());
                assertTrue(rightSplit.getDividerLocation() >= originalDivider,
                        "A wider sidebar may require more space for the terminal when restoring");
                assertEquals(310, ((JSplitPane) rightSplit.getLeftComponent()).getDividerLocation(),
                        "Sidebar width changes made while expanded must survive restoring: frame=" + frame.getWidth()
                                + " outer=" + rightSplit.getWidth() + "/" + rightSplit.getDividerLocation()
                                + " left=" + rightSplit.getLeftComponent().getWidth()
                                + " right=" + rightSplit.getRightComponent().getWidth());
                assertEquals("workspace.maximizeTool", fill.getToolTipText());
                first.view.selectPage(1, true); // 文件面板使用相同的铺满/还原入口。
                fill.doClick(); frame.validate();
                assertTrue(first.files.isShowing());
                named(frame, "workspace.toolConnection", JButton.class).doClick();
                assertEquals(2, popupList(frame).getModel().getSize());
                clickRow(popupList(frame), 1); frame.validate();
                assertEquals(1, second.view.selectedPageIndex());
                assertTrue(second.files.isShowing());
                assertEquals("workspace.restoreTool", fill.getToolTipText());
                var hide = descendants(panel).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> "workspace.hideTools".equals(button.getToolTipText())).findFirst().orElseThrow();
                hide.doClick(); frame.validate();
                assertTrue(second.view.isShowing());
                assertFalse(panel.isShowing());
                assertEquals("workspace.maximizeTool", fill.getToolTipText());
                second.view.selectPage(1, true); fill.doClick(); frame.validate();
                assertTrue(second.files.isShowing());
                workbench.removeTab("second"); frame.validate();
                assertTrue(first.files.isShowing());
                workbench.removeTab("first"); frame.validate();
                assertTrue(rightSplit.isShowing(), "Closing the last session must restore the welcome workspace");
                assertNull(rightSplit.getRightComponent());
                assertEquals("workspace.maximizeTool", fill.getToolTipText());
            }).get(15, TimeUnit.SECONDS);
        } finally {
            SwingExecutors.runOnEdtAsync(workbench::close).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @EnumSource(AppTheme.class)
    void connectionTagFiltersOpenedPluginSessionsAndPreservesPagesWhenSwitching(AppTheme theme) throws Exception {
        Fixture first = fixture(false), second = fixture(true), withoutPlugin = fixture(false, false);
        var selected = new java.util.concurrent.atomic.AtomicReference<Object>();
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        var workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(theme, "#3574f0", null, 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> connections.incrementAndGet(), items -> {}, project -> {}, folders -> {}, selected::set,
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            view.addTab("first", "Same server", first.view, noop, "user@192.0.2.1:22");
            view.addTab("second", "Same server", second.view, noop, "user@192.0.2.2:22");
            view.addTab("without", "Other session", withoutPlugin.view, noop, "user@192.0.2.3:22");
            view.selectTab("first"); view.show();
            return view;
        }).get(10, TimeUnit.SECONDS);
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                first.view.selectPage(3, true);
                var frame = workbench.frame(); frame.validate();
                var tag = named(frame, "workspace.toolConnection", JButton.class);
                assertEquals("Same server", tag.getText());
                assertTrue(tag.getToolTipText().contains("192.0.2.1:22"));
                assertTrue(tag.isShowing());
                tag.doClick();
                var list = popupList(frame);
                assertEquals(2, list.getModel().getSize(), "Sessions without this plugin must be excluded");
                assertEquals("user@192.0.2.1:22", list.getModel().getElementAt(0).detail());
                assertEquals("user@192.0.2.2:22", list.getModel().getElementAt(1).detail());
                assertEquals("✓", list.getModel().getElementAt(0).shortcut());
                clickRow(list, 1); frame.validate();
                assertEquals("second", selected.get());
                assertEquals(4, second.view.selectedPageIndex(), "Different plugin order must match by ID");
                assertTrue(second.editor.isShowing());
                assertTrue(tag.getToolTipText().contains("192.0.2.2:22"));
                second.editor.setText("second editor unsaved");
                named(frame, "workspace.maximizeTool", JButton.class).doClick(); frame.validate();
                tag.doClick(); clickRow(popupList(frame), 0); frame.validate();
                assertEquals("first", selected.get());
                assertTrue(first.editor.isShowing());
                assertTrue(tag.getToolTipText().contains("192.0.2.1:22"));
                tag.doClick(); clickRow(popupList(frame), 1); frame.validate();
                assertEquals("second editor unsaved", second.editor.getText());
                tag.doClick(); clickRow(popupList(frame), 0); frame.validate();
                tag.doClick(); // 留着弹窗，模拟另一个会话的插件在后台关闭。
                assertEquals(2, popupList(frame).getModel().getSize());
                assertEquals(0, connections.get(), "The tag must switch an existing session, never reconnect");
            }).get(15, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> {
                second.model.getTabs().removeLast();
                second.view.publishPages();
                return null;
            }).get(10, TimeUnit.SECONDS);
            SwingExecutors.runOnEdtAsync(() -> {
                var frame = workbench.frame();
                clickRow(popupList(frame), 1); frame.validate();
                assertEquals("first", selected.get(), "A closed plugin in a stale popup cannot switch to another tool");
                assertTrue(first.editor.isShowing());
                var tag = named(frame, "workspace.toolConnection", JButton.class);
                tag.doClick();
                assertEquals(1, popupList(frame).getModel().getSize());
            }).get(10, TimeUnit.SECONDS);
        } finally {
            SwingExecutors.runOnEdtAsync(workbench::close).get(10, TimeUnit.SECONDS);
            FxThread.supplyAsync(() -> null).get(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static JList<com.jlshell.ui.swing.idea.IdeaListPopup.Row> popupList(JFrame frame) {
        return (JList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>) java.util.Arrays.stream(frame.getOwnedWindows())
                .filter(Window::isShowing).flatMap(window -> descendants(window).stream())
                .filter(JList.class::isInstance).findFirst().orElseThrow();
    }

    private static void clickRow(JList<?> list, int index) {
        Rectangle cell = list.getCellBounds(index, index);
        list.dispatchEvent(new java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, cell.x + 5, cell.y + cell.height / 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    private record Fixture(SwingSessionView view, JTextArea editor, JPanel files, TabPane model) {}

    private static Fixture fixture(boolean extraPlugin) throws Exception { return fixture(extraPlugin, true); }

    private static Fixture fixture(boolean extraPlugin, boolean hasPlugin) throws Exception {
        JTextArea editor = SwingExecutors.supplyOnEdtAsync(JTextArea::new).get(10, TimeUnit.SECONDS);
        var model = FxThread.supplyAsync(() -> {
            var tabs = new TabPane(new Tab("Terminal"), new Tab("Files"), new Tab("Plugins"));
            if (extraPlugin) tabs.getTabs().add(new Tab("Other plugin"));
            var plugin = new Tab("Custom plugin", new Pane());
            plugin.getProperties().put("pluginId", "test.editor");
            plugin.getProperties().put(PluginsTabView.NATIVE_VIEW, (java.util.function.Supplier<JComponent>) () -> editor);
            if (hasPlugin) tabs.getTabs().add(plugin);
            return tabs;
        }).get(10, TimeUnit.SECONDS);
        var fixture = SwingExecutors.supplyOnEdtAsync(() -> {
            var view = new SwingSessionView(model, null, "Monitor", null, "Settings", new I18nService(Locale.ENGLISH));
            var files = new JPanel();
            ((JPanel) view.rightToolWindow()).add(files, "files"); // 文件网络 I/O 与工作台布局独立。
            return new Fixture(view, editor, files, model);
        }).get(10, TimeUnit.SECONDS);
        FxThread.supplyAsync(() -> { fixture.view.publishPages(); return null; }).get(10, TimeUnit.SECONDS);
        SwingExecutors.runOnEdtAsync(() -> {}).get(10, TimeUnit.SECONDS);
        return fixture;
    }

    private static <T extends Component> T named(Container parent, String name, Class<T> type) {
        return descendants(parent).stream().filter(type::isInstance).map(type::cast)
                .filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }
    private static List<Component> descendants(Container parent) {
        var result = new java.util.ArrayList<Component>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }
}
