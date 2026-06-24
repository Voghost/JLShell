package com.jlshell.ui.view;

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
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.plugin.api.storage.PluginStorage;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;

/**
 * 单个 SSH 会话工作区 Tab。
 */
public class SessionWorkspaceTab extends Tab {

    private final String historyId;
    private final SshSession sshSession;
    private final SessionManager sessionManager;
    private final ConnectionProfileService connectionProfileService;
    private final TerminalWorkspaceView terminalWorkspaceView;
    private final ConnectionProfile connectionProfile;

    /** 返回此 Tab 关联的连接配置，用于右键菜单"复制连接"等操作。 */
    public ConnectionProfile getConnectionProfile() {
        return connectionProfile;
    }
    private final SftpService sftpService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final PluginManager pluginManager;
    private final CapabilityBus capabilityBus;
    private final java.util.function.Function<String, PluginStorage> storageFactory;

    private boolean filePaneInitialized;
    private Tab pluginsTab;
    private PluginsTabView pluginsTabView;
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
        this.innerTabPane = workspaceTabs;
        terminalWorkspaceView.setWorkspaceTabPane(workspaceTabs);

        if (pluginManager != null) {
            pluginsTab = new Tab(i18nService.get("workspace.plugins"));
            pluginsTab.setClosable(false);
            pluginsTabView = new PluginsTabView(
                    pluginManager, sessionId, sshSession, workspaceTabs, i18nService, themeService, sftpService, capabilityBus, storageFactory);
            pluginsTab.setContent(pluginsTabView);
            workspaceTabs.getTabs().add(pluginsTab);
        }

        i18nService.localeProperty().addListener((obs, oldLocale, newLocale) -> {
            if (pluginsTab != null) {
                pluginsTab.setText(i18nService.get("workspace.plugins"));
            }
        });

        setContent(workspaceTabs);
    }

    public CompletableFuture<Void> initialize() {
        return terminalWorkspaceView.initialize();
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        terminalWorkspaceView.applyColorScheme(scheme);
    }

    public CompletableFuture<Void> closeWorkspace() {
        terminalWorkspaceView.stopPlugins();
        if (pluginsTabView != null) {
            pluginsTabView.stopPlugins();
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
                });
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
}
