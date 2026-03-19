package com.jlshell.ui.view;

import java.util.concurrent.CompletableFuture;

import com.jlshell.core.model.SessionState;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
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
    private final SftpService sftpService;
    private final I18nService i18nService;
    private final PluginManager pluginManager;

    private boolean filePaneInitialized;

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
            AppTheme theme,
            PluginManager pluginManager
    ) {
        super(connectionProfile.displayName());
        this.connectionProfile = connectionProfile;
        this.historyId = historyId;
        this.sshSession = sshSession;
        this.sessionManager = sessionManager;
        this.connectionProfileService = connectionProfileService;
        this.sftpService = sftpService;
        this.i18nService = i18nService;
        this.pluginManager = pluginManager;

        this.terminalWorkspaceView = new TerminalWorkspaceView(
                sshSession,
                terminalViewFactory,
                fontProfileService,
                appSettingsService,
                i18nService,
                theme
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

        if (pluginManager != null) {
            Tab pluginsTab = new Tab(i18nService.get("workspace.plugins"));
            pluginsTab.setClosable(false);
            pluginsTab.setContent(new PluginsTabView(
                    pluginManager, sshSession, workspaceTabs.getTabs()::add, i18nService));
            workspaceTabs.getTabs().add(pluginsTab);
        }

        setContent(workspaceTabs);
    }

    public CompletableFuture<Void> initialize() {
        return terminalWorkspaceView.initialize();
    }

    public void applyTheme(AppTheme theme) {
        terminalWorkspaceView.applyTheme(theme);
    }

    public CompletableFuture<Void> closeWorkspace() {
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
        filesTab.setContent(new SftpBrowserPane(connectionProfile, sshSession, sftpService, i18nService));
    }
}
