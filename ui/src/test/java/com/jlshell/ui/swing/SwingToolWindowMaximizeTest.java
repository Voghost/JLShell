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
                assertFalse(tag.isShowing(), "Connection selector is only needed while the workspace is filled");
                named(frame, "workspace.maximizeTool", JButton.class).doClick(); frame.validate();
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

    @ParameterizedTest @EnumSource(AppTheme.class)
    void docksEachToolIndependentlyAndRestoresSavedLayout(AppTheme theme) throws Exception {
        Fixture fixture = fixture(false), restoredFixture = fixture(false);
        var saved = new java.util.concurrent.atomic.AtomicReference<String>();
        var workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(theme, "#3574f0", null, 13);
            Runnable noop = () -> {};
            var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                    connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                    (tab, position) -> {}, (items, folder) -> {},
                    (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()),
                    (key, value) -> saved.set(value), noop);
            var view = new SwingWorkbench(actions, Map.of());
            view.addTab("first", "Server", fixture.view, noop);
            view.frame().setSize(1200, 800); view.show();
            return view;
        }).get(10, TimeUnit.SECONDS);
        try {
            SwingExecutors.runOnEdtAsync(() -> {
                fixture.editor.setText("unsaved text");
                fixture.view.selectPage(1, true);
                var frame = workbench.frame(); frame.validate();
                var title = named(frame, "workspace.rightToolTitle", JLabel.class);
                var verticalDivider=named(frame,"workspace.verticalSplit",JSplitPane.class);
                verticalDivider.setDividerLocation(verticalDivider.getHeight()-304); frame.validate();
                JPanel bottomBeforeDrop=named(frame,"monitor.dock",JPanel.class);
                Rectangle bottomBounds=SwingUtilities.convertRectangle(bottomBeforeDrop.getParent(),bottomBeforeDrop.getBounds(),frame.getLayeredPane());
                Point destination=SwingUtilities.convertPoint(bottomBeforeDrop,bottomBeforeDrop.getWidth()*3/4,bottomBeforeDrop.getHeight()/2,title);
                title.dispatchEvent(new java.awt.event.MouseEvent(title, java.awt.event.MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
                title.dispatchEvent(new java.awt.event.MouseEvent(title, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                        System.currentTimeMillis(), java.awt.event.MouseEvent.BUTTON1_DOWN_MASK,
                        destination.x, destination.y, 0, false, java.awt.event.MouseEvent.NOBUTTON));
                Window dragImage = java.util.Arrays.stream(Window.getWindows()).filter(window -> window.isShowing()
                        && "workspace.toolDragFeedback".equals(window.getName())).findFirst().orElseThrow();
                assertFalse(dragImage.getFocusableWindowState());
                JLabel dragName = named(dragImage, "workspace.dragToolName", JLabel.class);
                assertEquals("Files", dragName.getAccessibleContext().getAccessibleName()); assertNotNull(dragName.getIcon());
                assertTrue(dragImage.getWidth() <= 32,"The drag image must remain an icon, rather than a large text card");
                assertEquals(Cursor.MOVE_CURSOR, title.getCursor().getType());
                assertEquals("workspace.moveToolBottomRight", named(java.util.Arrays.stream(Window.getWindows()).filter(w->w.isShowing()&&"workspace.toolDragHint".equals(w.getName())).findFirst().orElseThrow(), "workspace.dragDestination", JLabel.class).getText());
                Rectangle preview=named(frame,"workspace.toolDropPreview",JComponent.class).getBounds();
                assertEquals(bottomBounds.y,preview.y);assertEquals(bottomBounds.height,preview.height);
                assertEquals(bottomBounds.x+bottomBounds.width/2,preview.x);
                JComponent slot=named(frame,"workspace.toolDropSlot",JComponent.class);
                assertTrue(slot.isShowing());assertTrue(slot.getWidth()>=24,"The stripe insertion marker must have icon width: "+slot.getSize());
                captureDrag(frame,"idea-tool-drag-"+theme.name());
                workbench.cancelToolDrag();
                assertFalse(dragImage.isShowing());
                title.dispatchEvent(new java.awt.event.MouseEvent(title, java.awt.event.MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, destination.x, destination.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
                assertEquals(ToolDockState.Anchor.RIGHT, ToolDockState.decode(saved.get()).anchor("files"), "取消拖动不能修改布局");
                title.dispatchEvent(new java.awt.event.MouseEvent(title, java.awt.event.MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
                title.dispatchEvent(new java.awt.event.MouseEvent(title, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                        System.currentTimeMillis(), java.awt.event.MouseEvent.BUTTON1_DOWN_MASK,
                        destination.x, destination.y, 0, false, java.awt.event.MouseEvent.NOBUTTON));
                Window currentImage=java.util.Arrays.stream(Window.getWindows()).filter(w->w.isShowing()&&"workspace.toolDragFeedback".equals(w.getName())).findFirst().orElseThrow();
                JLabel currentIcon=named(currentImage,"workspace.dragToolName",JLabel.class);
                Point absolute=SwingUtilities.convertPoint(title,destination,frame);SwingUtilities.convertPointToScreen(absolute,frame);
                currentIcon.dispatchEvent(new java.awt.event.MouseEvent(currentIcon,java.awt.event.MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(),0,7,7,absolute.x,absolute.y,1,false,java.awt.event.MouseEvent.BUTTON1));
                frame.validate();
                assertFalse(dragImage.isShowing(), "释放后必须清理拖动浮标");
                assertNotEquals(Cursor.MOVE_CURSOR, title.getCursor().getType());
                assertEquals(ToolDockState.Anchor.BOTTOM_RIGHT, ToolDockState.decode(saved.get()).anchor("files"));
                fixture.view.selectPage(3, true); frame.validate();
                assertTrue(fixture.files.isShowing(), "文件应独立保留在底部");
                assertTrue(fixture.editor.isShowing(), "插件仍在右侧同时显示");
                assertTrue(SwingUtilities.isDescendingFrom(fixture.editor, named(frame, "right.toolWindow", JPanel.class)));
                var sourceButton=named(frame,"workspace.stripe."+fixture.view.toolKey(3),JButton.class);
                var firstButton=named(frame,"workspace.stripe.plugins",JButton.class);
                Point beforeFirst=SwingUtilities.convertPoint(firstButton,5,1,sourceButton);
                sourceButton.dispatchEvent(new java.awt.event.MouseEvent(sourceButton,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,5,5,1,false,java.awt.event.MouseEvent.BUTTON1));
                sourceButton.dispatchEvent(new java.awt.event.MouseEvent(sourceButton,java.awt.event.MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),java.awt.event.MouseEvent.BUTTON1_DOWN_MASK,beforeFirst.x,beforeFirst.y,0,false,java.awt.event.MouseEvent.NOBUTTON));
                sourceButton.dispatchEvent(new java.awt.event.MouseEvent(sourceButton,java.awt.event.MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,beforeFirst.x,beforeFirst.y,1,false,java.awt.event.MouseEvent.BUTTON1));
                frame.validate();
                var topStripe=named(frame,"workspace.rightTopStripe",JPanel.class);
                assertEquals(fixture.view.toolKey(3),((JComponent)topStripe.getComponent(0)).getClientProperty("jlshell.toolKey"),"Reorder inside a stripe must match the shown insertion slot");
                var ordered=ToolDockState.decode(saved.get());
                assertTrue(new java.util.ArrayList<>(ordered.positions.keySet()).indexOf(fixture.view.toolKey(3))<new java.util.ArrayList<>(ordered.positions.keySet()).indexOf("plugins"));
                workbench.moveTool(fixture.view.toolKey(3), ToolDockState.Anchor.BOTTOM_LEFT); frame.validate();
                assertTrue(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                JToggleButton monitor = named(frame, "workspace.tool.monitor", JToggleButton.class);
                JToggleButton plugin = named(frame, "workspace.tool." + fixture.view.toolKey(3), JToggleButton.class);
                assertEquals(monitor.getFont(), plugin.getFont());
                assertNotNull(monitor.getIcon()); assertNotNull(plugin.getIcon());
                int closeX=plugin.getWidth()-12, closeY=plugin.getHeight()/2;
                plugin.dispatchEvent(new java.awt.event.MouseEvent(plugin,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,closeX,closeY,1,false,java.awt.event.MouseEvent.BUTTON1));
                plugin.dispatchEvent(new java.awt.event.MouseEvent(plugin,java.awt.event.MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,closeX,closeY,1,false,java.awt.event.MouseEvent.BUTTON1));
                frame.validate();
                assertFalse(fixture.editor.isShowing(), "Closing one tool tab must show the remaining monitor");
                assertTrue(named(frame,"workspace.tool.monitor",JToggleButton.class).isShowing());
                named(frame,"workspace.stripe."+fixture.view.toolKey(3),JButton.class).doClick(); frame.validate();
                assertTrue(fixture.editor.isShowing(), "A closed tool tab must reopen from its stripe");
                assertEquals("unsaved text", fixture.editor.getText(), "Closing the tool tab must retain its contents");
                var state = ToolDockState.decode(saved.get());
                assertEquals(ToolDockState.Anchor.BOTTOM_LEFT, state.anchor(fixture.view.toolKey(3)));
                assertEquals(ToolDockState.Anchor.RIGHT, state.anchor("plugins"));
                var bottomDivider = named(frame,"workspace.bottomSplit",JSplitPane.class);
                bottomDivider.setDividerLocation(.7);
                String persisted = ToolDockState.decode(saved.get()).encode();
                var restoreState=ToolDockState.decode(persisted); restoreState.bottomPercent=70;
                persisted=restoreState.encode();
                workbench.restoreDefaultLayout(); frame.validate();
                assertEquals("unsaved text", fixture.editor.getText());
                assertEquals(ToolDockState.Anchor.RIGHT, ToolDockState.decode(saved.get()).anchor("files"));
                fixture.view.selectPage(3, true); frame.validate(); assertTrue(fixture.editor.isShowing());
                Runnable noop = () -> {};
                var actions = new SwingWorkbench.Actions(noop, noop, noop, noop, noop, noop, noop, noop, noop,
                        connection -> {}, items -> {}, project -> {}, folders -> {}, tab -> {},
                        (tab, position) -> {}, (items, folder) -> {},
                        (item, reply) -> reply.accept(List.of()), (tab, reply) -> reply.accept(List.of()));
                var restarted = new SwingWorkbench(actions, Map.of(ToolDockState.SETTING, persisted));
                try {
                    restarted.addTab("restored", "Server", restoredFixture.view, noop);
                    restarted.show(); restarted.frame().validate();
                    var restoredDivider=named(restarted.frame(),"workspace.bottomSplit",JSplitPane.class);
                    assertEquals(.7,restoredDivider.getDividerLocation()/(double)(restoredDivider.getWidth()-restoredDivider.getDividerSize()),.02,
                            "Reopening must restore the saved left/right bottom proportion");
                    assertTrue(restoredFixture.files.isShowing(), "重新创建工作台后文件恢复到底部右区");
                    named(restarted.frame(), "workspace.tool." + restoredFixture.view.toolKey(3), JToggleButton.class).doClick();
                    restarted.frame().validate(); assertTrue(restoredFixture.editor.isShowing());
                    assertTrue(restoredFixture.files.isShowing());
                } finally { restarted.close(); }

            }).get(15, TimeUnit.SECONDS);
        } finally { SwingExecutors.runOnEdtAsync(workbench::close).get(10, TimeUnit.SECONDS); }
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest @EnumSource(AppTheme.class)
    void fullHeightStripesHideEachDockIndependentlyAndRestoreFromMaximized(AppTheme theme) throws Exception {
        Fixture fixture = fixture(false);
        var saved = new java.util.concurrent.atomic.AtomicReference<String>();
        var workbench = SwingExecutors.supplyOnEdtAsync(() -> {
            SwingTheme.install(theme, "#3574f0", null, 13);
            Runnable noop=()->{};
            var actions=new SwingWorkbench.Actions(noop,noop,noop,noop,noop,noop,noop,noop,noop,
                    connection->{},items->{},project->{},folders->{},tab->{},(tab,position)->{},(items,folder)->{},
                    (item,reply)->reply.accept(List.of()),(tab,reply)->reply.accept(List.of()),(key,value)->saved.set(value),noop);
            var view=new SwingWorkbench(actions,Map.of()); view.addTab("one","Server",fixture.view,noop);
            view.frame().setSize(1200,800); view.show(); return view;
        }).get(10,TimeUnit.SECONDS);
        try {
            SwingExecutors.runOnEdtAsync(()->{
                var frame=workbench.frame();
                fixture.view.selectPage(3,true); fixture.editor.setText("unsaved plugin text");
                workbench.moveTool("files",ToolDockState.Anchor.BOTTOM_RIGHT); frame.validate();
                var left=named(frame,"workspace.leftStripe",JPanel.class);
                var right=named(frame,"workspace.rightStripe",JPanel.class);
                assertEquals(left.getY(),right.getY()); assertEquals(left.getHeight(),right.getHeight());
                var bottom=named(frame,"workspace.bottomRightDock",JPanel.class);
                Point end=SwingUtilities.convertPoint(bottom,0,bottom.getHeight(),frame.getContentPane());
                Point stripeEnd=SwingUtilities.convertPoint(right,0,right.getHeight(),frame.getContentPane());
                assertEquals(end.y,stripeEnd.y,"Right stripe must reach the bottom of the workspace");
                assertTrue(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                JPanel monitorDock=named(frame,"monitor.dock",JPanel.class);
                named(frame,"workspace.hideBottomLeft",JButton.class).doClick(); frame.validate();
                assertFalse(monitorDock.isShowing());
                assertTrue(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                named(frame,"workspace.hideBottomRight",JButton.class).doClick(); frame.validate();
                assertFalse(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                assertFalse(ToolDockState.decode(saved.get()).leftVisible);
                assertFalse(ToolDockState.decode(saved.get()).bottomRightVisible);
                named(frame,"workspace.stripe.monitor",JButton.class).doClick(); frame.validate();
                assertTrue(named(frame,"monitor.dock",JPanel.class).isShowing()); assertFalse(fixture.files.isShowing());
                named(frame,"workspace.stripe.files",JButton.class).doClick(); frame.validate();
                assertTrue(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                assertTrue(named(frame,"workspace.stripe.monitor",JButton.class).isSelected());
                assertTrue(named(frame,"workspace.stripe.files",JButton.class).isSelected());
                named(frame,"workspace.maximizeTool",JButton.class).doClick(); frame.validate();
                assertFalse(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                named(frame,"workspace.stripe.monitor",JButton.class).doClick(); frame.validate();
                assertTrue(fixture.files.isShowing()); assertTrue(fixture.editor.isShowing());
                assertTrue(named(frame,"monitor.dock",JPanel.class).isShowing());
                assertEquals("unsaved plugin text",fixture.editor.getText());
                assertEquals(left.getHeight(),right.getHeight());
                try {
                    var output=java.nio.file.Path.of("target/review/independent-stripes-"+theme.name()+".png");
                    java.nio.file.Files.createDirectories(output.getParent());
                    var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics(); frame.paint(g);g.dispose();javax.imageio.ImageIO.write(image,"png",output.toFile());
                }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
            }).get(15,TimeUnit.SECONDS);
        } finally { SwingExecutors.runOnEdtAsync(workbench::close).get(10,TimeUnit.SECONDS); }
    }

    private static void captureDrag(JFrame frame,String name) {
        try{
            var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();frame.paint(g);
            for(Window window:frame.getOwnedWindows())if(window.isShowing()&&window.getName()!=null&&window.getName().startsWith("workspace.toolDrag")){
                var child=(Graphics2D)g.create();child.translate(window.getX()-frame.getX(),window.getY()-frame.getY());window.paint(child);child.dispose();
            }
            g.dispose();var path=java.nio.file.Path.of("target/review/"+name+".png");java.nio.file.Files.createDirectories(path.getParent());
            javax.imageio.ImageIO.write(image,"png",path.toFile());
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }

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
