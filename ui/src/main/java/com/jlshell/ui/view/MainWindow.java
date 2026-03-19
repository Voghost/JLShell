package com.jlshell.ui.view;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.plugin.loader.PluginManager;
import com.jlshell.ui.dialog.ProjectManagerDialog;
import com.jlshell.ui.model.ConnectionFormData;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.service.ConnectionProfileService;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.service.LocalShellLauncher;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.viewmodel.MainViewModel;
import com.jlshell.ui.dialog.PreferencesDialog;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.stage.Stage;
import org.springframework.stereotype.Component;

/**
 * 主窗口视图。
 */
@Component
public class MainWindow {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

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
    private final TabPane workspaceTabs = new TabPane();
    private final ListView<ConnectionProfile> connectionListView = new ListView<>();
    private SidebarTreeView sidebarTreeView;
    /** null = "Default" (connections with no project) */
    private String activeProjectId = null;
    private final Label projectLabel = new Label();
    /** Cached profiles for tree selection lookup */
    private java.util.List<ConnectionProfile> cachedProfiles = java.util.List.of();

    private final int maxFolderDepth;

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
            @org.springframework.beans.factory.annotation.Value("${jlshell.sidebar.maxFolderDepth:5}") int maxFolderDepth,
            PluginManager pluginManager
    ) {
        this.viewModel = viewModel;
        this.connectionProfileService = connectionProfileService;
        this.sessionManager = sessionManager;
        this.terminalViewFactory = terminalViewFactory;
        this.fontProfileService = fontProfileService;
        this.appSettingsService = appSettingsService;
        this.sftpService = sftpService;
        this.themeService = themeService;
        this.i18nService = i18nService;
        this.localShellLauncher = localShellLauncher;
        this.executor = sshConnectionExecutor;
        this.maxFolderDepth = maxFolderDepth;
        this.pluginManager = pluginManager;
    }

    public Scene createScene(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildTopArea(stage));
        root.setCenter(buildCenterArea(stage));
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1480, 920);
        themeService.apply(scene);
        viewModel.activeThemeProperty().bind(themeService.currentThemeProperty());
        themeService.currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            themeService.apply(scene);
            workspaceTabs.getTabs().stream()
                    .filter(SessionWorkspaceTab.class::isInstance)
                    .map(SessionWorkspaceTab.class::cast)
                    .forEach(tab -> tab.applyTheme(newTheme));
        });

        loadConnections();
        return scene;
    }

    private VBox buildTopArea(Stage stage) {
        MenuBar menuBar = new MenuBar();

        // File 菜单
        Menu fileMenu = new Menu(i18nService.get("menu.file"));
        MenuItem newConnection = new MenuItem(i18nService.get("action.newConnection"));
        MenuItem refreshConnections = new MenuItem(i18nService.get("action.refresh"));
        MenuItem exit = new MenuItem(i18nService.get("action.exit"));
        newConnection.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        refreshConnections.setAccelerator(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN));
        exit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        newConnection.setOnAction(event -> createConnection(stage));
        refreshConnections.setOnAction(event -> loadConnections());
        exit.setOnAction(event -> stage.close());

        Menu projectsMenu = new Menu(i18nService.get("project.menu.projects"));
        MenuItem manageProjects = new MenuItem(i18nService.get("project.menu.manage"));
        manageProjects.setOnAction(e -> {
            ProjectManagerDialog.show(stage, connectionProfileService, i18nService);
            rebuildProjectsMenu(projectsMenu, stage);
            loadConnections();
        });
        projectsMenu.getItems().add(manageProjects);
        rebuildProjectsMenu(projectsMenu, stage);

        fileMenu.getItems().addAll(newConnection, refreshConnections, projectsMenu, new SeparatorMenuItem(), exit);

        // View 菜单
        Menu viewMenu = new Menu(i18nService.get("menu.view"));
        MenuItem darkTheme = new MenuItem(i18nService.get("theme.dark"));
        MenuItem lightTheme = new MenuItem(i18nService.get("theme.light"));
        darkTheme.setOnAction(event -> themeService.setTheme(AppTheme.DARK));
        lightTheme.setOnAction(event -> themeService.setTheme(AppTheme.LIGHT));
        viewMenu.getItems().addAll(darkTheme, lightTheme);

        // Preferences 菜单（macOS 下会自动移到 App 菜单）
        Menu appMenu = new Menu(i18nService.get("menu.app"));
        MenuItem preferences = new MenuItem(i18nService.get("action.preferences"));
        preferences.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        preferences.setOnAction(event -> openPreferences(stage));
        appMenu.getItems().add(preferences);

        menuBar.getMenus().addAll(appMenu, fileMenu, viewMenu);
        menuBar.setUseSystemMenuBar(true);

        VBox box = new VBox(menuBar);
        box.getStyleClass().add("top-shell");
        return box;
    }

    private void rebuildProjectsMenu(Menu projectsMenu, Stage stage) {
        // Keep "Manage Projects..." as last item; rebuild project radio items before it
        projectsMenu.getItems().removeIf(item -> item instanceof RadioMenuItem);
        ToggleGroup group = new ToggleGroup();

        RadioMenuItem defaultItem = new RadioMenuItem(i18nService.get("project.label.default"));
        defaultItem.setToggleGroup(group);
        defaultItem.setSelected(activeProjectId == null);
        defaultItem.setOnAction(e -> switchProject(null));
        projectsMenu.getItems().add(0, defaultItem);

        java.util.List<ProjectProfile> projects = connectionProfileService.listProjects();
        for (int i = 0; i < projects.size(); i++) {
            ProjectProfile p = projects.get(i);
            RadioMenuItem item = new RadioMenuItem(p.name());
            item.setToggleGroup(group);
            item.setSelected(p.id().equals(activeProjectId));
            item.setOnAction(e -> switchProject(p.id()));
            projectsMenu.getItems().add(i + 1, item);
        }
    }

    private void switchProject(String projectId) {
        activeProjectId = projectId;
        appSettingsService.set("ui.activeProject", projectId != null ? projectId : "");
        updateProjectLabel();
        loadConnections();
    }

    private void updateProjectLabel() {
        if (activeProjectId == null) {
            projectLabel.setText(i18nService.get("project.label.default"));
        } else {
            connectionProfileService.listProjects().stream()
                    .filter(p -> p.id().equals(activeProjectId))
                    .findFirst()
                    .ifPresentOrElse(
                            p -> projectLabel.setText(p.name()),
                            () -> projectLabel.setText(i18nService.get("project.label.default"))
                    );
        }
    }

    private void openPreferences(Stage stage) {
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService);
    }

    private SplitPane buildCenterArea(Stage stage) {
        VBox sidebar = buildSidebar(stage);
        workspaceTabs.getStyleClass().add("workspace-tabs");
        SplitPane splitPane = new SplitPane(sidebar, workspaceTabs);
        splitPane.setDividerPositions(0.26);
        return splitPane;
    }

    private VBox buildSidebar(Stage stage) {
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
        sidebarTreeView.setOnDelete(item -> {
            if (item instanceof SidebarItem.ConnectionItem conn) {
                ConnectionProfile profile = cachedProfiles.stream()
                        .filter(p -> p.id().equals(conn.id())).findFirst().orElse(null);
                if (profile != null) {
                    viewModel.selectedConnectionProperty().set(profile);
                    deleteSelectedConnection();
                }
            } else if (item instanceof SidebarItem.FolderItem folder) {
                CompletableFuture.runAsync(() -> connectionProfileService.deleteFolder(folder.id()), executor)
                        .whenComplete((v, t) -> FxThread.run(this::loadConnections));
            }
        });
        sidebarTreeView.setOnNewSubFolder((parentId, parentDepth) -> createSubFolder(stage, parentId));
        sidebarTreeView.setOnRenameFolder((folderId, currentName) -> renameFolder(stage, folderId, currentName));
        sidebarTreeView.setOnMove((items, targetFolderId) ->
                CompletableFuture.runAsync(() -> {
                    for (SidebarItem item : items) {
                        if (item instanceof SidebarItem.ConnectionItem ci) {
                            connectionProfileService.moveConnectionToFolder(ci.id(), targetFolderId);
                        } else if (item instanceof SidebarItem.FolderItem fi) {
                            connectionProfileService.moveFolderToParent(fi.id(), targetFolderId);
                        }
                    }
                }, executor).whenComplete((v, t) -> FxThread.run(this::loadConnections)));

        sidebarTreeView.getTreeView().getSelectionModel().selectedItemProperty()
                .addListener((obs, ov, nv) -> {
                    if (nv != null && nv.getValue() instanceof SidebarItem.ConnectionItem conn) {
                        cachedProfiles.stream().filter(p -> p.id().equals(conn.id()))
                                .findFirst().ifPresent(viewModel.selectedConnectionProperty()::set);
                    } else {
                        viewModel.selectedConnectionProperty().set(null);
                    }
                });



        // 紧凑图标按钮行 — Lucide SVG icons
        Button createButton    = svgIconButton("M12 5v14M5 12h14",          i18nService.get("action.newConnection"),    () -> createConnection(stage));
        Button editButton      = svgIconButton("M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z", i18nService.get("action.editConnection"), () -> editSelectedConnection(stage));
        Button deleteButton    = svgIconButton("M3 6h18M8 6V4h8v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6",    i18nService.get("action.deleteConnection"), this::deleteSelectedConnection);
        Button newFolderButton = svgIconButton("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2zM12 11v6M9 14h6",                         i18nService.get("sidebar.newFolder"),       () -> createFolder(stage));
        Button connectButton   = svgIconButton("M5 3l14 9-14 9V3z",         i18nService.get("action.connect"),          this::connectSelected);
        Button refreshButton   = svgIconButton("M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15", i18nService.get("action.refresh"), this::loadConnections);
        connectButton.getStyleClass().add("icon-btn-primary");

        HBox actionBar = new HBox(4, createButton, editButton, deleteButton, newFolderButton,
                new javafx.scene.layout.Region(), connectButton, refreshButton);
        HBox.setHgrow(actionBar.getChildren().get(4), Priority.ALWAYS);
        actionBar.getStyleClass().add("sidebar-action-bar");

        Label sectionLabel = new Label(i18nService.get("sidebar.connections"));
        sectionLabel.getStyleClass().add("sidebar-section-label");

        projectLabel.getStyleClass().add("sidebar-project-label");
        updateProjectLabel();

        VBox sidebar = new VBox(0, sectionLabel, projectLabel, sidebarTreeView.getTreeView(), actionBar);
        sidebar.getStyleClass().add("sidebar");
        VBox.setVgrow(sidebarTreeView.getTreeView(), Priority.ALWAYS);
        return sidebar;
    }

    private Label buildStatusBar() {
        Label statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.statusMessageProperty());
        statusLabel.getStyleClass().add("status-bar");
        return statusLabel;
    }

    private Button svgIconButton(String svgPath, String tooltip, Runnable action) {
        javafx.scene.layout.Region icon = new javafx.scene.layout.Region();
        icon.setStyle(String.format(
                "-fx-min-width:14px;-fx-min-height:14px;-fx-max-width:14px;-fx-max-height:14px;" +
                "-fx-pref-width:14px;-fx-pref-height:14px;" +
                "-fx-shape:\"%s\";-fx-scale-shape:true;", svgPath));
        icon.getStyleClass().add("action-bar-icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        button.getStyleClass().add("icon-btn");
        button.setOnAction(e -> action.run());
        return button;
    }

    private Button iconButton(String icon, String tooltip, Runnable action) {
        Button button = new Button(icon);
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        button.getStyleClass().add("icon-btn");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button toolbarButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private void loadConnections() {
        final String projectId = activeProjectId;
        CompletableFuture.supplyAsync(() -> {
            java.util.List<ConnectionProfile> profiles = connectionProfileService.listProfilesByProject(projectId);
            java.util.List<FolderProfile> folders = connectionProfileService.listFolders(projectId);
            return java.util.Map.entry(folders, profiles);
        }, executor).whenComplete((entry, throwable) -> FxThread.run(() -> {
            if (throwable != null) {
                showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                return;
            }
            cachedProfiles = entry.getValue();
            viewModel.replaceConnections(entry.getValue());
            if (sidebarTreeView != null) {
                sidebarTreeView.populate(entry.getKey(), entry.getValue());
            }
            viewModel.statusMessageProperty().set(
                    i18nService.get("status.connectionsLoaded", viewModel.connections().size()));
        }));
    }

    private void createFolder(Stage stage) {
        promptFolderName(stage, i18nService.get("sidebar.newFolder"), "")
                .ifPresent(name -> CompletableFuture
                        .runAsync(() -> connectionProfileService.saveFolder(null, name, null, activeProjectId), executor)
                        .whenComplete((v, t) -> FxThread.run(this::loadConnections)));
    }

    private void createSubFolder(Stage stage, String parentId) {
        // 先在后台查深度，超限则提示
        CompletableFuture.supplyAsync(() -> connectionProfileService.getFolderDepth(parentId), executor)
                .whenComplete((depth, t) -> FxThread.run(() -> {
                    if (depth + 1 >= maxFolderDepth) {
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                                javafx.scene.control.Alert.AlertType.WARNING);
                        alert.setHeaderText(null);
                        alert.setContentText(i18nService.get("folder.maxDepthReached", maxFolderDepth));
                        alert.showAndWait();
                        return;
                    }
                    promptFolderName(stage, i18nService.get("folder.newSub"), "")
                            .ifPresent(name -> CompletableFuture
                                    .runAsync(() -> connectionProfileService.saveFolder(null, name, parentId, activeProjectId), executor)
                                    .whenComplete((v, t2) -> FxThread.run(this::loadConnections)));
                }));
    }

    private void renameFolder(Stage stage, String folderId, String currentName) {
        promptFolderName(stage, i18nService.get("folder.rename"), currentName)
                .ifPresent(name -> CompletableFuture
                        .runAsync(() -> connectionProfileService.renameFolder(folderId, name), executor)
                        .whenComplete((v, t) -> FxThread.run(this::loadConnections)));
    }

    private java.util.Optional<String> promptFolderName(Stage stage, String title, String initial) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(initial);
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(i18nService.get("sftp.newFolder.prompt"));
        return dialog.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank());
    }

    private void createConnection(Stage stage) {
        ConnectionDialog.show(stage, i18nService, ConnectionFormData.empty(),
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId))
                .ifPresent(form -> saveConnection(form));
    }

    private void editSelectedConnection(Stage stage) {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        // loadForm 含 DB 查询，移到后台线程，拿到结果后回 FX 线程弹对话框
        CompletableFuture.supplyAsync(() -> connectionProfileService.loadForm(selected.id()), executor)
                .whenComplete((formData, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                        return;
                    }
                    ConnectionDialog.show(stage, i18nService, formData,
                            connectionProfileService.listProjects(),
                            connectionProfileService.listFolders(activeProjectId))
                            .ifPresent(this::saveConnection);
                }));
    }

    private void saveConnection(ConnectionFormData formData) {
        CompletableFuture.supplyAsync(() -> connectionProfileService.save(formData), executor)
                .whenComplete((saved, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                        return;
                    }
                    loadConnections();
                    viewModel.statusMessageProperty().set(i18nService.get("status.connectionSaved"));
                }));
    }

    private void deleteSelectedConnection() {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                i18nService.get("confirm.deleteConnection", selected.displayName()),
                ButtonType.OK,
                ButtonType.CANCEL);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused ->
                CompletableFuture.runAsync(() -> connectionProfileService.delete(selected.id()), executor)
                        .whenComplete((v, throwable) -> FxThread.run(() -> {
                            if (throwable != null) {
                                showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                                return;
                            }
                            loadConnections();
                        }))
        );
    }

    private void connectSelected() {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        log.info("Connect requested for {}", selected.summary());
        viewModel.statusMessageProperty().set(i18nService.get("status.connecting", selected.summary()));

        if (selected.connectionType() == ConnectionType.LOCAL_SHELL) {
            connectLocalShell(selected);
        } else {
            connectSsh(selected);
        }
    }

    private void connectLocalShell(ConnectionProfile profile) {
        com.jlshell.terminal.model.TerminalViewRequest request =
                new com.jlshell.terminal.model.TerminalViewRequest(profile.displayName(), null, null, null);
        localShellLauncher.launch(profile.displayName(), request)
                .whenComplete((viewHandle, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        log.error("Local shell launch failed for {}", profile.displayName(), throwable);
                        showError(i18nService.get("status.connectionFailed",
                                throwable.getCause() == null ? throwable.getMessage() : throwable.getCause().getMessage()));
                        return;
                    }
                    openLocalShellTab(profile, viewHandle);
                }));
    }

    private void openLocalShellTab(ConnectionProfile profile, com.jlshell.terminal.service.TerminalViewHandle viewHandle) {
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(profile.displayName());
        tab.setClosable(true);
        javafx.embed.swing.SwingNode swingNode = new javafx.embed.swing.SwingNode();
        swingNode.setContent((javax.swing.JComponent) viewHandle.component());
        tab.setContent(swingNode);
        tab.setOnCloseRequest(event -> {
            event.consume();
            viewHandle.closeAsync().whenComplete((unused, t) -> FxThread.run(() -> {
                workspaceTabs.getTabs().remove(tab);
            }));
        });
        workspaceTabs.getTabs().add(tab);
        workspaceTabs.getSelectionModel().select(tab);
        viewHandle.requestFocus();
        viewModel.statusMessageProperty().set(i18nService.get("status.connected", profile.displayName()));
    }

    private void connectSsh(ConnectionProfile selected) {
        // toConnectionRequest 含 DB 查询 + AES 解密，必须在后台线程执行
        CompletableFuture.supplyAsync(() -> connectionProfileService.toConnectionRequest(selected.id()), executor)
                .thenCompose(sessionManager::openSession)
                .whenComplete((sshSession, throwable) -> {
                    if (throwable != null) {
                        log.error("SSH connection failed for {}", selected.summary(), throwable);
                        FxThread.run(() -> showError(i18nService.get("status.connectionFailed",
                                throwable.getCause() == null ? throwable.getMessage() : throwable.getCause().getMessage())));
                        return;
                    }
                    log.info("SSH connection future completed for session {}", sshSession.sessionId());
                    FxThread.run(() -> openWorkspace(selected, sshSession));
                });
    }

    private void openWorkspace(ConnectionProfile profile, com.jlshell.core.session.SshSession sshSession) {
        log.info("Opening workspace for session {}", sshSession.sessionId());
        // recordSessionOpened 含 DB 写入，移到后台线程
        CompletableFuture.supplyAsync(
                () -> connectionProfileService.recordSessionOpened(sshSession.sessionId(), profile.id(), profile.summary()),
                executor
        ).whenComplete((historyId, throwable) -> FxThread.run(() -> {
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
                    themeService.currentTheme(),
                    pluginManager
            );
            tab.setClosable(true);
            tab.setOnCloseRequest(event -> {
                event.consume();
                tab.closeWorkspace().whenComplete((unused, t) -> FxThread.run(() -> {
                    workspaceTabs.getTabs().remove(tab);
                    if (t != null) {
                        showError(i18nService.get("status.sessionCloseFailed", t.getMessage()));
                    }
                }));
            });
            workspaceTabs.getTabs().add(tab);
            workspaceTabs.getSelectionModel().select(tab);
            log.info("Workspace tab added for session {}", sshSession.sessionId());
            tab.initialize().whenComplete((unused, t) -> FxThread.run(() -> {
                if (t != null) {
                    log.error("Workspace initialization failed for session {}", sshSession.sessionId(), t);
                    showError(i18nService.get("status.terminalOpenFailed", t.getMessage()));
                    workspaceTabs.getTabs().remove(tab);
                } else {
                    log.info("Workspace initialization completed for session {}", sshSession.sessionId());
                    viewModel.statusMessageProperty().set(i18nService.get("status.connected", profile.summary()));
                }
            }));
        }));
    }

    private ConnectionProfile selectedConnection() {
        ConnectionProfile fromViewModel = viewModel.selectedConnectionProperty().get();
        if (fromViewModel != null) return fromViewModel;
        if (sidebarTreeView != null) {
            return sidebarTreeView.getSelectedConnection(cachedProfiles);
        }
        return null;
    }

    private void showError(String message) {
        viewModel.statusMessageProperty().set(message);
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.showAndWait();
    }
}
