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
        Fixture second = fixture(true); // 相同插件在不同下标，不能按页码误选其他插件。
        var workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(theme, "#3574f0", null, 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
            var view = new SwingWorkbench(actions, Map.of());
            view.addTab("first", "First", first.view, noop);
            view.addTab("second", "Second", second.view, noop);
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
                assertTrue(panel.getWidth() > frame.getWidth() * .85);
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
                assertEquals(originalDivider, rightSplit.getDividerLocation());
                assertEquals("workspace.maximizeTool", fill.getToolTipText());
                first.view.selectPage(1, true); // 文件面板使用相同的铺满/还原入口。
                fill.doClick(); frame.validate();
                assertTrue(first.files.isShowing());
                workbench.selectTab("second"); frame.validate();
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

    private record Fixture(SwingSessionView view, JTextArea editor, JPanel files) {}

    private static Fixture fixture(boolean extraPlugin) throws Exception {
        JTextArea editor = SwingExecutors.supplyOnEdtAsync(JTextArea::new).get(10, TimeUnit.SECONDS);
        var model = FxThread.supplyAsync(() -> {
            var tabs = new TabPane(new Tab("Terminal"), new Tab("Files"), new Tab("Plugins"));
            if (extraPlugin) tabs.getTabs().add(new Tab("Other plugin"));
            var plugin = new Tab("Custom plugin", new Pane());
            plugin.getProperties().put("pluginId", "test.editor");
            plugin.getProperties().put(PluginsTabView.NATIVE_VIEW, (java.util.function.Supplier<JComponent>) () -> editor);
            tabs.getTabs().add(plugin);
            return tabs;
        }).get(10, TimeUnit.SECONDS);
        var fixture = SwingExecutors.supplyOnEdtAsync(() -> {
            var view = new SwingSessionView(model, null, "Monitor", null, "Settings", new I18nService(Locale.ENGLISH));
            var files = new JPanel();
            ((JPanel) view.rightToolWindow()).add(files, "files"); // 文件网络 I/O 与工作台布局独立。
            return new Fixture(view, editor, files);
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
