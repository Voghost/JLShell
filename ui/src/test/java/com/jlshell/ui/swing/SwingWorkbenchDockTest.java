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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真正挂载同一个终端；确保工具窗显隐、排序和切换插件页不替换主终端及缓冲区。 */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class SwingWorkbenchDockTest {
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
