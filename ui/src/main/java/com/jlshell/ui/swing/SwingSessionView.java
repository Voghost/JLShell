package com.jlshell.ui.swing;

import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.view.SessionWorkspaceTab;
import com.jlshell.ui.view.TerminalWorkspaceView;
import com.jlshell.ui.view.PluginsTabView;
import com.jlshell.ui.model.WorkspacePage;
import com.jlshell.ui.model.WorkspaceTabs;
import com.jlshell.plugin.api.lifecycle.Registration;
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

/** 原生 Swing 终端、文件、监控与插件页；页面以原生组件延迟创建。 */
public final class SwingSessionView extends JPanel implements NativeTerminalHost, AutoCloseable {
    private final JPanel terminal = new JPanel(new BorderLayout());
    private final JPanel terminalBody = new JPanel(new BorderLayout());
    private final JPanel navigation = new JPanel();
    private final JPanel monitoring = new JPanel(new BorderLayout());
    private final String monitoringTitle;
    private IntConsumer showToolPage = index -> {};
    private Runnable toolPagesChanged = () -> {};

    void setToolPagesChanged(Runnable callback) { toolPagesChanged = callback; }
    private final I18nService i18n;
    private final java.util.Map<WorkspacePage, SwingPluginPage.Prepared> preparedPages = new java.util.IdentityHashMap<>();
    private final java.util.Map<WorkspacePage, SwingPluginPage> pluginPages = new java.util.IdentityHashMap<>();
    private final java.util.Map<WorkspacePage, Registration> contentListeners = new java.util.IdentityHashMap<>();
    private java.util.Map<WorkspacePage, SwingPluginPage.Prepared> nativePrepared = java.util.Map.of();
    private List<WorkspacePage> pageTabs = List.of();
    private List<String> pageTitles = List.of();
    private List<String> pagePluginIds = List.of();
    private final JPanel rightHost = new JPanel(new java.awt.CardLayout());
    private java.util.function.Supplier<SwingSftpBrowser> filesFactory;
    private SwingSftpBrowser files;
    private java.awt.Component fileComponent;
    private final ThemeService themes;
    private TerminalWorkspaceView terminalView;
    private final String terminalSettingsLabel;
    private final WorkspaceTabs model;
    private SwingServerMonitor status;
    private TerminalViewHandle handle;
    private Registration selectionSubscription;
    private com.jlshell.plugin.api.lifecycle.Registration terminalViewSubscription;
    private Registration tabSubscription;
    private final java.util.Map<WorkspacePage, Registration> titleListeners = new java.util.IdentityHashMap<>();
    private boolean closed;
    private String statusName = "", statusAddress = "", connectionState = "connecting";
    private boolean localSession;
    private java.time.Instant connectedAt;

    SwingStatusBar.SessionInfo statusInfo() {
        String encoding = handle == null ? "" : handle.encodingName();
        String terminal = encoding;
        if (handle != null && handle.component() instanceof com.jlshell.terminal.support.JlshellJediTermWidget widget) {
            var buffer = widget.getTerminalTextBuffer();
            terminal += " · " + buffer.getWidth() + " × " + buffer.getHeight();
        }
        return new SwingStatusBar.SessionInfo(statusName, statusAddress, localSession, connectionState, connectedAt, terminal, encoding);
    }
    String activeTransfer() {
        String transfer = files == null ? null : files.activeTransferDescription();
        return transfer == null ? null : statusName + " · " + transfer;
    }

    private volatile int selectedPageIndex;
    private boolean followingToolSelection; // 仅由 EDT 访问。
    private record Page(WorkspacePage tab, String title, String pluginId) {}
    private List<PluginsTabView.PinnedShortcut> pinnedShortcuts = List.of();
    private Registration pinnedSubscription;

    /** 配置会话后，在 EDT 延迟创建终端与工具窗口。 */
    public static CompletableFuture<SwingSessionView> create(SessionWorkspaceTab source, ThemeService themes, I18nService i18n) {
        WorkspaceTabs model = source.getInnerTabPane();
        var initial = source.getTerminalWorkspaceView();
        var future = SwingExecutors.supplyOnEdtAsync(() -> {
            var view = new SwingSessionView(model, themes, i18n.get("workspace.monitoring"), initial,
                    i18n.get("terminal.appearanceSettings"), i18n);
            view.statusName = source.getConnectionProfile().displayName();
            view.statusAddress = source.getConnectionProfile().summary();
            view.localSession = source.getConnectionProfile().connectionType() == com.jlshell.core.model.ConnectionType.LOCAL_SHELL;
            view.terminalBody.removeAll();
            view.terminalBody.add(new SwingLoadingPanel(i18n.get("workspace.connecting"), view.statusAddress, WorkbenchIcon.Kind.SSH));
            return view;
        });
        NativeTerminalHost host = new NativeTerminalHost() {
            public void attach(TerminalViewHandle handle) { future.thenAccept(view -> view.attach(handle)); }
            public void disconnected(String reason, Runnable reconnect) { future.thenAccept(view -> view.disconnected(reason, reconnect)); }
        };
        source.setReconnectFailureHandler(host::disconnected);
        java.util.function.Consumer<TerminalWorkspaceView> configure = controller -> {
            controller.setNativeTerminalHost(host);
            var files = source.prepareNativeFiles();
            var monitor = SwingServerMonitor.prepare(controller, i18n);
            future.thenAccept(view -> SwingUtilities.invokeLater(() -> {
                view.terminalView = controller; view.installFiles(files); view.installChrome(monitor);
            }));
        };
        configure.accept(initial);
        var terminalSubscription = source.terminalChanges().subscribe(next -> { if (next != null) configure.accept(next); });
        future.thenAccept(view -> {
            view.terminalViewSubscription = terminalSubscription;
            view.selectionSubscription = model.selectionProperty().subscribe(tab -> {
                boolean revealTool = !view.followingToolSelection;
                SwingUtilities.invokeLater(() -> view.selectPage(model.pages().indexOf(tab), revealTool));
            });
            view.tabSubscription = model.pagesProperty().subscribe(pages -> SwingUtilities.invokeLater(view::publishPages));
            view.pinnedSubscription = source.pinnedShortcuts().subscribe(shortcuts -> com.jlshell.ui.support.UiThread.run(() -> {
                view.pinnedShortcuts = shortcuts; view.publishPages();
            }));
        });
        return future;
    }

    SwingSessionView(WorkspaceTabs model, ThemeService themes, String monitoringTitle,
                     TerminalWorkspaceView terminalView, String terminalSettingsLabel, I18nService i18n) {
        super(new BorderLayout());
        this.themes = themes;
        this.i18n = i18n;
        this.model = model;
        this.monitoringTitle = monitoringTitle;
        this.terminalView = terminalView;
        this.terminalSettingsLabel = terminalSettingsLabel;

        terminalBody.add(new SwingLoadingPanel(i18n.get("workspace.connecting"), "", WorkbenchIcon.Kind.SSH));
        terminal.add(terminalBody, BorderLayout.CENTER);
        // 终端独立于插件卡片；铺满只切换外层布局，不移除或重建终端。
        add(terminal, BorderLayout.CENTER);
        navigation.setLayout(new javax.swing.BoxLayout(navigation, javax.swing.BoxLayout.Y_AXIS));
        navigation.setOpaque(false);
    }

    void selectPage(int index, boolean reveal) {
        if (closed || index < 0) return;
        selectedPageIndex = index;
        if (index == 1 && files == null && filesFactory != null) {
            files = filesFactory.get();
            rightHost.add(files, "files");
        }
        if (index >= 2 && index < pageTabs.size()) {
            WorkspacePage tab = pageTabs.get(index);
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
        rightHost.revalidate(); rightHost.repaint();
        navigation.revalidate(); navigation.repaint();
    }

    String toolKey(int index) {
        if (index == 0) return "monitor";
        if (index == 1) return "files";
        if (index == 2) return "plugins";
        return index < pagePluginIds.size() && pagePluginIds.get(index) != null
                ? "plugin:" + pagePluginIds.get(index) : null;
    }

    int toolIndex(String key) {
        if ("monitor".equals(key)) return 0;
        for (int i = 1; i < pageTabs.size(); i++) if (java.util.Objects.equals(key, toolKey(i))) return i;
        return -1;
    }

    com.jlshell.plugin.api.host.HostExtensions.ViewOptions toolOptions(String key) {
        int index=toolIndex(key);
        if(index<0 || index>=pageTabs.size()) return null;
        WorkspacePage tab=pageTabs.get(index);
        if(tab==null) return null;
        Object host=tab.getProperties().get("jlshell.hostExtensions");
        return host instanceof com.jlshell.plugin.loader.ScopedHostExtensions extensions ? extensions.viewOptions() : null;
    }

    String toolTitle(String key) {
        int index = toolIndex(key);
        if (index == 0) return monitoringTitle;
        if (index > 0) return pageTitles.get(index);
        for (var component : navigation.getComponents())
            if (component instanceof JToggleButton button && java.util.Objects.equals(key, button.getClientProperty("jlshell.pluginKey")))
                return java.util.Objects.toString(button.getToolTipText(), "");
        return "";
    }

    java.awt.Component toolComponent(String key) {
        int index = toolIndex(key);
        if (index == 0) return monitoring;
        if (index < 0) return null;
        int selected = selectedPageIndex;
        selectPage(index, false);
        java.awt.Component result = index == 1 ? (files != null ? files : fileComponent) : pluginPages.get(pageTabs.get(index));
        // Layout test fixtures can provide a local file panel without a network-backed browser.
        if (index == 1 && result == null) for (var child : rightHost.getComponents())
            if (!pluginPages.containsValue(child)) { result = child; break; }
        if (index == 1) fileComponent = result;
        selectPage(selected, false);
        return result;
    }

    /** 底部工具窗只保留会话监控；文件和插件页交由右侧工具窗显示。 */
    public JPanel toolWindow() { return monitoring; }

    java.awt.Component rightToolWindow() { return rightHost; }

    private void installFiles(java.util.function.Supplier<SwingSftpBrowser> factory) {
        if (closed) return;
        if (files != null) { rightHost.remove(files); files.close(); files = null; }
        filesFactory = factory;
        selectPage(selectedPageIndex, false);
        toolPagesChanged.run();
    }

    /** EDT 使用的页面标识快照；使用稳定的页面标识。 */
    record ToolPage(int kind, String pluginId, WorkspacePage identity) {}

    ToolPage selectedToolPage() {
        int index = selectedPageIndex;
        if (index < 1 || index >= pageTabs.size()) return null;
        return new ToolPage(Math.min(index, 3), pagePluginIds.get(index), pageTabs.get(index));
    }

    int findToolPage(ToolPage page) {
        if (page == null) return -1;
        if (page.kind() < 3) return page.kind() < pageTabs.size() ? page.kind() : -1;
        int index = page.pluginId() == null ? pageTabs.indexOf(page.identity()) : pagePluginIds.indexOf(page.pluginId());
        return index >= 3 ? index : -1;
    }

    /** 铺满状态切换会话时沿用工具种类，未打开同一插件则回到插件列表。EDT 调用。 */
    void followToolPage(SwingSessionView previous) {
        int index = findToolPage(previous.selectedToolPage());
        selectToolPage(index >= 0 ? index : 2);
    }

    void selectToolPage(int index) {
        if (closed || index < 1 || index >= pageTabs.size()) return;
        WorkspacePage tab = pageTabs.get(index);
        selectPage(index, false);
        SwingUtilities.invokeLater(() -> {
            if (closed || !model.pages().contains(tab)) return;
            followingToolSelection = true;
            try { model.select(tab); }
            finally { followingToolSelection = false; }
        });
    }

    int selectedPageIndex() { return selectedPageIndex; }

    record ToolPageOption(int index, String title) {}

    List<ToolPageOption> toolPageOptions() {
        var result = new java.util.ArrayList<ToolPageOption>();
        for (int index = 1; index < pageTitles.size(); index++)
            result.add(new ToolPageOption(index, pageTitles.get(index)));
        return List.copyOf(result);
    }

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

    /** 插件可动态 openTab/closeTab/updateTabTitle，必须同步原有 WorkspacePage 模型。EDT 调用。 */
    void publishPages() {
        if (closed) return;
        titleListeners.entrySet().removeIf(entry -> {
            if (model.pages().contains(entry.getKey())) return false;
            entry.getValue().close(); return true;
        });
        contentListeners.entrySet().removeIf(entry -> {
            if (model.pages().contains(entry.getKey())) return false;
            entry.getValue().close(); return true;
        });
        for (WorkspacePage tab : model.pages()) {
            if (!titleListeners.containsKey(tab)) {
                final boolean[] initial = {true};
                var subscription = tab.textProperty().subscribe(title -> {
                    if (initial[0]) { initial[0] = false; return; }
                    SwingUtilities.invokeLater(this::publishPages);
                });
                titleListeners.put(tab, subscription);
            }
            if (!contentListeners.containsKey(tab)) {
                final boolean[] initial = {true};
                var subscription = tab.contentProperty().subscribe(next -> {
                    if (initial[0]) { initial[0] = false; return; }
                    com.jlshell.ui.support.UiThread.run(() -> {
                        var prepared = preparedPages.remove(tab); if (prepared != null) prepared.releaseModel().run();
                        var page = pluginPages.remove(tab);
                        if (page != null) { if (page.getParent() != null) page.getParent().remove(page); page.close(); }
                        publishPages();
                    });
                });
                contentListeners.put(tab, subscription);
            }
        }
        preparedPages.entrySet().removeIf(entry -> {
            if (model.pages().contains(entry.getKey())) return false;
            entry.getValue().releaseModel().run(); return true;
        });
        for (int index = 2; index < model.pages().size(); index++) {
            WorkspacePage tab = model.pages().get(index);
            preparedPages.computeIfAbsent(tab, key -> SwingPluginPage.prepare(key, i18n, themes));
        }
        List<WorkspacePage> tabs = List.copyOf(model.pages());
        var prepared = new java.util.IdentityHashMap<>(preparedPages);
        List<Page> pages = model.pages().stream().map(tab -> new Page(tab, tab.getText(), (String) tab.getProperties().get("pluginId"))).toList();
        int selected = model.selectedIndex();
        List<PluginsTabView.PinnedShortcut> shortcuts = pinnedShortcuts == null ? List.of() : List.copyOf(pinnedShortcuts);
        com.jlshell.ui.support.UiThread.run(() -> {
            if (closed) return;
            pageTabs = tabs;
            pageTitles = pages.stream().map(Page::title).toList();
            pagePluginIds = pages.stream().map(Page::pluginId).toList();
            pluginPages.forEach((tab, page) -> { int index = tabs.indexOf(tab); if (index >= 0) page.updateTitle(pageTitles.get(index)); });
            // 使用不可变页面快照。
            nativePrepared = prepared;
            pluginPages.entrySet().removeIf(entry -> {
                if (tabs.contains(entry.getKey())) return false;
                if (entry.getValue().getParent() != null) entry.getValue().getParent().remove(entry.getValue()); entry.getValue().close(); return true;
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
                    SwingUtilities.invokeLater(() -> model.select(page.tab()));
                    if (alreadySelected) showToolPage.accept(pageIndex);
                });
                group.add(selector);
                navigation.add(selector);
                navigation.add(javax.swing.Box.createVerticalStrut(6));
            }
            for (PluginsTabView.PinnedShortcut shortcut : shortcuts) {
                JToggleButton selector = new JToggleButton(PluginIcons.icon(shortcut.title(), shortcut.icon(), 18, true));
                selector.setName("plugin-shortcut-" + shortcut.id());
                selector.putClientProperty("jlshell.pluginKey", "plugin:" + shortcut.id());
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
                    else shortcut.open().run();
                });
                group.add(selector); navigation.add(selector);
                navigation.add(javax.swing.Box.createVerticalStrut(6));
            }
            selectPage(selected, false);
            toolPagesChanged.run();
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
            handle = viewHandle; connectionState = "connected"; connectedAt = java.time.Instant.now();
            terminalBody.removeAll();
            javax.swing.JComponent component = viewHandle.component();
            TerminalPopupBridge.install(component, terminalSettingsLabel,
                    () -> SwingUtilities.invokeLater(terminalView::openFontSettings));
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
            if (connectionState.equals("connected")) {
                com.jlshell.core.support.RecentApplicationLogs.shared().append(java.time.Instant.now(), "INFO", "Connection",
                        i18n.get("statusbar.sessionEnded", statusName));
            }
            handle = null; connectionState = "disconnected"; connectedAt = null;
            if (status != null) status.disconnected();
            if (files != null) files.disconnected();
            terminalBody.removeAll();
            JPanel message = new JPanel(new java.awt.GridBagLayout());
            JPanel actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 12));
            actions.add(new JLabel(reason, JLabel.CENTER));
            JButton retry = new JButton("重新连接 / Reconnect");
            retry.addActionListener(event -> { retry.setEnabled(false); SwingUtilities.invokeLater(reconnect); });
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
        toolPagesChanged = () -> {};
        terminalBody.removeAll();
        pluginPages.values().forEach(SwingPluginPage::close); pluginPages.clear();
        nativePrepared = java.util.Map.of();
        if (files != null) files.close();
        filesFactory = null;
        if (status != null) status.close();
        if (tabSubscription != null) tabSubscription.close();
        if (pinnedSubscription != null) pinnedSubscription.close();
        if (selectionSubscription != null) selectionSubscription.close();
        if (terminalViewSubscription != null) terminalViewSubscription.close();
        titleListeners.values().forEach(Registration::close); titleListeners.clear();
        contentListeners.values().forEach(Registration::close); contentListeners.clear();
        preparedPages.values().forEach(page -> page.releaseModel().run()); preparedPages.clear();
    }
}
