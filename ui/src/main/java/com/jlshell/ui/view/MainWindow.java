package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.jlshell.api.server.ApiServer;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.model.ConnectionRequest;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.program.plugin.loader.ProgramPluginManager;
import com.jlshell.ui.dialog.ProjectManagerDialog;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.ConnectionShareService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.LocalShellLauncher;
import com.jlshell.ui.service.MemoryReclaimService;
import com.jlshell.ui.service.ProgramConnectionRouteService;
import com.jlshell.ui.service.SidebarExpansionStateStore;
import com.jlshell.ui.service.account.AccountService;
import com.jlshell.ui.service.account.AccountConnectionCounter;
import com.jlshell.ui.service.update.UpdateService;
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.support.ExceptionMessages;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.viewmodel.MainViewModel;
import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutRegistry;
import com.jlshell.ui.dialog.AboutDialog;
import com.jlshell.ui.dialog.AccountDialog;
import com.jlshell.ui.dialog.PreferencesDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jlshell.ui.model.WorkspacePage;
import com.jlshell.ui.model.WorkspaceTabs;
import com.jlshell.ui.support.UiThread;
import com.jlshell.ui.swing.*;
import com.jlshell.ui.swing.SwingMenuBridge.Item;

/** 原生桌面工作台控制器。 */
public class MainWindow {
    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
    private static final String SETTINGS_SIDEBAR_VISIBLE = "ui.sidebar.visible";
    private static final String SETTINGS_FOCUS_MODE = "ui.focusMode";
    private static final String SETTINGS_FOCUS_SIDEBAR_BEFORE = "ui.focusMode.sidebarVisibleBefore";
    private final MainViewModel viewModel;
    private final ConnectionProfileService connectionProfileService;
    private final SessionManager sessionManager;
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;
    private final SftpService sftpService;
    private final ThemeService themeService;
    private final I18nService i18nService;
    private final LocalShellLauncher localShellLauncher;
    private final ExecutorService executor;
    private final PluginManager pluginManager;
    private final ProgramPluginManager programPluginManager;
    private final ProgramConnectionRouteService connectionRouteService;
    private final ApiServer apiServer;
    private final CapabilityBus capabilityBus;
    private final VaultService vaultService;
    private final MemoryReclaimService memoryReclaimService;
    private final UpdateService updateService;
    private final AccountService accountService;
    private final ShortcutRegistry shortcutRegistry;
    private final ConnectionShareService connectionShareService = new ConnectionShareService();
    private final SidebarExpansionStateStore sidebarExpansionStateStore;
    private final Map<String, Set<String>> sidebarCollapsedFolderStateCache = new ConcurrentHashMap<>();
    private final Object sidebarExpansionSaveLock = new Object();
    private CompletableFuture<Void> sidebarExpansionSave = CompletableFuture.completedFuture(null);
    private final WorkspaceTabs workspaceTabs = new WorkspaceTabs();
    private final List<com.jlshell.terminal.service.TerminalViewHandle> localShellHandles = new ArrayList<>();
    private final Set<String> connectingConnectionIds = new HashSet<>();
    private final Set<WorkspacePage> closingTabs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final List<com.jlshell.plugin.api.lifecycle.Registration> registrations = new ArrayList<>();
    private SidebarTreeView sidebarTreeView;
    private CompletableFuture<SwingWorkbench> swingWorkbench;
    private Runnable swingQuitAction;
    private javax.swing.Timer automaticUpdateTimer;
    private Object dialogOwner;
    private static final ProjectProfile DEFAULT_PROJECT = new ProjectProfile(null, "", null);
    private String activeProjectId;
    private List<ProjectProfile> projectChoices = List.of();
    private List<ConnectionProfile> cachedProfiles = List.of();
    private final int maxFolderDepth;
    private boolean sidebarVisible = true, focusMode, sidebarVisibleBeforeFocus = true;
    private final java.util.function.Function<String, com.jlshell.plugin.api.storage.PluginStorage> storageFactory;

    public MainWindow(
            MainViewModel viewModel,
            ConnectionProfileService connectionProfileService,
            SessionManager sessionManager,
            TerminalViewFactory terminalViewFactory,
            FontProfileService fontProfileService,
            AppSettingsService appSettingsService,
            SftpService sftpService,
            ThemeService themeService,
            I18nService i18nService,
            LocalShellLauncher localShellLauncher,
            ExecutorService sshConnectionExecutor,
            VaultService vaultService,
            int maxFolderDepth,
            PluginManager pluginManager,
            ProgramPluginManager programPluginManager,
            ApiServer apiServer,
            CapabilityBus capabilityBus,
            java.util.function.Function<String, com.jlshell.plugin.api.storage.PluginStorage> storageFactory,
            MemoryReclaimService memoryReclaimService,
            UpdateService updateService,
            AccountService accountService,
            ShortcutRegistry shortcutRegistry
    ) {
        this.viewModel = viewModel;
        this.connectionProfileService = connectionProfileService;
        this.sessionManager = sessionManager;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.sidebarExpansionStateStore = new SidebarExpansionStateStore(appSettingsService);
        this.sftpService = sftpService;
        this.themeService = themeService;
        this.i18nService = i18nService;
        this.localShellLauncher = localShellLauncher;
        this.executor = sshConnectionExecutor;
        this.vaultService = vaultService;
        this.maxFolderDepth = maxFolderDepth;
        this.pluginManager = pluginManager;
        this.programPluginManager = programPluginManager;
        this.connectionRouteService = new ProgramConnectionRouteService(programPluginManager);
        this.apiServer = apiServer;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;
        this.memoryReclaimService = memoryReclaimService;
        this.updateService = updateService;
        this.accountService = accountService;
        this.shortcutRegistry = shortcutRegistry;

        // Restore saved active project
        String savedProject = appSettingsService.get("ui.activeProject", "");
        if (savedProject != null && !savedProject.isBlank()) {
            this.activeProjectId = savedProject;
        }
        this.sidebarVisible = Boolean.parseBoolean(appSettingsService.get(SETTINGS_SIDEBAR_VISIBLE, "true"));
        this.focusMode = Boolean.parseBoolean(appSettingsService.get(SETTINGS_FOCUS_MODE, "false"));
        if (this.focusMode) {
            this.sidebarVisibleBeforeFocus = Boolean.parseBoolean(
                    appSettingsService.get(SETTINGS_FOCUS_SIDEBAR_BEFORE, "true"));
            this.sidebarVisible = false;
        }
    }

    /** 原生工作台启动入口。业务状态与控件生命周期都在 EDT 串行更新。 */
    public CompletableFuture<Void> showSwingWindow(Object owner, Runnable quit) {
        return showSwingWindow(owner, quit, null);
    }

    public CompletableFuture<Void> showSwingWindow(Object owner, Runnable quit, com.jlshell.core.support.PerformanceTrace timing) {
        swingQuitAction = quit;
        dialogOwner = owner;
        return UiThread.supplyAsync(() -> {
            configureSidebarModel(owner);
            refreshProjectCombo();
            var texts = com.jlshell.ui.swing.SwingWorkbench.TEXT_KEYS.stream().collect(Collectors.toMap(key -> key, i18nService::get));
            for (String key : List.of("ui.workspace.toolLayout", "ui.workspace.windowSize"))
                texts.put(key, appSettingsService.get(key, ""));
            texts.put("ui.sidebar.sort", appSettingsService.get("ui.sidebar.sort", "name"));
            texts.put("ui.sidebar.foldersFirst", appSettingsService.get("ui.sidebar.foldersFirst", "true"));
            var actions = new com.jlshell.ui.swing.SwingWorkbench.Actions(
                    () -> createConnection(owner), () -> createFolder(owner), () -> editSelectedConnection(owner),
                    this::deleteSelectedConnection, this::loadConnections, () -> openPreferences(owner),
                    () -> manageProjects(owner), this::toggleSidebar, quit, this::connectProfile,
                    items -> {
                        sidebarTreeView.selectItems(items);
                        viewModel.selectedConnectionProperty().set(sidebarTreeView.getSelectedConnection(cachedProfiles));
                    }, project -> switchProjectWithConfirm(project.id() == null ? DEFAULT_PROJECT : project),
                    this::saveSidebarCollapsedFolders,
                    id -> { if (id instanceof WorkspacePage page) workspaceTabs.select(page); },
                    (id, position) -> { if (id instanceof WorkspacePage page) { workspaceTabs.move(page, position); withSwing(view -> view.reorderTabs(new ArrayList<>(workspaceTabs.pages()))); } },
                    sidebarTreeView::moveItems, (item, reply) -> reply.accept(sidebarTreeView.contextMenuFor(item)),
                    (id, reply) -> { if (id instanceof WorkspacePage page) reply.accept(buildTabContextMenu(page)); },
                    appSettingsService::set, () -> {
                        var profile = connectionProfileForTab(workspaceTabs.selected());
                        if (profile != null) withSwing(view -> view.focusConnection(profile.id()));
                    });
            SwingTheme.install(themeService);
            installProgramWorkspaceUi();
            com.jlshell.plugin.loader.HostExtensionRegistry.sessionSelector(id -> com.jlshell.terminal.support.SwingExecutors.runOnEdtAsync(() -> {
                var page=workspaceTabs.pages().stream().filter(candidate -> (candidate instanceof SessionWorkspaceTab sessionTab && id.equals(sessionTab.getSessionId())) || id.equals(candidate.getProperties().get("terminalSessionId"))).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Session is not open"));
                workspaceTabs.select(page);
            }));
            var view = timing == null ? new SwingWorkbench(actions, texts) : timing.call("swing-workbench-create", () -> new SwingWorkbench(actions, texts));
            swingWorkbench = CompletableFuture.completedFuture(view);
            view.setMenuBar(SwingMenuBridge.create(this::buildMenuBar));
            view.setMenuProvider(reply -> reply.accept(buildMenuBar()), Map.of(
                    "plugins", () -> openPreferences(owner, PreferencesDialog.TAB_PLUGINS),
                    "account", () -> openPreferences(owner, PreferencesDialog.TAB_ACCOUNT),
                    "shortcuts", () -> openPreferences(owner, PreferencesDialog.TAB_SHORTCUTS)));
            registrations.add(viewModel.statusMessageProperty().subscribe(value -> UiThread.run(() -> view.setStatus(value))));
            registrations.add(workspaceTabs.selectionProperty().subscribe(page -> UiThread.run(() -> { view.selectTab(page); updateWindowTitle(); com.jlshell.plugin.loader.HostExtensionRegistry.activeSession(selectedApiSessionId()); })));
            registrations.add(themeService.onThemeChanged(theme -> UiThread.run(() -> {
                viewModel.activeThemeProperty().set(theme); applyUiFontSettings();
                if (pluginManager != null) pluginManager.setThemeName(theme.name().toLowerCase(Locale.ROOT));
                if (programPluginManager != null) programPluginManager.setThemeName(theme.name().toLowerCase(Locale.ROOT));
            })));
            registrations.add(themeService.onAccentColorChanged(accent -> UiThread.run(this::applyUiFontSettings)));
            registrations.add(themeService.onUiFontChanged(font -> UiThread.run(this::applyUiFontSettings)));
            registrations.add(themeService.onColorSchemeChanged(scheme -> UiThread.run(() -> {
                workspaceTabs.pages().stream().filter(SessionWorkspaceTab.class::isInstance).map(SessionWorkspaceTab.class::cast).forEach(tab -> tab.applyColorScheme(scheme));
                localShellHandles.forEach(handle -> handle.updateColorScheme(scheme));
            })));
            registrations.add(i18nService.onLocaleChanged(locale -> {
                if (pluginManager != null) pluginManager.setLocale(locale);
                if (programPluginManager != null) programPluginManager.setLocale(locale);
            }));
            view.setSidebarVisible(sidebarVisible);
            installSwingShortcuts(quit);
            if (timing != null) view.firstPaint().thenRun(() -> timing.finish("workbench-first-paint"));
            view.show(); loadConnections(); publishSwingProjects(); updateWindowTitle();
            scheduleAutomaticUpdateCheck(owner); validateAccountSession(owner);
            return null;
        });
    }

    private void manageProjects(Object owner) {
        ProjectManagerDialog.show(owner, connectionProfileService, i18nService, themeService, vaultService, activeProjectId,
                this::switchProject).thenRun(() -> UiThread.run(() -> { refreshProjectCombo(); loadConnections(); }));
    }

    private void dispatchSwingAction(Runnable action) { UiThread.run(action); }
    public void showMainWindow() { withSwing(SwingWorkbench::show); }
    public void hideMainWindow() { withSwing(SwingWorkbench::hide); }
    public void closeSwingWindow() {
        registrations.forEach(com.jlshell.plugin.api.lifecycle.Registration::close); registrations.clear();
        if (automaticUpdateTimer != null) automaticUpdateTimer.stop();
        apiBackgroundLeases.values().forEach(ProgramConnectionRouteService::closeQuietly);
        apiBackgroundLeases.clear();
        com.jlshell.plugin.loader.HostExtensionRegistry.workspaceUi(null);
        withSwing(SwingWorkbench::close);
    }

    private List<Item> buildMenuBar() {
        List<Item> projects = new ArrayList<>();
        projects.add(SwingMenuBridge.checked(i18nService.get("project.label.default"), activeProjectId == null, () -> switchProjectWithConfirm(DEFAULT_PROJECT)));
        projectChoices.forEach(project -> projects.add(SwingMenuBridge.checked(project.name(), Objects.equals(activeProjectId, project.id()), () -> switchProjectWithConfirm(project))));
        projects.add(action("project.menu.manage", () -> manageProjects(dialogOwner)));
        var files = List.of(action("action.newConnection", () -> createConnection(dialogOwner)),
                action("connection.share.pasteMenu", () -> pasteSharedConnection(dialogOwner)), action("action.refresh", this::loadConnections),
                SwingMenuBridge.submenu(i18nService.get("project.menu.projects"), projects),
                action("vault.menu.manage", () -> com.jlshell.ui.dialog.VaultManagerDialog.show(dialogOwner, vaultService, i18nService, themeService,
                        activeProjectId, projectChoices, () -> UiThread.run(this::loadConnections))), SwingMenuBridge.separator(), action("action.exit", swingQuitAction));
        List<Item> connections = new ArrayList<>();
        for (WorkspacePage page : workspaceTabs.pages()) connections.add(SwingMenuBridge.checked(page.getText(), page == workspaceTabs.selected(), () -> workspaceTabs.select(page)));
        if (connections.isEmpty()) connections.add(SwingMenuBridge.disabled(i18nService.get("menu.connections.empty")));
        var view = List.of(action("sidebar.toggle", this::toggleSidebar),
                SwingMenuBridge.checked(i18nService.get("terminal.statusBar.toggle"), Boolean.parseBoolean(appSettingsService.get(TerminalWorkspaceView.STATUS_BAR_VISIBLE_KEY, "true")),
                        () -> setTerminalStatusBarVisible(!Boolean.parseBoolean(appSettingsService.get(TerminalWorkspaceView.STATUS_BAR_VISIBLE_KEY, "true")))),
                SwingMenuBridge.checked(i18nService.get("focusMode.toggle"), focusMode, this::toggleFocusMode), SwingMenuBridge.separator(),
                SwingMenuBridge.checked(i18nService.get("theme.dark"), themeService.currentTheme() == AppTheme.DARK, () -> themeService.setTheme(AppTheme.DARK)),
                SwingMenuBridge.checked(i18nService.get("theme.light"), themeService.currentTheme() == AppTheme.LIGHT, () -> themeService.setTheme(AppTheme.LIGHT)),
                SwingMenuBridge.separator(), action("workspace.restoreLayout", () -> withSwing(SwingWorkbench::restoreDefaultLayout)));
        var help = List.of(action("menu.help.checkUpdates", () -> checkForUpdates(dialogOwner)), action("menu.help.about", () -> openAbout(dialogOwner)));
        return List.of(SwingMenuBridge.submenu(i18nService.get("menu.file"), files),
                SwingMenuBridge.submenu(i18nService.get("menu.connections"), connections),
                SwingMenuBridge.submenu(i18nService.get("menu.account"), accountMenu()),
                SwingMenuBridge.submenu(i18nService.get("menu.view"), view),
                SwingMenuBridge.submenu(i18nService.get("workspace.plugins"), pluginMenu()),
                SwingMenuBridge.submenu(i18nService.get("menu.settings"), List.of(action("action.preferences", () -> openPreferences(dialogOwner)))),
                SwingMenuBridge.submenu(i18nService.get("menu.help"), help));
    }

    private Item action(String key, Runnable action) {
        Item item = SwingMenuBridge.action(i18nService.get(key), action);
        String shortcutId = Map.of("action.newConnection", "app.newConnection", "action.preferences", "app.preferences",
                "action.exit", "app.quit", "action.refresh", "app.refreshConnections", "sidebar.toggle", "app.toggleSidebar", "focusMode.toggle", "app.focusMode").get(key);
        if (shortcutId == null) return item;
        String spec = shortcutRegistry.getEffectivePrimary(shortcutId);
        return new Item(item.text(), ShortcutConverter.toDisplayText(spec), ShortcutConverter.toKeyStroke(spec), false, false, 0, action, null, List.of());
    }

    private List<Item> pluginMenu() {
        var active = workspaceTabs.selected();
        SessionWorkspaceTab session = active instanceof SessionWorkspaceTab value ? value : null;
        List<Item> result = new ArrayList<>();
        result.add(session == null ? SwingMenuBridge.disabled(i18nService.get("menu.plugins.open")) : action("menu.plugins.open", () -> withSwing(SwingWorkbench::openPluginPanel)));
        result.add(action("menu.plugins.manage", () -> openPreferences(dialogOwner, PreferencesDialog.TAB_PLUGINS)));
        result.add(SwingMenuBridge.separator());
        var entries = session == null ? List.<PluginsTabView.PluginMenuItem>of() : session.pluginMenuItems();
        if (entries.isEmpty()) result.add(SwingMenuBridge.disabled(i18nService.get(session == null ? "workspace.noOpenSessions" : "plugin.noPlugins")));
        for (var entry : entries) result.add(SwingMenuBridge.action(entry.title(), () -> {
            if (workspaceTabs.selected() != session) return;
            boolean selected = session.isPluginSelected(entry.id()); entry.open().run();
            if (selected) withSwing(SwingWorkbench::revealSelectedToolPage);
        }));
        return List.copyOf(result);
    }

    private List<Item> accountMenu() {
        if (accountService == null) return List.of(SwingMenuBridge.disabled(i18nService.get("account.login.password.action")));
        if (accountService.isSignedIn()) {
            var session = accountService.currentSession().orElseThrow();
            String name = session.username().isBlank() ? session.email() : session.username();
            return List.of(SwingMenuBridge.disabled(i18nService.get("account.signedInAs", name)),
                    action("account.settings", () -> openPreferences(dialogOwner, PreferencesDialog.TAB_ACCOUNT)),
                    action("account.logout", () -> accountService.logout().whenComplete((ignored, error) -> UiThread.run(() -> {
                        if (error != null) showError(ExceptionMessages.userMessage(error));
                        else viewModel.statusMessageProperty().set(i18nService.get("account.logout.success"));
                    }))));
        }
        return List.of(action("account.login.web.action", () -> AccountDialog.showBrowserLogin(dialogOwner, i18nService, themeService, accountService)),
                action("account.login.password.action", () -> AccountDialog.showLogin(dialogOwner, i18nService, themeService, accountService)),
                action("account.register.action", () -> AccountDialog.showRegister(dialogOwner, i18nService, themeService, accountService)),
                action("account.settings", () -> openPreferences(dialogOwner, PreferencesDialog.TAB_ACCOUNT)));
    }

    private void refreshProjectCombo() {
        projectChoices = List.copyOf(connectionProfileService.listProjects()); publishSwingProjects();
    }
    private void selectProjectCombo() { publishSwingProjects(); }
    private void publishSwingProjects() {
        var choices = projectChoices; String active = activeProjectId;
        withSwing(view -> view.updateProjects(choices, active));
    }
    private void switchProjectWithConfirm(ProjectProfile selected) {
        if (selected == null || Objects.equals(selected.id(), activeProjectId)) return;
        String title = selected.id() == null ? i18nService.get("project.label.default") : selected.name();
        SwingMessages.confirm(i18nService.get("project.switch.confirm", title), i18nService.get("project.switch.detail", title), themeService, i18nService)
                .thenAccept(confirmed -> { if (confirmed) switchProject(selected.id()); else publishSwingProjects(); });
    }
    public void refreshShortcuts(Object scene, Object stage) { installSwingShortcuts(swingQuitAction); }
    private void updateWindowTitle() {
        var profile = connectionProfileForTab(workspaceTabs.selected());
        withSwing(view -> view.setTitle(profile == null ? "JLShell" : formatConnectionTitle(profile) + " — JLShell"));
    }
    static ConnectionProfile connectionProfileForTab(WorkspacePage tab) {
        if (tab instanceof SessionWorkspaceTab session) return session.getConnectionProfile();
        return tab != null && tab.getProperties().get("connectionProfile") instanceof ConnectionProfile profile ? profile : null;
    }
    private void installWorkspaceTabHeader(WorkspacePage tab, String title) { tab.setText(title); }
    private void applySidebarVisibility() { withSwing(view -> view.setSidebarVisible(sidebarVisible)); }
    private void applyUiFontSettings() {
        withSwing(view -> view.applyTheme(themeService.currentTheme(), themeService.accentColor().color(), themeService.uiFontFamily(), themeService.uiFontSize()));
    }
    private CompletableFuture<String> promptFolderName(Object stage, String title, String initial) {
        return SwingFormDialog.showAsync(title, "jlshell.folder.name", i18nService, themeService, () -> {
            javax.swing.JTextField name = new javax.swing.JTextField(initial == null ? "" : initial); name.setName("folder.name");
            javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
            content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 22, 18, 22));
            content.add(new javax.swing.JLabel(i18nService.get("sftp.newFolder.prompt")), java.awt.BorderLayout.NORTH); content.add(name);
            return new SwingFormDialog.Form<>(content, () -> name.getText().isBlank() ? null : name.getText().strip());
        }, 460, 210);
    }
    private void deleteSelectedConnection() {
        var selected = selectedConnection(); if (selected == null) return;
        SwingMessages.confirm(i18nService.get("action.deleteConnection"), i18nService.get("confirm.deleteConnection", selected.displayName()), themeService, i18nService)
                .thenAccept(confirmed -> { if (confirmed) CompletableFuture.runAsync(() -> connectionProfileService.delete(selected.id()), executor)
                        .whenComplete((ignored, error) -> UiThread.run(() -> { if (error != null) showError(ExceptionMessages.userMessage(error)); else loadConnections(); })); });
    }
    private void openLocalShellTab(ConnectionProfile profile, com.jlshell.terminal.service.TerminalViewHandle handle) { openLocalShellTab(profile,handle,true); }
    private void openLocalShellTab(ConnectionProfile profile, com.jlshell.terminal.service.TerminalViewHandle handle,boolean focus) {
        localShellHandles.add(handle);
        com.jlshell.plugin.loader.HostExtensionRegistry.bind(handle.sessionId().toString(),profile.id(),com.jlshell.program.api.ProgramWorkspaceService.Presentation.TAB);
        var terminalRegistration=handle.terminalAccess().map(access -> com.jlshell.plugin.loader.HostExtensionRegistry.terminal(
                new com.jlshell.plugin.api.host.HostExtensions.Session(access.sessionId(),profile.displayName(),"localhost",System.getProperty("user.name"),true,false),access))
                .orElse(com.jlshell.plugin.api.lifecycle.Registration.noop());
        WorkspacePage tab = new WorkspacePage(profile.displayName()); tab.getProperties().put("connectionProfile", profile);
        tab.getProperties().put("terminalSessionId",handle.sessionId().toString());
        tab.getProperties().put("closeAction", (java.util.function.Supplier<CompletableFuture<Void>>) () -> handle.closeAsync().whenComplete((ignored, error) -> UiThread.run(() -> { localShellHandles.remove(handle); terminalRegistration.close(); })));
        withSwing(view -> { TerminalPopupBridge.install(handle.component()); view.addTab(tab, profile.displayName(), handle.component(), () -> closeTab(tab), profile.summary()); view.selectTab(tab); });
        workspaceTabs.add(tab); workspaceTabs.select(tab); if(focus) handle.requestFocus();
        viewModel.statusMessageProperty().set(i18nService.get("status.connected", profile.displayName()));
    }
    private List<Item> buildTabContextMenu(WorkspacePage tab) {
        int index = workspaceTabs.pages().indexOf(tab);
        List<Item> result = new ArrayList<>();
        result.add(action("tab.close", () -> closeTab(tab)));
        result.add(index <= 0 ? SwingMenuBridge.disabled(i18nService.get("tab.closeLeft")) : action("tab.closeLeft", () -> closeTabsLeftOf(tab)));
        result.add(index >= workspaceTabs.pages().size() - 1 ? SwingMenuBridge.disabled(i18nService.get("tab.closeRight")) : action("tab.closeRight", () -> closeTabsRightOf(tab)));
        result.add(action("tab.closeAll", this::closeAllTabs));
        if (tab instanceof SessionWorkspaceTab session) { result.add(SwingMenuBridge.separator()); result.add(action("tab.duplicateSession", () -> connectProfile(session.getConnectionProfile()))); }
        return List.copyOf(result);
    }
    private void removeWorkspaceTab(WorkspacePage tab) {
        String id=tab instanceof SessionWorkspaceTab ssh ? ssh.getSessionId() : (String)tab.getProperties().get("terminalSessionId");
        if(id!=null) com.jlshell.plugin.loader.HostExtensionRegistry.unbind(id);
        workspaceTabs.remove(tab); withSwing(view -> view.removeTab(tab)); updateWindowTitle(); }
    private void closeTab(WorkspacePage tab) {
        if (!closingTabs.add(tab)) return;
        CompletableFuture<Void> closed;
        if (tab instanceof SessionWorkspaceTab session) closed = session.closeWorkspace();
        else {
            @SuppressWarnings("unchecked") var action = (java.util.function.Supplier<CompletableFuture<Void>>) tab.getProperties().get("closeAction");
            closed = action == null ? CompletableFuture.completedFuture(null) : action.get();
        }
        closed.whenComplete((ignored, error) -> UiThread.run(() -> {
            closingTabs.remove(tab); removeWorkspaceTab(tab); afterWorkspaceTabRemoved();
            if (error != null) showError(i18nService.get("status.sessionCloseFailed", ExceptionMessages.userMessage(error)));
        }));
    }
    private void showError(String message) {
        viewModel.statusMessageProperty().set(message);
        SwingMessages.show(i18nService.get("error.title"), message, themeService, i18nService);
    }

    private void withSwing(java.util.function.Consumer<com.jlshell.ui.swing.SwingWorkbench> action) {
        if (swingWorkbench != null) swingWorkbench.thenAccept(view -> javax.swing.SwingUtilities.invokeLater(() -> action.accept(view)));
    }

    private int swingFontSize() {
        try { return Math.max(10, Math.min(40, Integer.parseInt(appSettingsService.get("ui.font.size", "13")))); }
        catch (NumberFormatException ignored) { return 13; }
    }

    private void publishSwingWelcome() {
        if (swingWorkbench == null) return;
        CompletableFuture.supplyAsync(() -> connectionProfileService.listRecentSessions(10), executor)
                .thenAccept(items -> withSwing(view -> {
                    // 收藏和历史都来自原有服务，数据不在 EDT 查询。
                    CompletableFuture.supplyAsync(connectionProfileService::listFavoriteProfiles, executor)
                            .thenAccept(favorites -> withSwing(window -> window.updateWelcome(items, favorites)));
                }));
    }

    private void installSwingShortcuts(Runnable quit) {
        Map<String, Runnable> actions = Map.of("app.newConnection", () -> createConnection(dialogOwner),
                "app.refreshConnections", this::loadConnections, "app.quit", quit,
                "app.toggleSidebar", this::toggleSidebar,
                "app.focusMode", this::toggleFocusMode, "app.preferences", () -> openPreferences(dialogOwner));
        Map<javax.swing.KeyStroke, Runnable> bindings = new java.util.HashMap<>();
        actions.forEach((id, action) -> {
            for (String spec : new String[]{shortcutRegistry.getEffectivePrimary(id), shortcutRegistry.getEffectiveSecondary(id)}) {
                var key = ShortcutConverter.toKeyStroke(spec);
                if (key != null) bindings.put(key, () -> dispatchSwingAction(action));
            }
        });
        String preferencesShortcut = ShortcutConverter.toDisplayText(shortcutRegistry.getEffectivePrimary("app.preferences"));
        withSwing(view -> {
            view.setShortcuts(bindings);
            view.setPreferenceShortcut(preferencesShortcut);
        });
    }

    private void validateAccountSession(Object stage) {
        if (accountService == null) {
            return;
        }
        accountService.setLiveConnectionCount(AccountConnectionCounter.connectedSessions(sessionManager));
        if (!accountService.isSignedIn()) return;
        accountService.validateSession().whenComplete((session, error) -> {
            if (session == null || error != null) {
                log.info("Account session validation failed or expired");
            }
        });
    }

    private void reportAccountStats() {
        if (accountService == null) return;
        accountService.updateLiveStats(AccountConnectionCounter.connectedSessions(sessionManager));
    }

    private void switchProject(String projectId) {
        activeProjectId = projectId;
        appSettingsService.set("ui.activeProject", projectId != null ? projectId : "");
        selectProjectCombo();
        loadConnections();
    }

    public CompletableFuture<Void> openPreferences(Object stage) {
        return openPreferences(stage, PreferencesDialog.TAB_GENERAL);
    }

    private CompletableFuture<Void> openPreferences(Object stage, int initialTabIndex) {
        String selectedSessionId = selectedApiSessionId();
        return PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager,
                pluginManager, selectedSessionId, memoryReclaimService, accountService, shortcutRegistry, initialTabIndex)
                .thenCompose(ignored -> UiThread.supplyAsync(() -> {
                    loadConnections();
                    applyUiFontSettings();
                    refreshShortcuts(null, stage);
                    return null;
                }));
    }

    public void openAbout(Object stage) {
        AboutDialog.show(stage, i18nService, themeService, () -> UiThread.run(() -> checkForUpdates(stage)));
    }

    public void checkForUpdates(Object stage) {
        var checking = new com.jlshell.ui.swing.SwingProgressDialog(themeService, i18nService,
                "jlshell.updates.checking", i18nService.get("updates.checking"));
        updateService.checkLatest(PreferencesDialog.getVersion())
                .whenComplete((response, error) -> UiThread.run(() -> {
                    checking.close();
                    if (error != null) {
                        showUpdateError(stage, error);
                        return;
                    }
                    if (response == null || !response.updateAvailable() || updateService.isIgnored(response)) {
                        showUpdateMessage(stage, i18nService.get("updates.upToDate"));
                        return;
                    }
                    showUpdateAvailable(stage, response);
                }));
    }

    private void downloadUpdate(Object stage, UpdateService.UpdateResponse response) {
        var downloadDialog = new com.jlshell.ui.swing.SwingProgressDialog(themeService, i18nService,
                "jlshell.updates.download", i18nService.get("updates.downloading"));
        updateService.downloadAndStage(response, downloadDialog::update).whenComplete((result, error) -> UiThread.run(() -> {
            downloadDialog.close();
            if (error != null) {
                showUpdateError(stage, error);
                return;
            }
            if (result.restartRequired()) {
                showRestartRequiredDialog(stage, result.file().toString());
            } else {
                showUpdateMessage(stage, i18nService.get("updates.downloadedInstaller", result.file().toString()));
            }
        }));

    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String selectedApiSessionId() {
        WorkspacePage selected = workspaceTabs.selected();
        return selected instanceof SessionWorkspaceTab sessionTab ? sessionTab.getSessionId() : selected == null ? null : (String) selected.getProperties().get("terminalSessionId");
    }

    private String formatConnectionTitle(ConnectionProfile profile) {
        if (profile == null) {
            return "";
        }
        String name = profile.displayName() == null || profile.displayName().isBlank()
                ? profile.host()
                : profile.displayName();
        return name + " — " + profile.summary();
    }

    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        applySidebarVisibility();
        saveLayoutState();
    }

    private void toggleFocusMode() {
        setFocusMode(!focusMode);
    }

    private void setTerminalStatusBarVisible(boolean visible) {
        appSettingsService.set(TerminalWorkspaceView.STATUS_BAR_VISIBLE_KEY, String.valueOf(visible));
        workspaceTabs.pages().stream()
                .filter(SessionWorkspaceTab.class::isInstance)
                .map(SessionWorkspaceTab.class::cast)
                .forEach(tab -> tab.setStatusBarVisible(visible));
    }

    private void setFocusMode(boolean enabled) {
        if (focusMode == enabled) {
            return;
        }
        focusMode = enabled;
        if (enabled) {
            sidebarVisibleBeforeFocus = sidebarVisible;
            appSettingsService.set(SETTINGS_FOCUS_SIDEBAR_BEFORE, String.valueOf(sidebarVisibleBeforeFocus));
            if (sidebarVisible) {
                toggleSidebar();
            }
        } else {
            if (sidebarVisible != sidebarVisibleBeforeFocus) {
                toggleSidebar();
            }
        }
        saveLayoutState();
    }

    private void saveLayoutState() {
        appSettingsService.set(SETTINGS_SIDEBAR_VISIBLE, String.valueOf(sidebarVisible));
        appSettingsService.set(SETTINGS_FOCUS_MODE, String.valueOf(focusMode));
    }

    private void loadConnections() {
        final String projectId = activeProjectId;
        CompletableFuture.supplyAsync(() -> {
            java.util.List<ConnectionProfile> profiles = connectionProfileService.listProfilesByProject(projectId);
            java.util.List<FolderProfile> folders = connectionProfileService.listFolders(projectId);
            Set<String> collapsedFolders = sidebarCollapsedFolderStateCache.computeIfAbsent(
                    sidebarExpansionCacheKey(projectId),
                    ignored -> Set.copyOf(sidebarExpansionStateStore.loadCollapsedFolderIds(projectId)));
            return new ConnectionTreeSnapshot(folders, profiles, collapsedFolders);
        }, executor).whenComplete((entry, throwable) -> UiThread.run(() -> {
            if (!Objects.equals(projectId, activeProjectId)) {
                return;
            }
            if (throwable != null) {
                showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                return;
            }
            cachedProfiles = entry.profiles();
            publishSwingProjects();
            withSwing(view -> view.updateConnections(entry.folders(), entry.profiles(), entry.collapsedFolderIds()));
            publishSwingWelcome();
            viewModel.replaceConnections(entry.profiles());
            sidebarTreeView.populate(entry.folders(), entry.profiles(), entry.collapsedFolderIds());
            viewModel.statusMessageProperty().set(
                    i18nService.get("status.connectionsLoaded", viewModel.connections().size()));
        }));
    }

    private void saveSidebarCollapsedFolders(Set<String> collapsedFolderIds) {
        String projectId = activeProjectId;
        Set<String> snapshot = Set.copyOf(collapsedFolderIds);
        sidebarCollapsedFolderStateCache.put(sidebarExpansionCacheKey(projectId), snapshot);
        synchronized (sidebarExpansionSaveLock) {
            sidebarExpansionSave = sidebarExpansionSave
                    .handle((unused, error) -> {
                        if (error != null) {
                            log.warn("Failed to persist sidebar folder expansion state", error);
                        }
                        return null;
                    })
                    .thenRunAsync(() -> sidebarExpansionStateStore.saveCollapsedFolderIds(projectId, snapshot), executor);
        }
    }

    private static String sidebarExpansionCacheKey(String projectId) {
        return projectId == null ? "<default>" : projectId;
    }

    public void flushPendingUiState() {
        // Shutdown runs on its own thread. Drain the EDT snapshot and already queued UI writes
        // before closing the datasource. Never block EDT waiting for itself.
        if (swingWorkbench != null && !javax.swing.SwingUtilities.isEventDispatchThread()) {
            try {
                var workbench = swingWorkbench.getNow(null);
                if (workbench != null) {
                    var layout = com.jlshell.terminal.support.SwingExecutors.supplyOnEdtAsync(
                            workbench::captureLayoutForShutdown).get(2, TimeUnit.SECONDS);
                    UiThread.supplyAsync(() -> null).get(2, TimeUnit.SECONDS);
                    layout.forEach(appSettingsService::set);
                }
            } catch (Exception error) {
                log.warn("Failed to flush workbench layout", error);
            }
        }
        CompletableFuture<Void> pending;
        synchronized (sidebarExpansionSaveLock) {
            pending = sidebarExpansionSave;
        }
        try {
            pending.get(2, TimeUnit.SECONDS);
        } catch (Exception error) {
            log.warn("Failed to flush pending sidebar folder expansion state", error);
        }
    }

    private void createFolder(Object stage) {
        promptFolderName(stage, i18nService.get("sidebar.newFolder"), "")
                .thenAccept(name -> { if (name != null)  CompletableFuture
                        .runAsync(() -> connectionProfileService.saveFolder(null, name, null, activeProjectId), executor)
                        .whenComplete((v, t) -> UiThread.run(this::loadConnections)); });
    }

    private void createSubFolder(Object stage, String parentId) {
        // 先在后台查深度，超限则提示
        CompletableFuture.supplyAsync(() -> connectionProfileService.getFolderDepth(parentId), executor)
                .whenComplete((depth, t) -> UiThread.run(() -> {
                    if (t != null) {
                        showError(i18nService.get("error.saveFailed", t.getMessage()));
                        return;
                    }
                    if (depth + 1 >= maxFolderDepth) {
                        SwingMessages.show(i18nService.get("folder.newSub"), i18nService.get("folder.maxDepthReached", maxFolderDepth), themeService, i18nService);
                        return;
                    }
                    promptFolderName(stage, i18nService.get("folder.newSub"), "")
                            .thenAccept(name -> { if (name != null)  CompletableFuture
                                    .runAsync(() -> connectionProfileService.saveFolder(null, name, parentId, activeProjectId), executor)
                                    .whenComplete((v, t2) -> UiThread.run(this::loadConnections)); });
                }));
    }

    private void renameFolder(Object stage, String folderId, String currentName) {
        promptFolderName(stage, i18nService.get("folder.rename"), currentName)
                .thenAccept(name -> { if (name != null)  CompletableFuture
                        .runAsync(() -> connectionProfileService.renameFolder(folderId, name), executor)
                        .whenComplete((v, t) -> UiThread.run(this::loadConnections)); });
    }

    private int connectTimeoutSeconds() {
        try { return Integer.parseInt(appSettingsService.get("connection.timeout", "10")); }
        catch (NumberFormatException e) { return 10; }
    }

    private void createConnection(Object stage) {
        ConnectionDialog.showAsync(stage, i18nService, themeService, ConnectionFormData.empty(activeProjectId),
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .thenAccept(form -> { if (form != null) UiThread.run(() -> saveConnection(form)); });
    }

    private void createConnectionInFolder(Object stage, String folderId) {
        ConnectionDialog.showAsync(stage, i18nService, themeService, ConnectionFormData.emptyWithFolder(activeProjectId, folderId),
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .thenAccept(form -> { if (form != null) UiThread.run(() -> saveConnection(form)); });
    }

    private void pasteSharedConnection(Object stage) {
        pasteSharedConnection(stage, null);
    }

    private void pasteSharedConnection(Object stage, String folderId) {
        ConnectionDialog.showPasteShareAsync(i18nService).thenAccept(shared -> {
            if (shared == null) return;
            UiThread.run(() -> ConnectionDialog.showAsync(stage, i18nService, themeService, withActiveProject(shared, folderId),
                    connectionProfileService.listProjects(), connectionProfileService.listFolders(activeProjectId),
                    vaultService, this::testConnection, connectTimeoutSeconds())
                    .thenAccept(form -> { if (form != null) UiThread.run(() -> saveConnection(form)); }));
        });
    }

    private ConnectionFormData withActiveProject(ConnectionFormData form, String folderId) {
        return new ConnectionFormData(
                form.id(),
                form.displayName(),
                form.host(),
                form.port(),
                form.username(),
                form.authenticationType(),
                form.password(),
                form.privateKeyPath(),
                form.passphrase(),
                form.hostKeyVerificationMode(),
                form.description(),
                form.defaultRemotePath(),
                form.favorite(),
                activeProjectId,
                form.connectionType(),
                folderId != null ? folderId : form.folderId(),
                form.vaultEntryId(),
                form.keyContent()
        );
    }

    private void duplicateConnection(Object stage, ConnectionProfile source) {
        ConnectionFormData copyData = source.toCopyFormData();
        ConnectionDialog.showAsync(stage, i18nService, themeService, copyData,
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .thenAccept(form -> { if (form != null) UiThread.run(() -> saveConnection(form)); });
    }

    private void copyConnectionShare(Object stage, String connectionId) {
        CompletableFuture.supplyAsync(() -> connectionProfileService.loadForm(connectionId), executor)
                .whenComplete((formData, throwable) -> UiThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("connection.share.loadFailed", throwable.getMessage()));
                        return;
                    }
                    showConnectionShareDialog(stage, formData);
                }));
    }

    private void editSelectedConnection(Object stage) {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> connectionProfileService.loadForm(selected.id()), executor)
                .whenComplete((formData, throwable) -> UiThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                        return;
                    }
                    ConnectionDialog.showAsync(stage, i18nService, themeService, formData,
                            connectionProfileService.listProjects(),
                            connectionProfileService.listFolders(activeProjectId),
                            vaultService,
                            this::testConnection, connectTimeoutSeconds())
                            .thenAccept(form -> { if (form != null) UiThread.run(() -> saveConnection(form)); });
                }));
    }

    private CompletableFuture<String> testConnection(ConnectionDialog.ConnectionTestContext test) {
        com.jlshell.core.model.ConnectionRequest request = test.request();
        return connectionRouteService.route(test.connectionId(), test.projectId(), test.displayName(), request)
                .thenCompose(routed -> sessionManager.openSession(routed.request())
                        .thenCompose(session -> sessionManager.closeSession(session.sessionId())
                                .thenApply(v -> i18nService.get("testConnection.success")))
                        .whenComplete((msg, ex) -> ProgramConnectionRouteService.closeQuietly(routed.lease())))
                .whenComplete((msg, ex) -> {
                    if (request.credential() != null) request.credential().clear();
                });
    }

    private void saveConnection(ConnectionFormData formData) {
        CompletableFuture.supplyAsync(() -> connectionProfileService.save(formData), executor)
                .whenComplete((saved, throwable) -> UiThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                        return;
                    }
                    loadConnections();
                    viewModel.statusMessageProperty().set(i18nService.get("status.connectionSaved"));
                }));
    }

    private String buildDeleteConfirmMessage(DeletePreview preview) {
        StringBuilder sb = new StringBuilder();
        if (!preview.connNames().isEmpty()) {
            sb.append(i18nService.get("confirm.deleteConnections", preview.connNames().size()));
            sb.append("\n");
            for (String name : preview.connNames()) {
                sb.append("  • ").append(name).append("\n");
            }
        }
        if (!preview.folderNames().isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(i18nService.get("confirm.deleteFolders", preview.folderNames().size()));
            sb.append("\n");
            for (String name : preview.folderNames()) {
                sb.append("  📁 ").append(name).append("\n");
            }
        }
        if (!preview.folderConnNames().isEmpty()) {
            sb.append("\n");
            sb.append(i18nService.get("confirm.deleteFolderConnections", preview.folderConnNames().size()));
            sb.append("\n");
            int limit = 10;
            for (int i = 0; i < Math.min(preview.folderConnNames().size(), limit); i++) {
                sb.append("  • ").append(preview.folderConnNames().get(i)).append("\n");
            }
            if (preview.folderConnNames().size() > limit) {
                sb.append("  ... ").append(preview.folderConnNames().size() - limit).append(" more\n");
            }
        }
        return sb.toString().trim();
    }

    private void connectSelected() {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        connectProfile(selected);
    }

    private final java.util.concurrent.ConcurrentMap<String,AutoCloseable> apiBackgroundLeases=new java.util.concurrent.ConcurrentHashMap<>();
    private void installProgramWorkspaceUi() {
        com.jlshell.plugin.loader.HostExtensionRegistry.workspaceUi(new com.jlshell.plugin.loader.WorkspaceUi() {
            public CompletableFuture<String> open(com.jlshell.program.api.ProgramWorkspaceService.OpenRequest request) {
                return CompletableFuture.supplyAsync(() -> connectionProfileService.listProfiles().stream().filter(profile -> profile.id().equals(request.connectionId()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Saved connection not found")),executor)
                        .thenCompose(profile -> openApiConnection(profile,request));
            }
            public CompletableFuture<com.jlshell.program.api.ProgramWorkspaceService.Selection> choose(com.jlshell.program.api.ProgramWorkspaceService.SelectionRequest request) {
                return com.jlshell.plugin.loader.HostExtensionRegistry.workspace().sessions().thenCompose(values ->
                        com.jlshell.ui.swing.WorkspaceSelectionDialog.choose(request,values,i18nService,themeService));
            }
            public CompletableFuture<com.jlshell.program.api.ProgramWorkspaceService.RoleSelection> chooseRoles(com.jlshell.program.api.ProgramWorkspaceService.RoleRequest request) {
                return com.jlshell.plugin.loader.HostExtensionRegistry.workspace().sessions().thenCompose(values ->
                        com.jlshell.ui.swing.WorkspaceSelectionDialog.roles(request,values,i18nService,themeService));
            }
            public CompletableFuture<Void> reveal(String id,String terminalId,boolean focus) {
                return ensureApiWorkspace(id,focus).thenCompose(page -> UiThread.supplyAsync(() -> {
                    if(focus) {
                        workspaceTabs.select(page);
                        if(page instanceof SessionWorkspaceTab ssh) ssh.getTerminalWorkspaceView().focusTerminal(terminalId);
                        else localShellHandles.stream().filter(handle -> handle.sessionId().toString().equals(id)).findFirst().ifPresent(com.jlshell.terminal.service.TerminalViewHandle::requestFocus);
                    }
                    return (Void)null;
                }));
            }
            public CompletableFuture<Void> ensureFiles(String id) {
                return ensureApiWorkspace(id,false).thenCompose(page -> swingWorkbench.thenCompose(view -> UiThread.supplyAsync(() -> { view.ensureFiles(page); return (Void)null; })));
            }
            public CompletableFuture<Void> closeSession(String id) {
                return UiThread.supplyAsync(() -> workspaceTabs.pages().stream().filter(page -> apiSessionId(page).equals(id)).findFirst())
                        .thenCompose(page -> {
                            if(page.isEmpty()) return sessionManager.closeSession(new com.jlshell.core.model.SessionId(java.util.UUID.fromString(id)))
                                    .whenComplete((value,error) -> ProgramConnectionRouteService.closeQuietly(apiBackgroundLeases.remove(id)));
                            WorkspacePage tab=page.get();
                            CompletableFuture<Void> closing;
                            if(tab instanceof SessionWorkspaceTab ssh) closing=ssh.closeWorkspace();
                            else {
                                @SuppressWarnings("unchecked") var close=(java.util.function.Supplier<CompletableFuture<Void>>)tab.getProperties().get("closeAction");
                                closing=close==null?CompletableFuture.completedFuture(null):close.get();
                            }
                            return closing.thenCompose(ignored -> UiThread.supplyAsync(() -> { removeWorkspaceTab(tab); afterWorkspaceTabRemoved(); return (Void)null; }));
                        });
            }
        });
    }
    private final java.util.Map<String,CompletableFuture<WorkspacePage>> apiWorkspacePromotions = new java.util.HashMap<>();
    private CompletableFuture<WorkspacePage> ensureApiWorkspace(String id,boolean focus) {
        synchronized (apiWorkspacePromotions) {
            var pending = apiWorkspacePromotions.get(id);
            if (pending != null) return pending;
            var result = promoteApiWorkspace(id, focus);
            apiWorkspacePromotions.put(id, result);
            result.whenComplete((page, error) -> {
                synchronized (apiWorkspacePromotions) { apiWorkspacePromotions.remove(id, result); }
            });
            return result;
        }
    }
    private CompletableFuture<WorkspacePage> promoteApiWorkspace(String id,boolean focus) {
        return UiThread.supplyAsync(() -> workspaceTabs.pages().stream().filter(page -> id.equals(apiSessionId(page))).findFirst())
                .thenCompose(existing -> {
                    if(existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
                    var binding=com.jlshell.plugin.loader.HostExtensionRegistry.binding(id).orElseThrow(() -> new IllegalStateException("Session has no saved connection metadata"));
                    var session=sessionManager.getSession(new com.jlshell.core.model.SessionId(java.util.UUID.fromString(id))).orElseThrow(() -> new IllegalArgumentException("Session closed"));
                    return CompletableFuture.supplyAsync(() -> connectionProfileService.listProfiles().stream().filter(profile -> profile.id().equals(binding.connectionId())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Saved connection no longer exists")),executor)
                            .thenCompose(profile -> UiThread.supplyAsync(() -> {
                                AutoCloseable lease=apiBackgroundLeases.remove(id);
                                return openWorkspace(profile,session,lease,focus);
                            }).thenCompose(java.util.function.Function.identity()))
                            .thenCompose(ignored -> UiThread.supplyAsync(() -> apiWorkspacePage(id)));
                });
    }
    private String apiSessionId(WorkspacePage page) {
        return page instanceof SessionWorkspaceTab ssh ? ssh.getSessionId() : java.util.Objects.toString(page.getProperties().get("terminalSessionId"),"");
    }
    private WorkspacePage apiWorkspacePage(String id) {
        return workspaceTabs.pages().stream().filter(page -> id.equals(apiSessionId(page))).findFirst()
                .orElseThrow(() -> new IllegalStateException("This session has no terminal workspace"));
    }
    private CompletableFuture<String> openApiConnection(ConnectionProfile profile,com.jlshell.program.api.ProgramWorkspaceService.OpenRequest request) {
        if(profile.connectionType()==ConnectionType.LOCAL_SHELL) {
            if(request.presentation()==com.jlshell.program.api.ProgramWorkspaceService.Presentation.BACKGROUND)
                return CompletableFuture.failedFuture(new UnsupportedOperationException("Local shells require TAB presentation"));
            var terminalRequest=new com.jlshell.terminal.model.TerminalViewRequest(profile.displayName(),null,null,themeService.activeColorScheme(),terminalRuntimeSettings());
            return localShellLauncher.launch(profile.displayName(),terminalRequest).thenCompose(handle -> UiThread.supplyAsync(() -> {
                WorkspacePage previous=workspaceTabs.selected(); openLocalShellTab(profile,handle,request.focus());
                if(!request.focus() && previous!=null) workspaceTabs.select(previous);
                return handle.sessionId().toString();
            }));
        }
        return CompletableFuture.supplyAsync(() -> connectionProfileService.toConnectionRequest(profile.id()),executor)
                .thenCompose(connection -> connectionRouteService.route(profile,connection))
                .thenCompose(routed -> sessionManager.openSession(routed.request()).handle((session,error) -> {
                    if(error!=null) { ProgramConnectionRouteService.closeQuietly(routed.lease()); throw new java.util.concurrent.CompletionException(error); }
                    return new RoutedSshSession(session,routed.lease());
                })).thenCompose(routed -> {
                    String id=routed.session().sessionId().toString();
                    if(request.presentation()==com.jlshell.program.api.ProgramWorkspaceService.Presentation.BACKGROUND) {
                        if (routed.routeLease() != null) apiBackgroundLeases.put(id,routed.routeLease());
                        routed.session().addDisconnectListener(reason -> ProgramConnectionRouteService.closeQuietly(apiBackgroundLeases.remove(id)));
                        return CompletableFuture.completedFuture(id);
                    }
                    return UiThread.supplyAsync(() -> openWorkspace(profile,routed.session(),routed.routeLease(),request.focus())).thenCompose(java.util.function.Function.identity());
                });
    }

    private void connectProfile(ConnectionProfile selected) {
        if (!beginConnecting(selected)) {
            log.debug("Ignoring duplicate connect request for {}", selected.summary());
            return;
        }
        log.info("Connect requested for {}", selected.summary());
        viewModel.statusMessageProperty().set(i18nService.get("status.connecting", selected.summary()));

        try {
            if (selected.connectionType() == ConnectionType.LOCAL_SHELL) {
                connectLocalShell(selected);
            } else {
                connectSsh(selected);
            }
        } catch (Throwable throwable) {
            finishConnecting(selected);
            throw throwable;
        }
    }

    private boolean beginConnecting(ConnectionProfile profile) {
        String id = profile.id();
        if (id == null || id.isBlank()) {
            return true;
        }
        return connectingConnectionIds.add(id);
    }

    private void finishConnecting(ConnectionProfile profile) {
        String id = profile.id();
        if (id == null || id.isBlank()) {
            return;
        }
        connectingConnectionIds.remove(id);
    }

    private void connectLocalShell(ConnectionProfile profile) {
        com.jlshell.terminal.model.TerminalViewRequest request =
                new com.jlshell.terminal.model.TerminalViewRequest(profile.displayName(), null, null,
                        themeService.activeColorScheme(), terminalRuntimeSettings());
        localShellLauncher.launch(profile.displayName(), request)
                .whenComplete((viewHandle, throwable) -> UiThread.run(() -> {
                    try {
                        if (throwable != null) {
                            log.error("Local shell launch failed for {}", profile.displayName(), throwable);
                            showError(i18nService.get("status.connectionFailed",
                                    throwable.getCause() == null ? throwable.getMessage() : throwable.getCause().getMessage()));
                            return;
                        }
                        openLocalShellTab(profile, viewHandle);
                    } finally {
                        finishConnecting(profile);
                    }
                }));
    }

    private com.jlshell.terminal.model.TerminalRuntimeSettings terminalRuntimeSettings() {
        String raw = appSettingsService.get("terminal.scrollback.lines",
                String.valueOf(com.jlshell.terminal.model.TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES));
        try {
            return new com.jlshell.terminal.model.TerminalRuntimeSettings(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return com.jlshell.terminal.model.TerminalRuntimeSettings.defaults();
        }
    }

    private void connectSsh(ConnectionProfile selected) {
        // toConnectionRequest 含 DB 查询 + AES 解密，必须在后台线程执行
        CompletableFuture.supplyAsync(() -> connectionProfileService.toConnectionRequest(selected.id()), executor)
                .thenCompose(request -> connectionRouteService.route(selected, request))
                .thenCompose(routed -> sessionManager.openSession(routed.request())
                        .handle((session, error) -> {
                            if (error != null) {
                                ProgramConnectionRouteService.closeQuietly(routed.lease());
                                throw new java.util.concurrent.CompletionException(error);
                            }
                            return new RoutedSshSession(session, routed.lease());
                        }))
                .whenComplete((routedSession, throwable) -> {
                    if (throwable != null) {
                        var reason = ExceptionMessages.connectionReason(throwable);
                        log.error("SSH connection failed for {}: reason={}", selected.summary(), reason, throwable);
                        UiThread.run(() -> {
                            showError(i18nService.get("status.connectionFailed",
                                    reason == com.jlshell.core.exception.ConnectionException.Reason.UNKNOWN
                                            ? ExceptionMessages.userMessage(throwable)
                                            : i18nService.get("connection.failure." + reason.name().toLowerCase(java.util.Locale.ROOT))));
                            finishConnecting(selected);
                        });
                        return;
                    }
                    com.jlshell.core.session.SshSession sshSession = routedSession.session();
                    reportAccountStats();
                    log.info("SSH connection future completed for session {}", sshSession.sessionId());
                    UiThread.run(() -> {
                        try {
                            openWorkspace(selected, sshSession, routedSession.routeLease());
                        } catch (Throwable t) {
                            ProgramConnectionRouteService.closeQuietly(routedSession.routeLease());
                            finishConnecting(selected);
                            throw t;
                        }
                    });
                });
    }

    private CompletableFuture<String> openWorkspace(ConnectionProfile profile, com.jlshell.core.session.SshSession sshSession,
                               AutoCloseable routeLease) {
        return openWorkspace(profile,sshSession,routeLease,true);
    }
    private CompletableFuture<String> openWorkspace(ConnectionProfile profile, com.jlshell.core.session.SshSession sshSession,
                               AutoCloseable routeLease,boolean focus) {
        CompletableFuture<String> result=new CompletableFuture<>();
        WorkspacePage previous=workspaceTabs.selected();
        log.info("Opening workspace for session {}", sshSession.sessionId());
        // recordSessionOpened 含 DB 写入，移到后台线程
        CompletableFuture.supplyAsync(
                () -> connectionProfileService.recordSessionOpened(sshSession.sessionId(), profile.id(), profile.summary()),
                executor
        ).whenComplete((historyId, throwable) -> UiThread.run(() -> {
            try {
            if (throwable != null) {
                log.warn("Failed to record session history for {}", sshSession.sessionId(), throwable);
            }
            log.info("Session history recorded for session {} with history {}", sshSession.sessionId(), historyId);
            SessionWorkspaceTab tab = new SessionWorkspaceTab(
                    profile,
                    historyId,
                    sshSession,
                    sessionManager,
                    connectionProfileService,
                    terminalViewFactory,
                    fontProfileService,
                    appSettingsService,
                    sftpService,
                    i18nService,
                    themeService,
                    pluginManager,
                    programPluginManager == null ? null : programPluginManager.sessionIntegrationRegistry(),
                    connectionRouteService,
                    routeLease,
                    capabilityBus,
                    storageFactory,
                    this::reportAccountStats
            );
            installWorkspaceTabHeader(tab, profile.displayName());
            if (swingWorkbench != null) {
                com.jlshell.ui.swing.SwingSessionView.create(tab, themeService, i18nService).thenAccept(surface ->
                        withSwing(view -> {
                            if (!workspaceTabs.pages().contains(tab)) {
                                surface.close();
                                return;
                            }
                            view.addTab(tab, profile.displayName(), surface, () -> UiThread.run(() -> closeTab(tab)), profile.summary());
                            if(focus) view.selectTab(tab); else if(previous!=null) view.selectTab(previous);
                        }));
            }
            workspaceTabs.add(tab);
            if(focus || previous==null) workspaceTabs.select(tab);
            log.info("Workspace tab added for session {}", sshSession.sessionId());
            tab.initialize().whenComplete((unused, t) -> UiThread.run(() -> {
                try {
                    if (t != null) {
                        result.completeExceptionally(t);
                        log.error("Workspace initialization failed for session {}", sshSession.sessionId(), t);
                        showError(i18nService.get("status.terminalOpenFailed", t.getMessage()));
                        tab.closeWorkspace().whenComplete((closed, closeError) -> UiThread.run(() -> {
                            removeWorkspaceTab(tab);
                            reportAccountStats();
                            if (closeError != null) {
                                log.warn("Failed to close unusable SSH session {}", sshSession.sessionId(), closeError);
                            }
                        }));
                    } else {
                        if(focus) javax.swing.SwingUtilities.invokeLater(() -> { if(workspaceTabs.selected()==tab && tab.getTerminalWorkspaceView()!=null) tab.getTerminalWorkspaceView().focusTerminal(null); });
                        result.complete(sshSession.sessionId().toString());
                        log.info("Workspace initialization completed for session {}", sshSession.sessionId());
                        viewModel.statusMessageProperty().set(i18nService.get("status.connected", profile.summary()));
                    }
                } finally {
                    finishConnecting(profile);
                }
            }));
            } catch(Throwable error) {
                ProgramConnectionRouteService.closeQuietly(routeLease);
                sessionManager.closeSession(sshSession.sessionId());
                result.completeExceptionally(error);
            }
        }));
        return result;
    }

    private void afterWorkspaceTabRemoved() {
        boolean noOpenWorkspaces = workspaceTabs.pages().isEmpty();
        if (noOpenWorkspaces) publishSwingWelcome();
        if (memoryReclaimService != null) {
            memoryReclaimService.requestAfterWorkspaceClosed(noOpenWorkspaces);
        }
    }

    private void closeTabsLeftOf(WorkspacePage target) {
        int idx = workspaceTabs.pages().indexOf(target);
        if (idx <= 0) return;
        // 复制列表避免 ConcurrentModification
        List<WorkspacePage> toClose = new ArrayList<>(workspaceTabs.pages().subList(0, idx));
        toClose.forEach(this::closeTab);
    }

    private void closeTabsRightOf(WorkspacePage target) {
        int idx = workspaceTabs.pages().indexOf(target);
        if (idx < 0 || idx >= workspaceTabs.pages().size() - 1) return;
        List<WorkspacePage> toClose = new ArrayList<>(workspaceTabs.pages().subList(idx + 1, workspaceTabs.pages().size()));
        toClose.forEach(this::closeTab);
    }

    private void closeAllTabs() {
        List<WorkspacePage> toClose = new ArrayList<>(workspaceTabs.pages());
        toClose.forEach(this::closeTab);
    }

    private ConnectionProfile selectedConnection() {
        ConnectionProfile fromViewModel = viewModel.selectedConnectionProperty().get();
        if (fromViewModel != null) return fromViewModel;
        if (sidebarTreeView != null) {
            return sidebarTreeView.getSelectedConnection(cachedProfiles);
        }
        return null;
    }

    private record DeletePreview(List<String> connNames, List<String> folderNames, List<String> folderConnNames) {}
    private record ConnectionTreeSnapshot(List<FolderProfile> folders, List<ConnectionProfile> profiles, Set<String> collapsedFolderIds) {}
    private record RoutedSshSession(com.jlshell.core.session.SshSession session, AutoCloseable routeLease) {}
    private void configureSidebarModel(Object stage) {
        sidebarTreeView = new SidebarTreeView(i18nService, maxFolderDepth);
        sidebarTreeView.setOnConnect(this::connectSelected);
        sidebarTreeView.setOnEdit(item -> {
            if (item instanceof SidebarItem.ConnectionItem conn) {
                ConnectionProfile profile = cachedProfiles.stream()
                        .filter(p -> p.id().equals(conn.id())).findFirst().orElse(null);
                if (profile != null) {
                    viewModel.selectedConnectionProperty().set(profile);
                    editSelectedConnection(stage);
                }
            }
        });
        sidebarTreeView.setOnDelete(items -> {
            List<SidebarItem.ConnectionItem> conns = items.stream()
                    .filter(i -> i instanceof SidebarItem.ConnectionItem)
                    .map(i -> (SidebarItem.ConnectionItem) i).toList();
            List<SidebarItem.FolderItem> folders = items.stream()
                    .filter(i -> i instanceof SidebarItem.FolderItem)
                    .map(i -> (SidebarItem.FolderItem) i).toList();

            if (conns.isEmpty() && folders.isEmpty()) return;

            CompletableFuture.supplyAsync(() -> {
                List<String> connNames = new ArrayList<>();
                List<String> folderConnNames = new ArrayList<>();
                for (SidebarItem.ConnectionItem ci : conns) connNames.add(ci.displayName());
                for (SidebarItem.FolderItem fi : folders) {
                    folderConnNames.addAll(connectionProfileService.collectConnectionNamesUnderFolder(fi.id()));
                }
                return new DeletePreview(connNames, folders.stream().map(SidebarItem.FolderItem::displayName).toList(), folderConnNames);
            }, executor).thenAccept(preview -> UiThread.run(() -> {
                String msg = buildDeleteConfirmMessage(preview);
                SwingMessages.confirm(i18nService.get("action.deleteConnection"), msg, themeService, i18nService).thenAccept(confirmed -> {
                    if (confirmed) CompletableFuture.runAsync(() -> {
                        for (SidebarItem.ConnectionItem connection : conns) connectionProfileService.delete(connection.id());
                        for (SidebarItem.FolderItem folder : folders) connectionProfileService.deleteFolder(folder.id());
                    }, executor).whenComplete((ignored, error) -> UiThread.run(() -> {
                        if (error != null) showError(ExceptionMessages.userMessage(error)); else loadConnections();
                    }));
                });
            }));
        });
        sidebarTreeView.setOnDuplicate(item -> {
            if (item instanceof SidebarItem.ConnectionItem conn) {
                ConnectionProfile profile = cachedProfiles.stream()
                        .filter(p -> p.id().equals(conn.id())).findFirst().orElse(null);
                if (profile != null) {
                    duplicateConnection(stage, profile);
                }
            }
        });
        sidebarTreeView.setOnCopyShare(item -> {
            if (item instanceof SidebarItem.ConnectionItem conn) {
                copyConnectionShare(stage, conn.id());
            }
        });
        sidebarTreeView.setOnFavorite((id, favorite) -> CompletableFuture
                .runAsync(() -> connectionProfileService.setFavorite(id, favorite), executor)
                .whenComplete((unused, error) -> UiThread.run(() -> {
                    if (error != null) showError(i18nService.get("status.connectionSaveFailed", error.getMessage()));
                    else loadConnections();
                })));
        sidebarTreeView.setOnImportShare(folderId -> pasteSharedConnection(stage, folderId));
        sidebarTreeView.setOnNewSubFolder((parentId, parentDepth) -> createSubFolder(stage, parentId));
        sidebarTreeView.setOnRenameFolder((folderId, currentName) -> renameFolder(stage, folderId, currentName));
        sidebarTreeView.setOnNewConnectionInFolder(folderId -> createConnectionInFolder(stage, folderId));
        sidebarTreeView.setOnNewConnectionInEmpty(() -> createConnection(stage));
        sidebarTreeView.setOnNewFolderInEmpty(() -> createFolder(stage));
        sidebarTreeView.setOnMove((items, targetFolderId) ->
                CompletableFuture.runAsync(() -> {
                    for (SidebarItem item : items) {
                        if (item instanceof SidebarItem.ConnectionItem ci) {
                            connectionProfileService.moveConnectionToFolder(ci.id(), targetFolderId);
                        } else if (item instanceof SidebarItem.FolderItem fi) {
                            connectionProfileService.moveFolderToParent(fi.id(), targetFolderId);
                        }
                    }
                }, executor).whenComplete((v, t) -> UiThread.run(this::loadConnections)));
        sidebarTreeView.setOnCollapsedFoldersChanged(this::saveSidebarCollapsedFolders);

    }

    private void scheduleAutomaticUpdateCheck(Object stage) {
        if (Boolean.getBoolean("jlshell.ui.preview") || updateService == null || !updateService.autoCheckEnabled()) return;
        automaticUpdateTimer = new javax.swing.Timer(6000, event -> updateService.checkLatest(PreferencesDialog.getVersion())
                .whenComplete((response, error) -> UiThread.run(() -> {
                    if (error != null) log.debug("Automatic update check failed", error);
                    else if (response != null && response.updateAvailable() && !updateService.isIgnored(response)) showUpdateAvailable(stage, response);
                })));
        automaticUpdateTimer.setRepeats(false); automaticUpdateTimer.start();
    }
    private void showUpdateAvailable(Object stage, UpdateService.UpdateResponse response) {
        String download = i18nService.get("updates.download"), ignore = i18nService.get("updates.ignore");
        SwingMessages.choose(i18nService.get("updates.title"), i18nService.get("updates.available", response.latestVersion()), List.of(download, ignore), themeService, i18nService)
                .thenAccept(choice -> { if (download.equals(choice)) downloadUpdate(stage, response); else if (ignore.equals(choice)) updateService.ignoreVersion(response.latestVersion()); });
    }
    private void showUpdateMessage(Object stage, String message) { SwingMessages.show(i18nService.get("updates.title"), message, themeService, i18nService); }
    private void showRestartRequiredDialog(Object stage, String filePath) {
        String restart = i18nService.get("updates.restartNow");
        SwingMessages.choose(i18nService.get("updates.restartTitle"), i18nService.get("updates.downloadedRestart", filePath), List.of(restart), themeService, i18nService)
                .thenAccept(choice -> {
                    if (!restart.equals(choice)) return;
                    try { com.jlshell.ui.support.RestartHelper.scheduleRestart(); }
                    catch (IllegalStateException error) { showUpdateError(stage, new UpdateService.UpdateException("Failed to restart", error, "updates.error.restartFailed")); }
                });
    }
    private void showUpdateError(Object stage, Throwable error) {
        log.warn("JLShell update UI flow failed", error);
        SwingMessages.show(i18nService.get("updates.title"), i18nService.get("updates.failed", i18nService.get(UpdateService.userMessageKey(error))), themeService, i18nService);
    }
    private void showConnectionShareDialog(Object stage, ConnectionFormData form) {
        SwingFormDialog.showAsync(i18nService.get("connection.share.title"), "jlshell.connection.share.export", i18nService, themeService, () -> {
            var code = new javax.swing.JTextField(connectionShareService.generateShareCode()); code.setName("connection.share.code");
            var include = new javax.swing.JCheckBox(i18nService.get("connection.share.includeShareCode"));
            var preview = new javax.swing.JTextArea(7, 40); preview.setEditable(false); preview.setLineWrap(true); preview.setWrapStyleWord(true); preview.setName("connection.share.preview");
            var body = new javax.swing.JPanel(new java.awt.BorderLayout(0, 12)); body.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 24, 18, 24));
            var options = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 8));
            options.add(new javax.swing.JLabel(i18nService.get("connection.share.header", form.displayName()))); options.add(code); options.add(include);
            options.add(new javax.swing.JLabel(i18nService.get("connection.share.codeHint")));
            body.add(options, java.awt.BorderLayout.NORTH); body.add(new javax.swing.JScrollPane(preview));
            var error = new javax.swing.JLabel(" "); body.add(error, java.awt.BorderLayout.SOUTH);
            var revision = new java.util.concurrent.atomic.AtomicLong();
            Runnable update = () -> {
                long current = revision.incrementAndGet();
                preview.setText("");
                String value = code.getText().strip();
                boolean includeCode = include.isSelected();
                CompletableFuture.supplyAsync(() -> connectionShareService.exportShareText(form, value, includeCode), executor)
                        .whenComplete((text, failure) -> UiThread.run(() -> { if (revision.get() == current) preview.setText(failure == null ? text : ""); }));
            };
            code.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
            });
            include.addActionListener(event -> update.run()); update.run();
            return new SwingFormDialog.Form<>(body, () -> {
                if (code.getText().isBlank() || preview.getText().isBlank()) { error.setText(i18nService.get("connection.share.codeRequired")); return null; }
                String text = i18nService.get("connection.share.clipboardHeader", Objects.toString(form.host(), "-")) + System.lineSeparator() + preview.getText();
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
                revision.incrementAndGet(); preview.setText(""); code.setText(""); return true;
            }, i18nService.get("connection.share.copyButton"));
        }, 680, 390).thenAccept(copied -> { if (Boolean.TRUE.equals(copied)) SwingMessages.show(i18nService.get("connection.share.title"), i18nService.get("connection.share.copied"), themeService, i18nService); });
    }

}
