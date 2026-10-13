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
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 原生 Swing 终端；文件及插件页仍使用原始 JavaFX 视图和会话生命周期。 */
public final class SwingSessionView extends JPanel implements NativeTerminalHost, AutoCloseable {
    private final JPanel terminal = new JPanel(new BorderLayout());
    private final JPanel terminalBody = new JPanel(new BorderLayout());
    private final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JPanel toolWindow = new JPanel(new BorderLayout());
    private final JPanel cards = new JPanel(new CardLayout());
    private final JPanel monitoring = new JPanel(new BorderLayout());
    private final String monitoringTitle;
    private Runnable revealToolWindow = () -> {};
    private final SwingFxPanel compatibility;
    private final ThemeService themes;
    private final TabPane model;
    private SwingFxPanel toolbar;
    private SwingFxPanel status;
    private TerminalViewHandle handle;
    private ChangeListener<Tab> selectionListener;
    private ChangeListener<Node> terminalViewListener;
    private javafx.collections.ListChangeListener<Tab> tabListListener;
    private final java.util.Map<Tab, ChangeListener<String>> titleListeners = new java.util.IdentityHashMap<>();
    private boolean closed;
    private record Page(Tab tab, String title, boolean closable) {}

    /** 从 FX 线程调用。不会同步等待 EDT，也不会改变插件返回的 Node。 */
    public static CompletableFuture<SwingSessionView> create(SessionWorkspaceTab source, ThemeService themes, I18nService i18n) {
        TabPane model = source.getInnerTabPane();
        source.setContent(null);
        var future = SwingExecutors.supplyOnEdtAsync(() -> new SwingSessionView(model, themes, i18n.get("workspace.monitoring")));
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
            future.thenAccept(view -> SwingUtilities.invokeLater(() -> view.installChrome(top, bottom)));
        };
        configure.accept(source.getTerminalWorkspaceView());
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

    SwingSessionView(TabPane model, ThemeService themes, String monitoringTitle) {
        super(new BorderLayout());
        this.themes = themes;
        this.model = model;
        this.monitoringTitle = monitoringTitle;
        compatibility = new SwingFxPanel(themes);
        terminalBody.add(new JLabel("…", JLabel.CENTER));
        terminal.add(terminalBody, BorderLayout.CENTER);
        // 主终端始终可见，不再从 CardLayout 移出或被文件/插件页覆盖。
        add(terminal, BorderLayout.CENTER);
        cards.add(monitoring, "monitoring");
        cards.add(compatibility, "compatibility");
        navigation.setOpaque(false);
        navigation.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, SwingTheme.color("border")));
        toolWindow.add(navigation, BorderLayout.NORTH);
        toolWindow.add(cards, BorderLayout.CENTER);
    }

    private void selectPage(int index, boolean reveal) {
        if (closed || index < 0) return;
        ((CardLayout) cards.getLayout()).show(cards, index == 0 ? "monitoring" : "compatibility");
        if (index < navigation.getComponentCount() && navigation.getComponent(index) instanceof JPanel page) {
            ((JToggleButton) page.getClientProperty("selector")).setSelected(true);
        }
        if (reveal) revealToolWindow.run();
        cards.revalidate(); cards.repaint();
    }

    public JPanel toolWindow() { return toolWindow; }

    public void setToolWindowRevealAction(Runnable action) {
        revealToolWindow = action == null ? () -> {} : action;
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
        List<Page> pages = model.getTabs().stream().map(tab -> new Page(tab, tab.getText(), tab.isClosable())).toList();
        int selected = model.getSelectionModel().getSelectedIndex();
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            navigation.removeAll();
            ButtonGroup group = new ButtonGroup();
            for (Page page : pages) {
                JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                item.setOpaque(false);
                JToggleButton selector = new JToggleButton(page.tab() == pages.getFirst().tab() ? monitoringTitle : page.title());
                selector.putClientProperty("JButton.buttonType", "toolBarButton");
                selector.setFocusable(false);
                selector.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
                selector.addActionListener(event -> {
                    revealToolWindow.run();
                    FxThread.run(() -> model.getSelectionModel().select(page.tab()));
                });
                group.add(selector);
                item.putClientProperty("selector", selector);
                item.add(selector);
                if (page.closable()) {
                    JButton close = new JButton(new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE));
                    close.putClientProperty("JButton.buttonType", "toolBarButton");
                    close.setFocusable(false);
                    close.addActionListener(event -> FxThread.run(() -> {
                        javafx.event.Event request = new javafx.event.Event(page.tab(), page.tab(), Tab.TAB_CLOSE_REQUEST_EVENT);
                        javafx.event.Event.fireEvent(page.tab(), request);
                        if (!request.isConsumed()) {
                            model.getTabs().remove(page.tab());
                            javafx.event.Event.fireEvent(page.tab(), new javafx.event.Event(Tab.CLOSED_EVENT));
                        }
                    }));
                    item.add(close);
                }
                navigation.add(item);
            }
            selectPage(selected, false);
            navigation.revalidate();
            navigation.repaint();
        });
    }

    private void installChrome(Node top, Node bottom) {
        if (closed) return;
        if (toolbar != null) { terminal.remove(toolbar); toolbar.close(); }
        if (status != null) { monitoring.remove(status); status.close(); }
        if (top != null) {
            toolbar = new SwingFxPanel(themes);
            toolbar.setPreferredSize(new Dimension(100, 32));
            toolbar.setView(top);
            terminal.add(toolbar, BorderLayout.NORTH);
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
            TerminalPopupBridge.install(component);
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
        revealToolWindow = () -> {};
        terminalBody.removeAll();
        compatibility.close();
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
