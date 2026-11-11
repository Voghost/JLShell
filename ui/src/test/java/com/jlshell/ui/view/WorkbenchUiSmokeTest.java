package com.jlshell.ui.view;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.swing.SwingWorkbench;
import com.jlshell.ui.swing.SwingTheme;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.terminal.support.SwingExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class WorkbenchUiSmokeTest {
    static SwingWorkbench workbench() {
        SwingTheme.install(AppTheme.DARK, "#3574f0", null, 13);
        Runnable noop = () -> {};
        var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                profile -> {}, items -> {}, project -> {}, ids -> {}, tab -> {}, (tab, position) -> {},
                (items, folder) -> {}, (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
        return new SwingWorkbench(actions, SwingWorkbench.TEXT_KEYS.stream().collect(Collectors.toMap(key -> key, key -> key)));
    }
    static <T> T field(Object source, String name, Class<T> type) {
        try { var field = source.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(source)); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    @Test
    void largeNativeConnectionTreeFiltersAndReusesCellRenderer() throws Exception {
        SwingWorkbench view = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingWorkbench workbench = workbench();
            var profiles = IntStream.range(0, 4000).mapToObj(index -> new ConnectionProfile("conn-" + index,
                    "开发服务器 " + index, "192.0.2.10", 22, "developer", null, null, null, null,
                    false, null, ConnectionType.SSH, "folder", null)).toList();
            workbench.updateConnections(List.of(new FolderProfile("folder", "服务器", null, null, 0)), profiles, Set.of());
            workbench.show();
            return workbench;
        }).get(15, java.util.concurrent.TimeUnit.SECONDS);
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                JTree tree = field(view, "tree", JTree.class);
                assertEquals(4001, tree.getRowCount());
                assertTrue(tree.getRowBounds(0).height >= tree.getFontMetrics(tree.getFont()).getHeight());
                assertTrue(tree.getComponentCount() < 50);
                field(view, "search", JTextField.class).setText("3999");
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            awaitRows(view, 2);
            SwingExecutors.runOnEdtAsync(() -> field(view, "search", JTextField.class).setText(""))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            awaitRows(view, 4001);
            SwingExecutors.runOnEdtAsync(() -> {
                JTree tree = field(view, "tree", JTree.class);
                tree.scrollRowToVisible(3500);
                assertTrue(tree.getComponentCount() < 50);
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            SwingExecutors.runOnEdtAsync(view::close).get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void awaitRows(SwingWorkbench view, int expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int rows = SwingExecutors.supplyOnEdtAsync(() -> field(view, "tree", JTree.class).getRowCount())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (rows == expected) return;
            Thread.sleep(25);
        }
        fail("Connection search did not produce " + expected + " rows");
    }
    @Test void themeChangesKeepNativePluginTabsAndActions() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var view = workbench();
            var clicks = new java.util.concurrent.atomic.AtomicInteger();
            var tabs = new JTabbedPane(); var button = new JButton("插件动作"); button.addActionListener(event -> clicks.incrementAndGet());
            tabs.addTab("插件自己的页", button);
            try {
                Object id = new Object(); view.addTab(id, "插件", tabs, () -> {}); view.show();
                for (AppTheme theme : List.of(AppTheme.DARK, AppTheme.LIGHT, AppTheme.DARK)) {
                    view.applyTheme(theme, "#3574f0", null, 13);
                    assertSame(button, tabs.getComponentAt(0)); assertTrue(button.isShowing()); button.doClick();
                }
                assertEquals(3, clicks.get());
            } finally { view.close(); }
        }).get(15, java.util.concurrent.TimeUnit.SECONDS);
    }
}
