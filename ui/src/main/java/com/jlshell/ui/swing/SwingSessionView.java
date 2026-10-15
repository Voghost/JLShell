package com.jlshell.ui.swing;

import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.view.SessionWorkspaceTab;
import com.jlshell.ui.view.TerminalWorkspaceView;
import com.jlshell.ui.view.PluginsTabView;
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

/** 原生 Swing 终端、文件、监控与插件页；旧第三方 Node 按需兼容。 */
public final class SwingSessionView extends JPanel implements NativeTerminalHost, AutoCloseable {
    private final JPanel terminal = new JPanel(new BorderLayout());
    private final JPanel terminalBody = new JPanel(new BorderLayout());
    private final JPanel navigation = new JPanel();
    private final JPanel monitoring = new JPanel(new BorderLayout());
    private final String monitoringTitle;
    private IntConsumer showToolPage = index -> {};
    private final I18nService i18n;
    private final java.util.Map<Tab, SwingPluginPage.Prepared> preparedPages = new java.util.IdentityHashMap<>();
    private final java.util.Map<Tab, SwingPluginPage> pluginPages = new java.util.IdentityHashMap<>();
    private final java.util.Map<Tab, ChangeListener<Node>> contentListeners = new java.util.IdentityHashMap<>();
    private java.util.Map<Tab, SwingPluginPage.Prepared> nativePrepared = java.util.Map.of();
    private List<Tab> pageTabs = List.of();
    private List<String> pageTitles = List.of();
    private final JPanel rightHost = new JPanel(new java.awt.CardLayout());
    private java.util.function.Supplier<SwingSftpBrowser> filesFactory;
    private SwingSftpBrowser files;
    private final ThemeService themes;
    private TerminalWorkspaceView terminalView;
    private final String terminalSettingsLabel;
    private final TabPane model;
    private SwingServerMonitor status;
    private TerminalViewHandle handle;
    private ChangeListener<Tab> selectionListener;
    private ChangeListener<Node> terminalViewListener;
    private javafx.collections.ListChangeListener<Tab> tabListListener;
    private final java.util.Map<Tab, ChangeListener<String>> titleListeners = new java.util.IdentityHashMap<>();
    private boolean closed;
    private volatile int selectedPageIndex;
    private record Page(Tab tab, String title, String pluginId) {}
    private javafx.collections.ObservableList<PluginsTabView.PinnedShortcut> pinnedShortcuts;
    private javafx.collections.ListChangeListener<PluginsTabView.PinnedShortcut> pinnedListener;

    /** 从 FX 线程调用。不会同步等待 EDT，也不会改变插件返回的 Node。 */
    public static CompletableFuture<SwingSessionView> create(SessionWorkspaceTab source, ThemeService themes, I18nService i18n) {
        TabPane model = source.getInnerTabPane();
        model.getStyleClass().add("native-session-tabs");
        source.setContent(null);
        TerminalWorkspaceView initialTerminalView = source.getTerminalWorkspaceView();
        var future = SwingExecutors.supplyOnEdtAsync(() -> new SwingSessionView(model, themes,
                i18n.get("workspace.monitoring"), initialTerminalView,
                i18n.get("terminal.appearanceSettings"), i18n));
        NativeTerminalHost host = new NativeTerminalHost() {
            public void attach(TerminalViewHandle handle) { future.thenAccept(view -> view.attach(handle)); }
            public void disconnected(String reason, Runnable reconnect) {
                future.thenAccept(view -> view.disconnected(reason, reconnect));
            }
        };
        java.util.function.Consumer<TerminalWorkspaceView> configure = terminalView -> {
            terminalView.setNativeTerminalHost(host);
            terminalView.takeNativeStatusBar();
            var monitorFactory = SwingServerMonitor.prepare(terminalView, i18n);
            var filesFactory = source.prepareNativeFiles();
            future.thenAccept(view -> SwingUtilities.invokeLater(() -> {
                view.installFiles(filesFactory);
                view.terminalView = terminalView;
                view.installChrome(monitorFactory);
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
            FxThread.run(() -> {
                model.applyCss();
                source.setTabHeadersVisible(false);
                view.tabListListener = change -> view.publishPages();
                model.getTabs().addListener(view.tabListListener);
                for (Tab tab : model.getTabs()) {
                    if (tab.getProperties().get(PluginsTabView.PINNED_SHORTCUTS) instanceof javafx.collections.ObservableList<?> shortcuts) {
                        @SuppressWarnings("unchecked")
                        var typed = (javafx.collections.ObservableList<PluginsTabView.PinnedShortcut>) shortcuts;
                        view.pinnedShortcuts = typed;
                        view.pinnedListener = change -> view.publishPages();
                        typed.addListener(view.pinnedListener);
                    }
                }
                view.publishPages();
            });
        });
        return future;
    }

    SwingSessionView(TabPane model, ThemeService themes, String monitoringTitle,
                     TerminalWorkspaceView terminalView, String terminalSettingsLabel, I18nService i18n) {
        super(new BorderLayout());
        this.themes = themes;
        this.i18n = i18n;
        this.model = model;
        this.monitoringTitle = monitoringTitle;
        this.terminalView = terminalView;
        this.terminalSettingsLabel = terminalSettingsLabel;

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
        if (index == 1 && files == null && filesFactory != null) {
            files = filesFactory.get();
            rightHost.add(files, "files");
        }
        if (index >= 2 && index < pageTabs.size()) {
            Tab tab = pageTabs.get(index);
            if (!pluginPages.containsKey(tab)) {
                var prepared = nativePrepared.get(tab);
                if (prepared != null) {
                    var page = prepared.factory().get(); pluginPages.put(tab, page);
                    rightHost.add(page, "plugin-" + System.identityHashCode(tab));
                }
            }
            ((java.awt.CardLayout) rightHost.getLayout()).show(rightHost, "plugin-" + System.identityHashCode(tab));
        } else if (index == 1) ((java.awt.CardLayout) rightHost.getLayout()).show(rightHost, "files");
        for (java.awt.Component item : navigation.getComponents()) {
            if (item instanceof JToggleButton selector) {
                Object target = selector.getClientProperty("jlshell.pageIndex");
                selector.setSelected(target instanceof Integer page && page == index);
            }
        }
        if (reveal) showToolPage.accept(index);
        navigation.revalidate(); navigation.repaint();
    }

    /** 底部工具窗只保留会话监控；文件和插件页交由右侧工具窗显示。 */
    public JPanel toolWindow() { return monitoring; }

    java.awt.Component rightToolWindow() { return rightHost; }

    private void installFiles(java.util.function.Supplier<SwingSftpBrowser> factory) {
        if (closed) return;
        if (files != null) { rightHost.remove(files); files.close(); files = null; }
        filesFactory = factory;
        selectPage(selectedPageIndex, false);
    }

    int selectedPageIndex() { return selectedPageIndex; }

    String selectedPageTitle() {
        int index = selectedPageIndex;
        return index >= 0 && index < pageTitles.size() ? pageTitles.get(index) : "";
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
        for (Tab tab : model.getTabs()) if (!contentListeners.containsKey(tab)) {
            ChangeListener<Node> listener = (obs, old, next) -> {
                var prepared = preparedPages.remove(tab); if (prepared != null) prepared.releaseModel().run();
                SwingUtilities.invokeLater(() -> {
                    var page = pluginPages.remove(tab); if (page != null) { rightHost.remove(page); page.close(); }
                });
                publishPages();
            };
            tab.contentProperty().addListener(listener); contentListeners.put(tab, listener);
        }
        contentListeners.entrySet().removeIf(entry -> {
            if (model.getTabs().contains(entry.getKey())) return false;
            entry.getKey().contentProperty().removeListener(entry.getValue()); return true;
        });
        preparedPages.entrySet().removeIf(entry -> {
            if (model.getTabs().contains(entry.getKey())) return false;
            entry.getValue().releaseModel().run(); return true;
        });
        for (int index = 2; index < model.getTabs().size(); index++) {
            Tab tab = model.getTabs().get(index);
            preparedPages.computeIfAbsent(tab, key -> SwingPluginPage.prepare(key, i18n, themes));
        }
        List<Tab> tabs = List.copyOf(model.getTabs());
        var prepared = new java.util.IdentityHashMap<>(preparedPages);
        List<Page> pages = model.getTabs().stream().map(tab -> new Page(tab, tab.getText(), (String) tab.getProperties().get("pluginId"))).toList();
        int selected = model.getSelectionModel().getSelectedIndex();
        List<PluginsTabView.PinnedShortcut> shortcuts = pinnedShortcuts == null ? List.of() : List.copyOf(pinnedShortcuts);
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            pageTabs = tabs;
            pageTitles = pages.stream().map(Page::title).toList();
            pluginPages.forEach((tab, page) -> { int index = tabs.indexOf(tab); if (index >= 0) page.updateTitle(pageTitles.get(index)); });
            // 使用独立快照，EDT 不遍历 FX 集合。
            nativePrepared = prepared;
            pluginPages.entrySet().removeIf(entry -> {
                if (tabs.contains(entry.getKey())) return false;
                rightHost.remove(entry.getValue()); entry.getValue().close(); return true;
            });
            navigation.removeAll();
            ButtonGroup group = new ButtonGroup();
            for (int index = 1; index < Math.min(3, pages.size()); index++) {
                Page page = pages.get(index);
                int pageIndex = index;
                WorkbenchIcon.Kind iconKind = index == 1 ? WorkbenchIcon.Kind.FOLDER
                        : index == 2 ? WorkbenchIcon.Kind.PLUGIN : WorkbenchIcon.Kind.LAYOUT;
                JToggleButton selector = new JToggleButton(new WorkbenchIcon(iconKind));
                selector.putClientProperty("JButton.buttonType", "toolBarButton");
                selector.putClientProperty("jlshell.pageIndex", index);
                selector.setFocusable(false);
                selector.setPreferredSize(new Dimension(30, 30));
                selector.setMaximumSize(new Dimension(30, 30));
                selector.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                selector.setToolTipText(page.title());
                selector.getAccessibleContext().setAccessibleName(page.title());
                selector.addActionListener(event -> {
                    boolean alreadySelected = selectedPageIndex == pageIndex;
                    FxThread.run(() -> model.getSelectionModel().select(page.tab()));
                    if (alreadySelected) showToolPage.accept(pageIndex);
                });
                group.add(selector);
                navigation.add(selector);
                navigation.add(javax.swing.Box.createVerticalStrut(6));
            }
            for (PluginsTabView.PinnedShortcut shortcut : shortcuts) {
                JToggleButton selector = new JToggleButton(new WorkbenchIcon(WorkbenchIcon.Kind.PLUGIN));
                selector.setName("plugin-shortcut-" + shortcut.id());
                selector.putClientProperty("JButton.buttonType", "toolBarButton");
                int openedIndex = -1;
                for (int i = 0; i < pages.size(); i++) if (shortcut.id().equals(pages.get(i).pluginId())) openedIndex = i;
                selector.putClientProperty("jlshell.pageIndex", openedIndex);
                selector.setFocusable(false);
                selector.setPreferredSize(new Dimension(30, 30));
                selector.setMaximumSize(new Dimension(30, 30));
                selector.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                selector.setToolTipText(shortcut.title());
                selector.getAccessibleContext().setAccessibleName(shortcut.title());
                int pageIndex = openedIndex;
                selector.addActionListener(event -> {
                    if (pageIndex >= 0 && selectedPageIndex == pageIndex) showToolPage.accept(pageIndex);
                    else FxThread.run(shortcut.open());
                });
                group.add(selector); navigation.add(selector);
                navigation.add(javax.swing.Box.createVerticalStrut(6));
            }
            selectPage(selected, false);
            navigation.revalidate();
            navigation.repaint();
        });
    }

    private void installChrome(java.util.function.Supplier<SwingServerMonitor> monitorFactory) {
        if (closed) { monitorFactory.get().close(); return; }
        if (status != null) { monitoring.remove(status); status.close(); }
        status = monitorFactory.get();
        monitoring.add(status, BorderLayout.CENTER);
        revalidate();
    }

    public void attach(TerminalViewHandle viewHandle) {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            handle = viewHandle;
            terminalBody.removeAll();
            javax.swing.JComponent component = viewHandle.component();
            TerminalPopupBridge.install(component, terminalSettingsLabel,
                    () -> FxThread.run(terminalView::openFontSettings));
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
            if (status != null) status.disconnected();
            if (files != null) files.disconnected();
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
        pluginPages.values().forEach(SwingPluginPage::close); pluginPages.clear();
        nativePrepared = java.util.Map.of();
        if (files != null) files.close();
        filesFactory = null;
        if (status != null) status.close();
        FxThread.run(() -> {
            model.getTabs().removeListener(tabListListener);
            if (pinnedShortcuts != null && pinnedListener != null) pinnedShortcuts.removeListener(pinnedListener);
            titleListeners.forEach((tab, listener) -> tab.textProperty().removeListener(listener));
            titleListeners.clear();
            contentListeners.forEach((tab, listener) -> tab.contentProperty().removeListener(listener)); contentListeners.clear();
            preparedPages.values().forEach(page -> page.releaseModel().run()); preparedPages.clear();
            model.getSelectionModel().selectedItemProperty().removeListener(selectionListener);
            if (!model.getTabs().isEmpty()) model.getTabs().getFirst().contentProperty().removeListener(terminalViewListener);
        });
    }
}
