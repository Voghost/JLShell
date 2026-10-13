package com.jlshell.ui.swing;

import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.view.SessionWorkspaceTab;
import com.jlshell.ui.view.TerminalWorkspaceView;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/** 原生 Swing 终端；文件及插件页仍使用原始 JavaFX 视图和会话生命周期。 */
public final class SwingSessionView extends JPanel implements NativeTerminalHost, AutoCloseable {
    private final JPanel terminal = new JPanel(new BorderLayout());
    private final JPanel terminalBody = new JPanel(new BorderLayout());
    private final JPanel navigation = new JPanel();
    private final JPanel monitoring = new JPanel(new BorderLayout());
    private final String monitoringTitle;
    private IntConsumer showToolPage = index -> {};
    private final SwingFxPanel compatibility;
    private final ThemeService themes;
    private TerminalWorkspaceView terminalView;
    private final String terminalSettingsLabel;
    private final String pluginSettingsLabel;
    private final TabPane model;
    private SwingFxPanel toolbar;
    private Node toolbarNode;
    private ChangeListener<Boolean> toolbarVisibilityListener;
    private SwingFxPanel status;
    private TerminalViewHandle handle;
    private ChangeListener<Tab> selectionListener;
    private ChangeListener<Node> terminalViewListener;
    private javafx.collections.ListChangeListener<Tab> tabListListener;
    private final java.util.Map<Tab, ChangeListener<String>> titleListeners = new java.util.IdentityHashMap<>();
    private boolean closed;
    private volatile int selectedPageIndex;
    private record Page(Tab tab, String title) {}

    /** 从 FX 线程调用。不会同步等待 EDT，也不会改变插件返回的 Node。 */
    public static CompletableFuture<SwingSessionView> create(SessionWorkspaceTab source, ThemeService themes, I18nService i18n) {
        TabPane model = source.getInnerTabPane();
        model.getStyleClass().add("native-session-tabs");
        source.setContent(null);
        TerminalWorkspaceView initialTerminalView = source.getTerminalWorkspaceView();
        var future = SwingExecutors.supplyOnEdtAsync(() -> new SwingSessionView(model, themes,
                i18n.get("workspace.monitoring"), initialTerminalView,
                i18n.get("terminal.appearanceSettings"),
                i18n.get("plugin.pinToToolbar") + "…"));
        NativeTerminalHost host = new NativeTerminalHost() {
            public void attach(TerminalViewHandle handle) { future.thenAccept(view -> view.attach(handle)); }
            public void disconnected(String reason, Runnable reconnect) {
                future.thenAccept(view -> view.disconnected(reason, reconnect));
            }
        };
        java.util.function.Consumer<TerminalWorkspaceView> configure = terminalView -> {
            terminalView.setNativeTerminalHost(host);
            Node top = terminalView.takeNativeToolbar();
            Node bottom = terminalView.takeNativeStatusBar();
            boolean toolbarVisible = top != null && top.isVisible();
            future.thenAccept(view -> SwingUtilities.invokeLater(() -> {
                view.terminalView = terminalView;
                view.installChrome(top, bottom, toolbarVisible);
            }));
        };
        configure.accept(initialTerminalView);
        ChangeListener<Node> viewListener = (obs, oldNode, node) -> {
            if (node instanceof TerminalWorkspaceView terminalView) configure.accept(terminalView);
        };
        model.getTabs().getFirst().contentProperty().addListener(viewListener);
        ChangeListener<Tab> selection = (obs, oldTab, tab) -> {
            int index = model.getTabs().indexOf(tab);
            future.thenAccept(view -> SwingUtilities.invokeLater(() -> view.selectPage(index, true)));
        };
        model.getSelectionModel().selectedItemProperty().addListener(selection);
        future.thenAccept(view -> {
            view.terminalViewListener = viewListener;
            view.selectionListener = selection;
            view.compatibility.setView(model);
            FxThread.run(() -> {
                model.applyCss();
                source.setTabHeadersVisible(false);
                view.tabListListener = change -> view.publishPages();
                model.getTabs().addListener(view.tabListListener);
                view.publishPages();
            });
        });
        return future;
    }

    SwingSessionView(TabPane model, ThemeService themes, String monitoringTitle,
                     TerminalWorkspaceView terminalView, String terminalSettingsLabel, String pluginSettingsLabel) {
        super(new BorderLayout());
        this.themes = themes;
        this.model = model;
        this.monitoringTitle = monitoringTitle;
        this.terminalView = terminalView;
        this.terminalSettingsLabel = terminalSettingsLabel;
        this.pluginSettingsLabel = pluginSettingsLabel;
        compatibility = new SwingFxPanel(themes);
        terminalBody.add(new JLabel("…", JLabel.CENTER));
        terminal.add(terminalBody, BorderLayout.CENTER);
        // 主终端始终可见，不再从 CardLayout 移出或被文件/插件页覆盖。
        add(terminal, BorderLayout.CENTER);
        navigation.setLayout(new javax.swing.BoxLayout(navigation, javax.swing.BoxLayout.Y_AXIS));
        navigation.setOpaque(false);
    }

    private void selectPage(int index, boolean reveal) {
        if (closed || index < 0) return;
        selectedPageIndex = index;
        int navigationIndex = (index - 1) * 2;
        if (navigationIndex >= 0 && navigationIndex < navigation.getComponentCount()
                && navigation.getComponent(navigationIndex) instanceof JToggleButton selector) {
            selector.setSelected(true);
        }
        if (reveal) showToolPage.accept(index);
        navigation.revalidate(); navigation.repaint();
    }

    /** 底部工具窗只保留会话监控；文件和插件页交由右侧工具窗显示。 */
    public JPanel toolWindow() { return monitoring; }

    java.awt.Component rightToolWindow() { return compatibility; }

    int selectedPageIndex() { return selectedPageIndex; }

    String selectedPageTitle() {
        int index = selectedPageIndex;
        return index >= 0 && index < model.getTabs().size() ? model.getTabs().get(index).getText() : "";
    }

    /** 与会话工具标题合并显示，避免内容区重复占用一整行导航空间。 */
    JPanel toolNavigation() { return navigation; }

    JPanel monitorNavigation() {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        item.setOpaque(false);
        item.add(new JLabel(monitoringTitle, new WorkbenchIcon(WorkbenchIcon.Kind.MONITOR), JLabel.LEFT));
        return item;
    }

    public void setToolPageAction(IntConsumer action) {
        showToolPage = action == null ? index -> {} : action;
    }

    /** 插件可动态 openTab/closeTab/updateTabTitle，必须同步原有 Tab 模型。FX 线程调用。 */
    private void publishPages() {
        titleListeners.entrySet().removeIf(entry -> {
            if (model.getTabs().contains(entry.getKey())) return false;
            entry.getKey().textProperty().removeListener(entry.getValue());
            return true;
        });
        for (Tab tab : model.getTabs()) {
            if (!titleListeners.containsKey(tab)) {
                ChangeListener<String> listener = (obs, oldTitle, title) -> publishPages();
                tab.textProperty().addListener(listener);
                titleListeners.put(tab, listener);
            }
        }
        List<Page> pages = model.getTabs().stream().map(tab -> new Page(tab, tab.getText())).toList();
        int selected = model.getSelectionModel().getSelectedIndex();
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            navigation.removeAll();
            ButtonGroup group = new ButtonGroup();
            for (int index = 1; index < pages.size(); index++) {
                Page page = pages.get(index);
                int pageIndex = index;
                WorkbenchIcon.Kind iconKind = index == 1 ? WorkbenchIcon.Kind.FOLDER
                        : index == 2 ? WorkbenchIcon.Kind.PLUGIN : WorkbenchIcon.Kind.LAYOUT;
                JToggleButton selector = new JToggleButton(new WorkbenchIcon(iconKind));
                selector.putClientProperty("JButton.buttonType", "toolBarButton");
                selector.setFocusable(false);
                selector.setPreferredSize(new Dimension(30, 30));
                selector.setMaximumSize(new Dimension(30, 30));
                selector.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                selector.setToolTipText(page.title());
                selector.getAccessibleContext().setAccessibleName(page.title());
                selector.addActionListener(event -> {
                    boolean alreadySelected = model.getSelectionModel().getSelectedItem() == page.tab();
                    FxThread.run(() -> model.getSelectionModel().select(page.tab()));
                    if (alreadySelected) showToolPage.accept(pageIndex);
                });
                group.add(selector);
                navigation.add(selector);
                navigation.add(javax.swing.Box.createVerticalStrut(6));
            }
            selectPage(selected, false);
            navigation.revalidate();
            navigation.repaint();
        });
    }

    private void installChrome(Node top, Node bottom, boolean toolbarVisible) {
        if (closed) return;
        if (toolbarNode != null && toolbarVisibilityListener != null) {
            Node previous = toolbarNode;
            ChangeListener<Boolean> listener = toolbarVisibilityListener;
            FxThread.run(() -> previous.visibleProperty().removeListener(listener));
            toolbarNode = null;
            toolbarVisibilityListener = null;
        }
        if (toolbar != null) { terminal.remove(toolbar); toolbar.close(); }
        if (status != null) { monitoring.remove(status); status.close(); }
        if (top != null) {
            toolbar = new SwingFxPanel(themes);
            toolbar.setPreferredSize(new Dimension(100, toolbarVisible ? 32 : 0));
            toolbar.setView(top);
            terminal.add(toolbar, BorderLayout.NORTH);
            SwingFxPanel panel = toolbar;
            toolbarNode = top;
            toolbarVisibilityListener = (obs, wasVisible, visible) -> SwingUtilities.invokeLater(() -> {
                if (closed || toolbar != panel) return;
                panel.setPreferredSize(new Dimension(100, visible ? 32 : 0));
                terminal.revalidate();
            });
            ChangeListener<Boolean> listener = toolbarVisibilityListener;
            FxThread.run(() -> top.visibleProperty().addListener(listener));
        }
        if (bottom != null) {
            status = new SwingFxPanel(themes);
            SwingFxPanel monitorPanel = status;
            // 保留资源采样、悬浮明细和原事件；只调整 UI 布局位置。
            FxThread.run(() -> {
                var content = new javafx.scene.layout.BorderPane();
                content.setTop(bottom);
                monitorPanel.setView(content);
            });
            monitoring.add(status, BorderLayout.CENTER);
        }
        revalidate();
    }

    public void attach(TerminalViewHandle viewHandle) {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            handle = viewHandle;
            terminalBody.removeAll();
            javax.swing.JComponent component = viewHandle.component();
            TerminalPopupBridge.install(component, terminalSettingsLabel,
                    () -> FxThread.run(terminalView::openFontSettings), pluginSettingsLabel,
                    () -> FxThread.run(terminalView::showPluginPicker));
            component.setVisible(true);
            terminal.setVisible(true);
            terminalBody.add(component, BorderLayout.CENTER);
            terminalBody.revalidate();
            terminalBody.repaint();
            viewHandle.requestFocus();
        });
    }

    public void disconnected(String reason, Runnable reconnect) {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            handle = null;
            terminalBody.removeAll();
            JPanel message = new JPanel(new java.awt.GridBagLayout());
            JPanel actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 12));
            actions.add(new JLabel(reason, JLabel.CENTER));
            JButton retry = new JButton("重新连接 / Reconnect");
            retry.addActionListener(event -> { retry.setEnabled(false); FxThread.run(reconnect); });
            actions.add(retry);
            message.add(actions);
            terminalBody.add(message);
            terminalBody.revalidate();
            terminalBody.repaint();
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        handle = null;
        showToolPage = index -> {};
        terminalBody.removeAll();
        compatibility.close();
        if (toolbarNode != null && toolbarVisibilityListener != null) {
            Node node = toolbarNode;
            ChangeListener<Boolean> listener = toolbarVisibilityListener;
            FxThread.run(() -> node.visibleProperty().removeListener(listener));
        }
        if (toolbar != null) toolbar.close();
        if (status != null) status.close();
        FxThread.run(() -> {
            model.getTabs().removeListener(tabListListener);
            titleListeners.forEach((tab, listener) -> tab.textProperty().removeListener(listener));
            titleListeners.clear();
            model.getSelectionModel().selectedItemProperty().removeListener(selectionListener);
            if (!model.getTabs().isEmpty()) model.getTabs().getFirst().contentProperty().removeListener(terminalViewListener);
        });
    }
}
