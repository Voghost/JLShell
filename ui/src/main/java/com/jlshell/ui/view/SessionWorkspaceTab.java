package com.jlshell.ui.view;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.SessionState;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.ProgramConnectionRouteService;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.program.plugin.loader.ProgramSessionIntegrationRegistry;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.plugin.api.event.SessionOpenedEvent;
import com.jlshell.plugin.loader.PluginRuntimeServices;
import java.time.Instant;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.support.UiThread;
import com.jlshell.ui.model.WorkspaceTabs;
import com.jlshell.ui.model.WorkspacePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个 SSH 会话工作区 Tab。
 */
public class SessionWorkspaceTab extends WorkspacePage {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceTab.class);

    private final String historyId;
    private volatile SshSession sshSession;
    private volatile boolean closing;
    private boolean reconnecting;
    private CompletableFuture<Void> closeFuture;
    private java.util.function.BiConsumer<String, Runnable> reconnectFailureHandler = (message, retry) -> { };

    public void setReconnectFailureHandler(java.util.function.BiConsumer<String, Runnable> handler) {
        reconnectFailureHandler = handler;
    }
    private final SessionManager sessionManager;
    private final ConnectionProfileService connectionProfileService;
    private TerminalWorkspaceView terminalWorkspaceView;
    private final ConnectionProfile connectionProfile;

    /** 返回此 Tab 关联的连接配置，用于右键菜单"复制连接"等操作。 */
    public ConnectionProfile getConnectionProfile() {
        return connectionProfile;
    }

    public String getSessionId() {
        return sshSession.sessionId().toString();
    }
    private final SftpService sftpService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final PluginManager pluginManager;
    private final ProgramSessionIntegrationRegistry programSessionRegistry;
    private final ProgramConnectionRouteService connectionRouteService;
    private AutoCloseable connectionRouteLease;
    private final CapabilityBus capabilityBus;
    private final java.util.function.Function<String, PluginStorage> storageFactory;
    private final Runnable sessionCountChanged;
    private final java.util.function.Consumer<String> statsDisconnectListener;

    // 保存构造参数用于重连
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;

    private WorkspacePage pluginsTab;
    private final com.jlshell.core.support.MutableValue<List<PluginsTabView.PinnedShortcut>> pinnedShortcuts = new com.jlshell.core.support.MutableValue<>(List.of());
    public com.jlshell.plugin.api.ui.ObservableValue<List<PluginsTabView.PinnedShortcut>> pinnedShortcuts() { return pinnedShortcuts; }
    private PluginsTabView pluginsTabView;
    private final com.jlshell.plugin.api.lifecycle.Registration localeSubscription;
    private final com.jlshell.core.support.MutableValue<TerminalWorkspaceView> terminalChanges = new com.jlshell.core.support.MutableValue<>(null);
    public com.jlshell.plugin.api.ui.ObservableValue<TerminalWorkspaceView> terminalChanges() { return terminalChanges; }
    /** 会话内容模型，保存终端、文件和插件标签。 */
    private final WorkspaceTabs innerTabPane;

    public SessionWorkspaceTab(
            ConnectionProfile connectionProfile,
            String historyId,
            SshSession sshSession,
            SessionManager sessionManager,
            ConnectionProfileService connectionProfileService,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            SftpService sftpService,
            I18nService i18nService,
            ThemeService themeService,
            PluginManager pluginManager,
            ProgramSessionIntegrationRegistry programSessionRegistry,
            ProgramConnectionRouteService connectionRouteService,
            AutoCloseable connectionRouteLease,
            CapabilityBus capabilityBus,
            java.util.function.Function<String, PluginStorage> storageFactory,
            Runnable sessionCountChanged
    ) {
        super(connectionProfile.displayName());
        this.connectionProfile = connectionProfile;
        this.historyId = historyId;
        this.sshSession = sshSession;
        this.sessionManager = sessionManager;
        this.connectionProfileService = connectionProfileService;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.sftpService = sftpService;
        this.i18nService = i18nService;
        this.themeService = themeService;
        this.pluginManager = pluginManager;
        this.programSessionRegistry = programSessionRegistry;
        this.connectionRouteService = connectionRouteService;
        this.connectionRouteLease = connectionRouteLease;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.sessionCountChanged = sessionCountChanged == null ? () -> { } : sessionCountChanged;
        this.statsDisconnectListener = ignored -> notifySessionCountChanged();
        this.sshSession.addDisconnectListener(statsDisconnectListener);

        // 本工作区 Tab 的会话 id：来自 SSH 会话，用于 per-session 插件能力 registry 路由
        String sessionId = sshSession.sessionId().toString();
        PluginRuntimeServices.publish(new SessionOpenedEvent(
                sessionId, connectionProfile.id(), connectionProfile.projectId(), Instant.now()));

        this.terminalWorkspaceView = new TerminalWorkspaceView(
                sshSession,
                terminalViewFactory,
                fontProfileService,
                appSettingsService,
                i18nService,
                themeService
        );

        WorkspacePage terminalTab = new WorkspacePage(i18nService.get("workspace.terminal"));
        terminalTab.setClosable(false);
        WorkspacePage filesTab = new WorkspacePage(i18nService.get("workspace.files"));
        filesTab.setClosable(false);
        WorkspaceTabs workspaceTabs = new WorkspaceTabs();
        workspaceTabs.add(terminalTab); workspaceTabs.add(filesTab);
        this.innerTabPane = workspaceTabs;

        // 注册重连回调：断连后点击"重新连接"时触发
        terminalWorkspaceView.setOnReconnect(this::reconnect);

        if (pluginManager != null) {
            pluginsTab = new WorkspacePage(i18nService.get("workspace.plugins"));
            pluginsTab.setClosable(false);
            pluginsTabView = new PluginsTabView(
                    pluginManager, programSessionRegistry, sessionId, sshSession, workspaceTabs,
                    i18nService, themeService, sftpService, capabilityBus, storageFactory, appSettingsService, pinnedShortcuts);
            pluginsTab.setContent(pluginsTabView.prepareNativeView());
            workspaceTabs.add(pluginsTab);
        }

        localeSubscription = i18nService.onLocaleChanged(newLocale -> UiThread.run(() -> {
            if (pluginsTab != null) {
                pluginsTab.setText(i18nService.get("workspace.plugins"));
            }
        }));


    }

    public CompletableFuture<Void> initialize() {
        return terminalWorkspaceView.initialize();
    }

    public void showPlugins() {
        if (pluginsTab != null) innerTabPane.select(pluginsTab);
    }

    public List<PluginsTabView.PluginMenuItem> pluginMenuItems() {
        return pluginsTabView == null ? List.of() : pluginsTabView.createMenuItems();
    }

    public boolean isPluginSelected(String pluginId) {
        WorkspacePage selected = innerTabPane.selected();
        return selected != null && java.util.Objects.equals(pluginId, selected.getProperties().get("pluginId"));
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        terminalWorkspaceView.applyColorScheme(scheme);
    }

    public synchronized CompletableFuture<Void> closeWorkspace() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        SshSession sessionToClose = sshSession;
        localeSubscription.close();
        if (programSessionRegistry != null) {
            programSessionRegistry.deactivateSession(sshSession.sessionId().toString());
        }
        if (pluginManager != null) {
            pluginManager.deactivateSession(sshSession.sessionId().toString());
        }
        if (pluginsTabView != null) {
            pluginsTabView.dispose();
            pluginsTabView = null;
        }
        closeFuture = terminalWorkspaceView.closeAsync()
                .exceptionally(throwable -> null)
                .thenCompose(unused -> sessionManager.closeSession(sessionToClose.sessionId()))
                .handle((unused, throwable) -> {
                    connectionProfileService.recordSessionClosed(
                            historyId,
                            throwable == null ? SessionState.CLOSED : SessionState.FAILED,
                            null,
                            throwable == null ? null : throwable.getMessage()
                    );
                    if (throwable != null) {
                        throw new java.util.concurrent.CompletionException(throwable);
                    }
                    return null;
                })
                .thenApply(unused -> (Void) null)
                .whenComplete((unused, throwable) -> {
                    releaseConnectionRoute();
                    notifySessionCountChanged();
                    UiThread.run(this::disposeUiReferences);
                });
        return closeFuture;
    }

    private void disposeUiReferences() {
        sshSession.removeDisconnectListener(statsDisconnectListener);
        releaseConnectionRoute();
        innerTabPane.pages().forEach(tab -> tab.setContent(null));
        innerTabPane.clear();
        setContent(null);
        terminalWorkspaceView = null;
        pluginsTab = null;
        reconnectFailureHandler = (message, retry) -> { };
    }

    public java.util.function.Supplier<com.jlshell.ui.swing.SwingSftpBrowser> prepareNativeFiles() {
        return com.jlshell.ui.swing.SwingSftpBrowser.prepare(sshSession, sftpService, i18nService,
                connectionProfile.defaultRemotePath(), terminalWorkspaceView.cwdProperty());
    }

    /** 返回内层 TabPane（Terminal / Files / Plugins） */
    public WorkspaceTabs getInnerTabPane() {
        return innerTabPane;
    }

    public TerminalWorkspaceView getTerminalWorkspaceView() {
        return terminalWorkspaceView;
    }

    /** 控制终端底部实时状态栏的显隐。 */
    public void setStatusBarVisible(boolean visible) {
        if (terminalWorkspaceView != null) {
            terminalWorkspaceView.setStatusBarVisible(visible);
        }
    }

    /**
     * 断连后重连：关闭旧终端 → 重新建立 SSH 连接 → 创建新终端。
     * 重连期间在终端区域显示加载指示器。
     */
    private void reconnect() {
        if (closing || reconnecting) return;
        reconnecting = true;
        log.info("[Reconnect] Starting reconnect for '{}' ({})", connectionProfile.displayName(), connectionProfile.summary());
        if (programSessionRegistry != null) {
            programSessionRegistry.deactivateSession(sshSession.sessionId().toString());
        }
        if (pluginManager != null) {
            pluginManager.deactivateSession(sshSession.sessionId().toString());
        }
        if (pluginsTabView != null) {
            pluginsTabView.stopPlugins();
        }
        sshSession.removeDisconnectListener(statsDisconnectListener);

        // 1. 关闭旧终端
        terminalWorkspaceView.closeAsync()
                .exceptionally(ex -> {
                    log.warn("[Reconnect] Error closing old terminal: {}", ex.getMessage());
                    return null;
                })
                .thenCompose(unused -> {
                    // 2. 关闭旧 SSH 会话
                    return sessionManager.closeSession(sshSession.sessionId())
                            .exceptionally(ex -> {
                                log.warn("[Reconnect] Error closing old SSH session: {}", ex.getMessage());
                                return null;
                            });
                })
                .thenCompose(unused -> {
                    releaseConnectionRoute();
                    // 3. 后台线程：toConnectionRequest 含 DB 查询 + AES 解密
                    log.info("[Reconnect] Re-establishing SSH connection for '{}'", connectionProfile.displayName());
                    return CompletableFuture.supplyAsync(
                                    () -> connectionProfileService.toConnectionRequest(connectionProfile.id()))
                            .thenCompose(request -> connectionRouteService == null
                                    ? CompletableFuture.completedFuture(new ProgramConnectionRouteService.RoutedConnection(
                                            request, () -> { }))
                                    : connectionRouteService.route(connectionProfile, request))
                            .thenCompose(routed -> sessionManager.openSession(routed.request())
                                    .handle((session, error) -> {
                                        if (error != null) {
                                            ProgramConnectionRouteService.closeQuietly(routed.lease());
                                            throw new java.util.concurrent.CompletionException(error);
                                        }
                                        return new RoutedSession(session, routed.lease());
                                    }));
                })
                .thenCompose(routed -> {
                    com.jlshell.core.session.SshSession newSession = routed.session();
                    log.info("[Reconnect] SSH reconnected for session {}", newSession.sessionId());
                    // 在 EDT 替换会话与终端，关闭后的迟到连接须立即释放。
                    return UiThread.supplyAsync(() -> {
                        if (closing) {
                            ProgramConnectionRouteService.closeQuietly(routed.routeLease());
                            sessionManager.closeSession(newSession.sessionId());
                            throw new java.util.concurrent.CancellationException("Workspace is closing");
                        }
                        this.sshSession = newSession;
                        this.connectionRouteLease = routed.routeLease();
                        this.sshSession.addDisconnectListener(statsDisconnectListener);
                        notifySessionCountChanged();
                        // 创建新的终端视图
                        String newSessionId = newSession.sessionId().toString();
                        PluginRuntimeServices.publish(new SessionOpenedEvent(
                                newSessionId, connectionProfile.id(),
                                connectionProfile.projectId(), Instant.now()));
                        TerminalWorkspaceView newView = new TerminalWorkspaceView(
                                newSession, terminalViewFactory, fontProfileService,
                                appSettingsService, i18nService, themeService
                        );
                        newView.setOnReconnect(this::reconnect);
                        this.terminalWorkspaceView = newView;

                        // 替换 Terminal tab 的内容
                        terminalChanges.set(newView);
                        return newView;
                    }).thenCompose(TerminalWorkspaceView::initialize);
                })
                .whenComplete((unused, throwable) -> UiThread.run(() -> {
                    reconnecting = false;
                    if (closing) return;
                    if (throwable != null) {
                        log.error("[Reconnect] Failed to reconnect '{}': {}", connectionProfile.displayName(), throwable.getMessage());
                        terminalWorkspaceView.closeAsync();
                        reconnectFailureHandler.accept(
                                i18nService.get("status.terminalOpenFailed", throwable.getMessage()), this::reconnect);
                    } else {
                        log.info("[Reconnect] Successfully reconnected '{}'", connectionProfile.displayName());
                        terminalWorkspaceView.markReconnected();
                        resetPluginPaneAfterReconnect();
                    }
                }));
    }

    private void notifySessionCountChanged() {
        try {
            sessionCountChanged.run();
        } catch (RuntimeException e) {
            log.warn("Failed to update account connection statistics", e);
        }
    }

    private void releaseConnectionRoute() {
        ProgramConnectionRouteService.closeQuietly(connectionRouteLease);
        connectionRouteLease = null;
    }

    private record RoutedSession(com.jlshell.core.session.SshSession session, AutoCloseable routeLease) {
    }

    private void resetPluginPaneAfterReconnect() {
        if (pluginManager == null || pluginsTab == null) {
            return;
        }
        if (pluginsTabView != null) {
            pluginsTabView.dispose();
        }
        innerTabPane.pages().stream().filter(tab -> tab != pluginsTab && tab.getProperties().get("pluginId") != null).toList().forEach(innerTabPane::remove);
        String newSessionId = sshSession.sessionId().toString();
        pluginsTabView = new PluginsTabView(
                pluginManager, programSessionRegistry, newSessionId, sshSession, innerTabPane,
                i18nService, themeService,
                sftpService, capabilityBus, storageFactory, appSettingsService, pinnedShortcuts);
        pluginsTab.setContent(pluginsTabView.prepareNativeView());
    }
}
