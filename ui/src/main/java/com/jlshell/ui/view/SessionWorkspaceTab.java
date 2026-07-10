package com.jlshell.ui.view;

import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.ConnectionRequest;
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
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.support.FxThread;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个 SSH 会话工作区 Tab。
 */
public class SessionWorkspaceTab extends Tab {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceTab.class);

    private final String historyId;
    private SshSession sshSession;
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
    private final CapabilityBus capabilityBus;
    private final java.util.function.Function<String, PluginStorage> storageFactory;

    // 保存构造参数用于重连
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;

    private boolean filePaneInitialized;
    private Tab pluginsTab;
    private PluginsTabView pluginsTabView;
    private final ChangeListener<java.util.Locale> localeListener;
    /** SFTP 面板，首次激活时创建 */
    private SftpBrowserPane sftpPane;
    /** 内层 TabPane（Terminal / Files / Plugins），用于折叠顶栏时控制 tab-header 显隐 */
    private final TabPane innerTabPane;

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
            CapabilityBus capabilityBus,
            java.util.function.Function<String, PluginStorage> storageFactory
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
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;

        // 本工作区 Tab 的会话 id：来自 SSH 会话，用于 per-session 插件能力 registry 路由
        String sessionId = sshSession.sessionId().toString();

        this.terminalWorkspaceView = new TerminalWorkspaceView(
                sessionId,
                sshSession,
                terminalViewFactory,
                fontProfileService,
                appSettingsService,
                i18nService,
                themeService,
                pluginManager,
                capabilityBus,
                sftpService,
                storageFactory
        );

        Tab terminalTab = new Tab(i18nService.get("workspace.terminal"), terminalWorkspaceView);
        terminalTab.setClosable(false);
        Tab filesTab = new Tab(i18nService.get("workspace.files"),
                new StackPane(new Label(i18nService.get("status.connecting", connectionProfile.summary()))));
        filesTab.setClosable(false);
        filesTab.selectedProperty().addListener((obs, oldSelected, selected) -> {
            if (selected) {
                initializeFilePane(filesTab);
            }
        });

        TabPane workspaceTabs = new TabPane(terminalTab, filesTab);
        workspaceTabs.getStyleClass().add("session-inner-tabs");
        this.innerTabPane = workspaceTabs;
        terminalWorkspaceView.setWorkspaceTabPane(workspaceTabs);

        // 注册重连回调：断连后点击"重新连接"时触发
        terminalWorkspaceView.setOnReconnect(this::reconnect);

        if (pluginManager != null) {
            pluginsTab = new Tab(i18nService.get("workspace.plugins"));
            pluginsTab.setClosable(false);
            pluginsTabView = new PluginsTabView(
                    pluginManager, sessionId, sshSession, workspaceTabs, i18nService, themeService, sftpService, capabilityBus, storageFactory);
            pluginsTab.setContent(pluginsTabView);
            workspaceTabs.getTabs().add(pluginsTab);
        }

        localeListener = (obs, oldLocale, newLocale) -> {
            if (pluginsTab != null) {
                pluginsTab.setText(i18nService.get("workspace.plugins"));
            }
        };
        i18nService.localeProperty().addListener(localeListener);

        setContent(workspaceTabs);
    }

    public CompletableFuture<Void> initialize() {
        return terminalWorkspaceView.initialize();
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        terminalWorkspaceView.applyColorScheme(scheme);
    }

    public CompletableFuture<Void> closeWorkspace() {
        i18nService.localeProperty().removeListener(localeListener);
        if (pluginManager != null) {
            pluginManager.deactivateSession(sshSession.sessionId().toString());
        }
        terminalWorkspaceView.stopPlugins();
        if (pluginsTabView != null) {
            pluginsTabView.dispose();
            pluginsTabView = null;
        }
        return terminalWorkspaceView.closeAsync()
                .exceptionally(throwable -> null)
                .thenCompose(unused -> sessionManager.closeSession(sshSession.sessionId()))
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
                .whenComplete((unused, throwable) -> FxThread.run(this::disposeUiReferences));
    }

    private void disposeUiReferences() {
        if (sftpPane != null) {
            sftpPane.dispose();
            sftpPane = null;
        }
        innerTabPane.getTabs().forEach(tab -> tab.setContent(null));
        innerTabPane.getTabs().clear();
        setContent(null);
        terminalWorkspaceView = null;
        pluginsTab = null;
    }

    /**
     * 文件页签首次激活时再创建 SFTP 面板，避免连接瞬间同时初始化终端和文件管理导致界面卡顿。
     */
    private void initializeFilePane(Tab filesTab) {
        if (filePaneInitialized) {
            return;
        }
        filePaneInitialized = true;
        sftpPane = new SftpBrowserPane(connectionProfile, sshSession, sftpService, i18nService, themeService);
        // 连接终端 cwd 属性，实现"跟随终端目录"
        sftpPane.setTerminalCwdProperty(terminalWorkspaceView.cwdProperty());
        sftpPane.setInjectOsc7HookCallback(() -> terminalWorkspaceView.injectOsc7PromptHook());
        filesTab.setContent(sftpPane);
    }

    /** 返回内层 TabPane（Terminal / Files / Plugins） */
    public TabPane getInnerTabPane() {
        return innerTabPane;
    }

    /**
     * 控制内层 TabPane 的 tab-header-area 显隐。
     * CSS 方式对 JavaFX 内部布局不可靠，使用程序化方式直接操作子节点。
     */
    public void setTabHeadersVisible(boolean visible) {
        for (javafx.scene.Node node : innerTabPane.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area")) {
                node.setManaged(visible);
                node.setVisible(visible);
                return;
            }
        }
    }

    /**
     * 控制终端工作区顶部工具栏（系统信息条 + 插件按钮 + 字体设置）的显隐。
     */
    public void setToolbarVisible(boolean visible) {
        terminalWorkspaceView.setToolbarVisible(visible);
    }

    /**
     * 断连后重连：关闭旧终端 → 重新建立 SSH 连接 → 创建新终端。
     * 重连期间在终端区域显示加载指示器。
     */
    private void reconnect() {
        log.info("[Reconnect] Starting reconnect for '{}' ({})", connectionProfile.displayName(), connectionProfile.summary());
        if (pluginManager != null) {
            pluginManager.deactivateSession(sshSession.sessionId().toString());
        }
        terminalWorkspaceView.stopPlugins();
        if (pluginsTabView != null) {
            pluginsTabView.stopPlugins();
        }

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
                    // 3. 后台线程：toConnectionRequest 含 DB 查询 + AES 解密
                    log.info("[Reconnect] Re-establishing SSH connection for '{}'", connectionProfile.displayName());
                    return CompletableFuture.supplyAsync(
                                    () -> connectionProfileService.toConnectionRequest(connectionProfile.id()))
                            .thenCompose(sessionManager::openSession);
                })
                .thenCompose(newSession -> {
                    log.info("[Reconnect] SSH reconnected for session {}", newSession.sessionId());
                    // 4. 在 FX 线程替换 sshSession 和终端视图
                    return FxThread.supplyAsync(() -> {
                        this.sshSession = newSession;
                        // 创建新的终端视图
                        String newSessionId = newSession.sessionId().toString();
                        TerminalWorkspaceView newView = new TerminalWorkspaceView(
                                newSessionId, newSession, terminalViewFactory, fontProfileService,
                                appSettingsService, i18nService, themeService, pluginManager,
                                capabilityBus, sftpService, storageFactory
                        );
                        newView.setWorkspaceTabPane(innerTabPane);
                        newView.setOnReconnect(this::reconnect);
                        this.terminalWorkspaceView = newView;

                        // 替换 Terminal tab 的内容
                        innerTabPane.getTabs().getFirst().setContent(newView);
                        return newView;
                    }).thenCompose(TerminalWorkspaceView::initialize);
                })
                .whenComplete((unused, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        log.error("[Reconnect] Failed to reconnect '{}': {}", connectionProfile.displayName(), throwable.getMessage());
                        // 重连失败：清除旧 overlay，让下次断连时重新显示
                        terminalWorkspaceView.markReconnected();
                    } else {
                        log.info("[Reconnect] Successfully reconnected '{}'", connectionProfile.displayName());
                        terminalWorkspaceView.markReconnected();
                        resetFilePaneAfterReconnect();
                        resetPluginPaneAfterReconnect();
                    }
                }));
    }

    private void resetFilePaneAfterReconnect() {
        filePaneInitialized = false;
        if (sftpPane != null) {
            sftpPane.dispose();
            sftpPane = null;
        }
        innerTabPane.getTabs().stream()
                .filter(t -> t.getText().equals(i18nService.get("workspace.files")))
                .findFirst()
                .ifPresent(t -> t.setContent(new StackPane(new Label(
                        i18nService.get("status.connecting", connectionProfile.summary())))));
    }

    private void resetPluginPaneAfterReconnect() {
        if (pluginManager == null || pluginsTab == null) {
            return;
        }
        if (pluginsTabView != null) {
            pluginsTabView.dispose();
        }
        innerTabPane.getTabs().removeIf(tab -> tab != pluginsTab && tab.getProperties().get("pluginId") != null);
        String newSessionId = sshSession.sessionId().toString();
        pluginsTabView = new PluginsTabView(
                pluginManager, newSessionId, sshSession, innerTabPane, i18nService, themeService,
                sftpService, capabilityBus, storageFactory);
        pluginsTab.setContent(pluginsTabView);
    }
}
