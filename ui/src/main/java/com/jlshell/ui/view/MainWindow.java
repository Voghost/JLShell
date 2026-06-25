package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import com.jlshell.api.server.ApiServer;
import com.jlshell.core.model.ConnectionType;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.service.SessionManager;
import com.jlshell.plugin.api.rpc.CapabilityBus;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.terminal.model.TerminalColorScheme;
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
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.viewmodel.MainViewModel;
import com.jlshell.ui.dialog.PreferencesDialog;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ContextMenu;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 主窗口视图。
 */

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
    private final ApiServer apiServer;
    private final CapabilityBus capabilityBus;
    private final VaultService vaultService;
    private final TabPane workspaceTabs = new TabPane();
    private final List<com.jlshell.terminal.service.TerminalViewHandle> localShellHandles = new ArrayList<>();
    private final ListView<ConnectionProfile> connectionListView = new ListView<>();
    private SidebarTreeView sidebarTreeView;
    private WelcomePane welcomePane;
    private VBox topArea; // stored for locale rebuild
    private Label sectionLabel;
    private Label projectSwitchLabel;
    private Label statusLabel;
    private Stage primaryStage;
    /** Sentinel for the "Default" (no project) combo item */
    private static final ProjectProfile DEFAULT_PROJECT = new ProjectProfile(null, "", null);
    /** null = "Default" (connections with no project) */
    private String activeProjectId = null;
    private final ComboBox<ProjectProfile> projectCombo = new ComboBox<>();
    /** Cached profiles for tree selection lookup */
    private java.util.List<ConnectionProfile> cachedProfiles = java.util.List.of();

    private final int maxFolderDepth;

    // ── 搜索过滤 ──
    private javafx.scene.control.TextField searchField;

    // ── 侧边栏折叠/展开 ──
    private boolean sidebarVisible = true;
    private SplitPane centerSplitPane;
    private VBox sidebarVBox;
    private Button revealSidebarBtn;

    // ── 顶栏折叠/展开 ──
    private boolean topBarCollapsed = false;
    private javafx.animation.PauseTransition collapseDelay;
    private CustomTitleBar customTitleBar; // Windows 专用
    private StackPane workspaceStack; // workspace StackPane
    private Button topBarCollapseBtn; // 顶栏中的折叠按钮（▾）
    private Button topBarExpandBtn; // 折叠后的展开按钮（▴）
    private Region topHoverZone; // 折叠后顶部 4px hover 感应条
    /** 折叠后的免疫期：刚折叠时不响应 hover 展开，避免按钮点击瞬间触发 mouseEntered */
    private long collapseImmuneUntil = 0;

    // ── 插件存储工厂 ──
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
            ApiServer apiServer,
            CapabilityBus capabilityBus,
            java.util.function.Function<String, com.jlshell.plugin.api.storage.PluginStorage> storageFactory
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
        this.vaultService = vaultService;
        this.maxFolderDepth = maxFolderDepth;
        this.pluginManager = pluginManager;
        this.apiServer = apiServer;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;

        // Restore saved active project
        String savedProject = appSettingsService.get("ui.activeProject", "");
        if (savedProject != null && !savedProject.isBlank()) {
            this.activeProjectId = savedProject;
        }
    }

    public Scene createScene(Stage stage) {
        this.primaryStage = stage;
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            root.setTop(buildCustomTitleBar(stage));
        } else {
            root.setTop(buildTopArea(stage));
        }
        root.setCenter(buildCenterArea(stage));
        root.setBottom(buildStatusBar());

        // Adapt initial and minimum window size to available screen area.
        // On Windows with 150% DPI scaling at 1920×1080, the logical
        // resolution is ~1280×720 (minus taskbar).  Hard-coding 1480×920
        // and minWidth/minHeight of 1200/780 makes the window fill or
        // overflow the screen, so we compute adaptive values instead.
        Screen screen = Screen.getPrimary();
        Rectangle2D vis = screen.getVisualBounds();
        double availW = vis.getWidth();
        double availH = vis.getHeight();

        // Initial size: 90 % of available area, capped at 1480×920
        double initW = Math.min(1480, Math.floor(availW * 0.90));
        double initH = Math.min(920, Math.floor(availH * 0.90));

        // Minimum size: never below 640×480 (absolute usable minimum),
        // but allow shrinking well below the screen size (60 %) so the
        // window does not feel locked to nearly-fullscreen on small screens.
        double minW = Math.max(640, Math.floor(availW * 0.60));
        double minH = Math.max(480, Math.floor(availH * 0.60));

        Scene scene = new Scene(root, initW, initH);

        // On Windows, the stage is UNDECORATED — make the scene background
        // transparent so the rounded corners and border of .app-root are visible.
        if (isWindows) {
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        }

        stage.setMinWidth(minW);
        stage.setMinHeight(minH);

        // Centre the window on screen once it has been shown
        stage.setOnShown(e -> {
            stage.setX(vis.getMinX() + (availW - stage.getWidth()) / 2);
            stage.setY(vis.getMinY() + (availH - stage.getHeight()) / 2);
        });

        // On Windows, the stage is UNDECORATED so we need manual edge resize.
        if (isWindows) {
            root.getStyleClass().add("win-undecorated");
            installWindowResizeHandler(stage, scene);
            // 最大化时去掉圆角和边框间距，还原时恢复
            stage.maximizedProperty().addListener((obs, wasMax, isMax) -> {
                if (isMax) {
                    root.getStyleClass().add("win-maximized");
                } else {
                    root.getStyleClass().remove("win-maximized");
                }
            });
        }

        themeService.apply(scene);
        viewModel.activeThemeProperty().bind(themeService.currentThemeProperty());
        themeService.currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            themeService.apply(scene);
            TerminalColorScheme scheme = themeService.activeColorScheme();
            workspaceTabs.getTabs().stream()
                    .filter(SessionWorkspaceTab.class::isInstance)
                    .map(SessionWorkspaceTab.class::cast)
                    .forEach(tab -> tab.applyColorScheme(scheme));
            pluginManager.setThemeName(newTheme.name().toLowerCase());
        });

        themeService.activeColorSchemeProperty().addListener((obs, oldScheme, newScheme) -> {
            workspaceTabs.getTabs().stream()
                    .filter(SessionWorkspaceTab.class::isInstance)
                    .map(SessionWorkspaceTab.class::cast)
                    .forEach(tab -> tab.applyColorScheme(newScheme));
            localShellHandles.forEach(handle -> handle.updateColorScheme(newScheme));
        });

        i18nService.localeProperty().addListener((obs, oldLocale, newLocale) -> {
            pluginManager.setLocale(newLocale);
        });

        loadConnections();
        return scene;
    }

    private MenuBar buildMenuBar(Stage stage) {
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
            ProjectManagerDialog.show(stage, connectionProfileService, i18nService, themeService, activeProjectId,
                    projectId -> FxThread.run(() -> switchProject(projectId)));
            rebuildProjectsMenu(projectsMenu, stage);
            refreshProjectCombo();
            loadConnections();
        });
        projectsMenu.getItems().add(manageProjects);
        rebuildProjectsMenu(projectsMenu, stage);

        // Manage Vault
        MenuItem manageVault = new MenuItem(i18nService.get("vault.menu.manage"));
        manageVault.setOnAction(e -> {
            com.jlshell.ui.dialog.VaultManagerDialog.show(
                    stage, vaultService, i18nService, themeService, activeProjectId,
                    connectionProfileService.listProjects(), () -> FxThread.run(this::loadConnections));
        });

        fileMenu.getItems().addAll(newConnection, refreshConnections, projectsMenu, manageVault, new SeparatorMenuItem(), exit);

        // View 菜单
        Menu viewMenu = new Menu(i18nService.get("menu.view"));
        MenuItem toggleSidebarItem = new MenuItem(i18nService.get("sidebar.toggle"));
        toggleSidebarItem.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
        toggleSidebarItem.setOnAction(event -> toggleSidebar());
        MenuItem collapseTopBarItem = new MenuItem(i18nService.get("topbar.collapse"));
        collapseTopBarItem.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        collapseTopBarItem.setOnAction(event -> toggleTopBarCollapse());
        MenuItem darkTheme = new MenuItem(i18nService.get("theme.dark"));
        MenuItem lightTheme = new MenuItem(i18nService.get("theme.light"));
        darkTheme.setOnAction(event -> themeService.setTheme(AppTheme.DARK));
        lightTheme.setOnAction(event -> themeService.setTheme(AppTheme.LIGHT));
        viewMenu.getItems().addAll(toggleSidebarItem, collapseTopBarItem, new SeparatorMenuItem(), darkTheme, lightTheme);

        // Preferences menu item
        // On macOS, JavaFX automatically moves a MenuItem with Cmd+, shortcut to the app menu.
        MenuItem preferences = new MenuItem(i18nService.get("action.preferences"));
        preferences.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        preferences.setOnAction(event -> openPreferences(stage));

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (isMac) {
            // Add to fileMenu; JavaFX moves Preferences with Cmd+, to the macOS app menu
            fileMenu.getItems().add(4, preferences);
            menuBar.getMenus().addAll(fileMenu, viewMenu);
        } else {
            Menu settingsMenu = new Menu(i18nService.get("menu.settings"));
            settingsMenu.getItems().add(preferences);
            menuBar.getMenus().addAll(fileMenu, viewMenu, settingsMenu);
        }
        return menuBar;
    }

    private VBox buildTopArea(Stage stage) {
        MenuBar menuBar = buildMenuBar(stage);
        menuBar.setUseSystemMenuBar(true);

        // macOS 系统菜单栏下，JavaFX MenuBar 节点高度为 0，
        // 所以不在这里放折叠按钮（折叠按钮放在 workspace overlay 上）
        topArea = new VBox(menuBar);
        topArea.getStyleClass().add("top-shell");
        return topArea;
    }

    private CustomTitleBar buildCustomTitleBar(Stage stage) {
        MenuBar menuBar = buildMenuBar(stage);
        customTitleBar = new CustomTitleBar(stage, menuBar, i18nService);
        return customTitleBar;
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
        selectProjectCombo();
        loadConnections();
    }

    private void switchProjectWithConfirm(ProjectProfile selected) {
        if (selected == null) return; // can happen during combo rebuild
        String newProjectId = (selected == DEFAULT_PROJECT) ? null : selected.id();
        if (Objects.equals(newProjectId, activeProjectId)) return;

        String projectName = (selected == DEFAULT_PROJECT)
                ? i18nService.get("project.label.default")
                : selected.name();

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                i18nService.get("project.switch.detail", projectName),
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(i18nService.get("project.switch.confirm", projectName));
        themeService.applyToDialog(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused ->
                switchProject(newProjectId));

        // Revert combo if cancelled
        if (!Objects.equals(activeProjectId, newProjectId)) {
            selectProjectCombo();
        }
    }

    private void refreshProjectCombo() {
        String saved = activeProjectId;
        projectCombo.getItems().clear();
        projectCombo.getItems().add(DEFAULT_PROJECT);
        projectCombo.getItems().addAll(connectionProfileService.listProjects());
        activeProjectId = saved;
        selectProjectCombo();
    }

    private void selectProjectCombo() {
        if (activeProjectId == null) {
            projectCombo.getSelectionModel().select(DEFAULT_PROJECT);
        } else {
            projectCombo.getItems().stream()
                    .filter(p -> p != DEFAULT_PROJECT && p.id().equals(activeProjectId))
                    .findFirst()
                    .ifPresentOrElse(
                            projectCombo.getSelectionModel()::select,
                            () -> projectCombo.getSelectionModel().select(DEFAULT_PROJECT));
        }
    }


    public void openPreferences(Stage stage) {
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                connectionProfileService, activeProjectId, apiServer);
        // 导入后刷新侧边栏
        loadConnections();
    }

    private void refreshAllTexts(Stage stage, BorderPane root) {
        boolean wasTopBarCollapsed = topBarCollapsed;
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            root.setTop(buildCustomTitleBar(stage));
        } else {
            root.setTop(buildTopArea(stage));
        }
        // Rebuild sidebar and welcome pane to refresh all labels
        if (root.getCenter() instanceof SplitPane splitPane && splitPane.getItems().size() >= 2) {
            String savedSearch = searchField != null ? searchField.getText() : "";
            sidebarVBox = buildSidebar(stage);
            splitPane.getItems().set(0, sidebarVBox);
            // Rebuild the workspace StackPane with fresh WelcomePane
            welcomePane = new WelcomePane(i18nService, connectionProfileService, executor,
                    () -> createConnection(stage),
                    () -> createFolder(stage),
                    connectionId -> {
                        cachedProfiles.stream()
                                .filter(p -> p.id().equals(connectionId))
                                .findFirst()
                                .ifPresent(profile -> {
                                    viewModel.selectedConnectionProperty().set(profile);
                                    connectSelected();
                                });
                    });
            welcomePane.visibleProperty().bind(Bindings.isEmpty(workspaceTabs.getTabs()));
            StackPane workspaceStackPane = new StackPane(welcomePane, workspaceTabs, revealSidebarBtn, topBarCollapseBtn, topBarExpandBtn, topHoverZone);
            workspaceStack = workspaceStackPane;
            splitPane.getItems().set(1, workspaceStackPane);
            // 恢复搜索过滤
            if (!savedSearch.isBlank() && searchField != null) {
                searchField.setText(savedSearch);
                sidebarTreeView.applyFilter(savedSearch);
            }
            applySidebarVisibility();
            // 恢复顶栏折叠状态
            if (wasTopBarCollapsed) {
                applyTopBarCollapsed(true);
            }
        }
    }

    private SplitPane buildCenterArea(Stage stage) {
        sidebarVBox = buildSidebar(stage);
        workspaceTabs.getStyleClass().add("workspace-tabs");
        installTabDragReorder(workspaceTabs);

        welcomePane = new WelcomePane(i18nService, connectionProfileService, executor,
                () -> createConnection(stage),
                () -> createFolder(stage),
                connectionId -> {
                    cachedProfiles.stream()
                            .filter(p -> p.id().equals(connectionId))
                            .findFirst()
                            .ifPresent(profile -> {
                                viewModel.selectedConnectionProperty().set(profile);
                                connectSelected();
                            });
                });
        welcomePane.visibleProperty().bind(Bindings.isEmpty(workspaceTabs.getTabs()));
        workspaceTabs.visibleProperty().bind(Bindings.isNotEmpty(workspaceTabs.getTabs()));

        // 侧边栏折叠后的 reveal 按钮（workspace 左下角，避免遮挡 tab 标签）
        revealSidebarBtn = new Button();
        Region panelIcon = loadSvgShape("/icons/sidebar-left.svg", 12);
        if (panelIcon != null) revealSidebarBtn.setGraphic(panelIcon);
        revealSidebarBtn.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("sidebar.toggle")));
        revealSidebarBtn.getStyleClass().add("sidebar-reveal-btn");
        revealSidebarBtn.setOnAction(e -> toggleSidebar());
        revealSidebarBtn.setVisible(false);
        StackPane.setAlignment(revealSidebarBtn, javafx.geometry.Pos.BOTTOM_LEFT);
        StackPane.setMargin(revealSidebarBtn, new Insets(0, 0, 4, 4));

        // ── 顶栏折叠按钮 overlay（workspace 右上角，外层 tab header 右侧） ──
        topBarCollapseBtn = new Button("▾");
        topBarCollapseBtn.getStyleClass().add("topbar-collapse-btn");
        topBarCollapseBtn.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("topbar.collapse")));
        topBarCollapseBtn.setOnAction(e -> {
            toggleTopBarCollapse();
        });
        StackPane.setAlignment(topBarCollapseBtn, javafx.geometry.Pos.TOP_RIGHT);
        StackPane.setMargin(topBarCollapseBtn, new Insets(2, 4, 0, 0));
        // 初始无 tab 时不显示；有 tab 时由 applyTopBarCollapsed 管理
        topBarCollapseBtn.setVisible(false);
        workspaceTabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) c -> {
            if (!topBarCollapsed && !c.getList().isEmpty()) {
                topBarCollapseBtn.setVisible(true);
            }
        });

        // ── 折叠后的展开按钮（透明悬浮在终端右上角） ──
        topBarExpandBtn = new Button("▴");
        topBarExpandBtn.getStyleClass().add("topbar-expand-btn");
        topBarExpandBtn.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("topbar.expand")));
        topBarExpandBtn.setOnAction(e -> {
            topBarCollapsed = false;
            applyTopBarCollapsed(false);
            removeTopBarExitListener();
        });
        topBarExpandBtn.setVisible(false);
        StackPane.setAlignment(topBarExpandBtn, javafx.geometry.Pos.TOP_RIGHT);
        StackPane.setMargin(topBarExpandBtn, new Insets(4, 4, 0, 0));

        // ── 折叠后顶部 hover 感应条（4px 透明区域） ──
        // 折叠后终端（SwingNode）占满区域，StackPane 的 mouseMoved 不会被触发
        // 在顶部放一条 4px 透明感应条，鼠标进入即展开
        topHoverZone = new Region();
        topHoverZone.setPrefHeight(4);
        topHoverZone.setMaxHeight(4);
        topHoverZone.setMinHeight(4);
        topHoverZone.setStyle("-fx-background-color: transparent;");
        topHoverZone.setVisible(false);
        StackPane.setAlignment(topHoverZone, javafx.geometry.Pos.TOP_CENTER);
        topHoverZone.setOnMouseEntered(e -> {
            if (topBarCollapsed && System.currentTimeMillis() > collapseImmuneUntil
                    && "true".equals(appSettingsService.get("ui.topbar.hoverExpand", "false"))) {
                topBarCollapsed = false;
                applyTopBarCollapsed(false);
                installTopBarExitListener();
            }
        });

        StackPane workspaceStackPane = new StackPane(welcomePane, workspaceTabs, revealSidebarBtn, topBarCollapseBtn, topBarExpandBtn, topHoverZone);
        workspaceStack = workspaceStackPane;

        // 延迟折叠防抖
        collapseDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        collapseDelay.setOnFinished(e -> {
            if (topBarCollapsed) {
                applyTopBarCollapsed(true);
            }
        });

        centerSplitPane = new SplitPane(sidebarVBox, workspaceStackPane);
        centerSplitPane.setDividerPositions(0.26);
        return centerSplitPane;
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
            }, executor).thenAccept(preview -> FxThread.run(() -> {
                String msg = buildDeleteConfirmMessage(preview);
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.OK, ButtonType.CANCEL);
                themeService.applyToDialog(alert);
                alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(unused ->
                        CompletableFuture.runAsync(() -> {
                            for (SidebarItem.ConnectionItem ci : conns) connectionProfileService.delete(ci.id());
                            for (SidebarItem.FolderItem fi : folders) connectionProfileService.deleteFolder(fi.id());
                        }, executor).whenComplete((v, t) -> FxThread.run(this::loadConnections)));
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



        // 紧凑图标按钮行 — SVG 图标
        Button createButton    = svgIconButton("/icons/add.svg",          i18nService.get("action.newConnection"),    () -> createConnection(stage));
        Button editButton      = svgIconButton("/icons/edit.svg",         i18nService.get("action.editConnection"), () -> editSelectedConnection(stage));
        Button deleteButton    = svgIconButton("/icons/delete.svg",       i18nService.get("action.deleteConnection"), this::deleteSelectedConnection);
        Button newFolderButton = svgIconButton("/icons/folder.svg",       i18nService.get("sidebar.newFolder"),       () -> createFolder(stage));
        Button connectButton   = svgIconButton("/icons/runo24.svg",       i18nService.get("action.connect"),          this::connectSelected);
        Button refreshButton   = svgIconButton("/icons/refresh.svg",      i18nService.get("action.refresh"), this::loadConnections);
        Button toggleSidebarBtn = svgIconButton("/icons/sidebar-left.svg", i18nService.get("sidebar.toggle"), this::toggleSidebar);
        Button settingsButton  = svgIconButton("/icons/settings.svg",     i18nService.get("action.preferences"), () -> openPreferences(stage));
        connectButton.getStyleClass().add("icon-btn-primary");

        HBox actionBar = new HBox(4, createButton, editButton, deleteButton, newFolderButton,
                new javafx.scene.layout.Region(), connectButton, refreshButton, toggleSidebarBtn);
        HBox.setHgrow(actionBar.getChildren().get(4), Priority.ALWAYS);
        actionBar.getStyleClass().add("sidebar-action-bar");

        // 搜索框 — 放在 sectionLabel 和 TreeView 之间
        searchField = new javafx.scene.control.TextField();
        searchField.setPromptText(i18nService.get("sidebar.search.prompt"));
        searchField.getStyleClass().add("sidebar-search-field");
        Region searchIcon = loadSvgShape("/icons/search.svg", 14);
        if (searchIcon != null) searchIcon.getStyleClass().add("sidebar-search-icon");
        Button clearSearchBtn = new Button("✕");
        clearSearchBtn.getStyleClass().add("sidebar-search-clear");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.visibleProperty().bind(searchField.textProperty().isEmpty().not());
        clearSearchBtn.setOnAction(e -> searchField.clear());
        HBox searchRow = new HBox(4, searchIcon != null ? searchIcon : new Region(), searchField, clearSearchBtn);
        searchRow.getStyleClass().add("sidebar-search-row");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) ->
                sidebarTreeView.applyFilter(newVal));

        sectionLabel = new Label(i18nService.get("sidebar.connections"));
        sectionLabel.getStyleClass().add("sidebar-section-label");

        projectSwitchLabel = new Label(i18nService.get("project.switch.label"));
        projectSwitchLabel.getStyleClass().add("sidebar-project-label");

        projectCombo.getStyleClass().add("sidebar-project-combo");
        projectCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : (item == DEFAULT_PROJECT ? i18nService.get("project.label.default") : item.name()));
            }
        });
        projectCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : (item == DEFAULT_PROJECT ? i18nService.get("project.label.default") : item.name()));
            }
        });
        projectCombo.getItems().add(DEFAULT_PROJECT);
        projectCombo.getItems().addAll(connectionProfileService.listProjects());
        selectProjectCombo();
        projectCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                switchProjectWithConfirm(newVal));

        Button manageProjectBtn = svgIconButton("/icons/project.svg", i18nService.get("project.menu.manage"),
                () -> {
                    ProjectManagerDialog.show(stage, connectionProfileService, i18nService, themeService, activeProjectId,
                            projectId -> FxThread.run(() -> switchProject(projectId)));
                    refreshProjectCombo();
                    loadConnections();
                });

        HBox projectRow = new HBox(6, projectSwitchLabel, projectCombo, manageProjectBtn, settingsButton);
        projectRow.getStyleClass().add("sidebar-project-row");
        HBox.setHgrow(projectCombo, Priority.ALWAYS);

        VBox sidebar = new VBox(0, projectRow, sectionLabel, searchRow, sidebarTreeView.getTreeView(), actionBar);
        sidebar.getStyleClass().add("sidebar");
        VBox.setVgrow(sidebarTreeView.getTreeView(), Priority.ALWAYS);
        return sidebar;
    }

    private Label buildStatusBar() {
        statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.statusMessageProperty());
        statusLabel.getStyleClass().add("status-bar");
        return statusLabel;
    }

    private Button svgIconButton(String iconResourcePath, String tooltip, Runnable action) {
        Region icon = loadSvgShape(iconResourcePath, 16);
        Button button = new Button();
        if (icon != null) {
            button.setGraphic(icon);
        }
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        button.getStyleClass().add("icon-btn");
        button.setOnAction(e -> action.run());
        return button;
    }

    /** 从 SVG 文件提取 path 数据，返回 Region（通过 -fx-shape CSS 显示） */
    private Region loadSvgShape(String resourcePath, double size) {
        try (var is = MainWindow.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            String content = new String(is.readAllBytes());
            String pathData = extractSvgPath(content);
            if (pathData == null) return null;
            Region region = new Region();
            region.setMinSize(size, size);
            region.setMaxSize(size, size);
            region.setPrefSize(size, size);
            javafx.scene.shape.SVGPath svg = new javafx.scene.shape.SVGPath();
            svg.setContent(pathData);
            region.setShape(svg);
            region.setStyle("-fx-scale-shape:true;");
            region.getStyleClass().add("action-bar-icon");
            return region;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSvgPath(String svgContent) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < svgContent.length()) {
            int start = svgContent.indexOf("d=\"", idx);
            if (start == -1) break;
            // Ensure 'd' is a standalone attribute (preceded by whitespace or start-of-tag)
            if (start > 0 && Character.isLetterOrDigit(svgContent.charAt(start - 1))) {
                idx = start + 3;
                continue;
            }
            start += 3;
            int end = svgContent.indexOf("\"", start);
            if (end == -1) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(svgContent.substring(start, end));
            idx = end + 1;
        }
        return sb.isEmpty() ? null : sb.toString();
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

    // ── 侧边栏折叠/展开 ────────────────────────────────────────────

    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        applySidebarVisibility();
    }

    private void applySidebarVisibility() {
        if (sidebarVisible) {
            sidebarVBox.setPrefWidth(Region.USE_COMPUTED_SIZE);
            sidebarVBox.setMinWidth(Region.USE_COMPUTED_SIZE);
            sidebarVBox.setMaxWidth(Region.USE_COMPUTED_SIZE);
            centerSplitPane.setDividerPositions(0.26);
            revealSidebarBtn.setVisible(false);
        } else {
            sidebarVBox.setPrefWidth(0);
            sidebarVBox.setMinWidth(0);
            sidebarVBox.setMaxWidth(0);
            centerSplitPane.setDividerPositions(0);
            revealSidebarBtn.setVisible(true);
        }
    }

    // ── 顶栏折叠/展开 ────────────────────────────────────────────

    private void toggleTopBarCollapse() {
        topBarCollapsed = !topBarCollapsed;
        applyTopBarCollapsed(topBarCollapsed);
    }

    private void applyTopBarCollapsed(boolean collapsed) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (collapsed) {
            // 设置免疫期：600ms 内不响应 hover 展开
            collapseImmuneUntil = System.currentTimeMillis() + 600;
            // macOS/Linux: 隐藏菜单栏区域（系统菜单栏不占 JavaFX 空间）
            // Windows: 菜单栏始终保留可见
            if (!isWindows && topArea != null) {
                topArea.setManaged(false);
                topArea.setVisible(false);
            }
            // 外层 TabPane 隐藏 tab header（程序化，CSS 不可靠）
            setTabHeaderVisible(workspaceTabs, false);
            // 隐藏折叠按钮 overlay
            topBarCollapseBtn.setVisible(false);
            topBarCollapseBtn.setManaged(false);
            // 内层 TabPane 隐藏 tab header
            for (javafx.scene.control.Tab t : workspaceTabs.getTabs()) {
                if (t instanceof SessionWorkspaceTab swt) {
                    setTabHeaderVisible(swt.getInnerTabPane(), false);
                    // 隐藏终端工具栏（IP/CPU/Mem/Disk + 插件按钮 + 字体设置）
                    swt.setToolbarVisible(false);
                }
            }
            // 显示展开按钮（透明悬浮在终端右上角）
            topBarExpandBtn.setVisible(true);
            // 显示顶部 hover 感应条
            topHoverZone.setVisible(true);
        } else {
            // 恢复菜单栏
            if (!isWindows && topArea != null) {
                topArea.setManaged(true);
                topArea.setVisible(true);
            }
            // 外层 TabPane 恢复 tab header
            setTabHeaderVisible(workspaceTabs, true);
            // 恢复折叠按钮 overlay
            topBarCollapseBtn.setManaged(true);
            topBarCollapseBtn.setVisible(true);
            // 内层 TabPane 恢复 tab header
            for (javafx.scene.control.Tab t : workspaceTabs.getTabs()) {
                if (t instanceof SessionWorkspaceTab swt) {
                    setTabHeaderVisible(swt.getInnerTabPane(), true);
                    // 恢复终端工具栏
                    swt.setToolbarVisible(true);
                }
            }
            // 隐藏展开按钮和 hover 感应条
            topBarExpandBtn.setVisible(false);
            topHoverZone.setVisible(false);
        }
    }

    /**
     * 程序化控制 TabPane 的 tab-header-area 显隐。
     * CSS visibility/height 对 JavaFX 内部布局无效，必须直接操作子节点。
     * @return true 如果找到并操作了 tab-header-area 节点
     */
    /**
     * 程序化控制 TabPane 的 tab-header-area 显隐。
     *
     * JavaFX TabPaneSkin 用绝对定位布局，setManaged(false) 不会让
     * content area 自动扩展。必须同时把 header 的 prefHeight/maxHeight/minHeight
     * 设为 0，Skin 在 layoutChildren 中才会把全部空间分配给 content area。
     */
    private boolean setTabHeaderVisible(TabPane tabPane, boolean visible) {
        for (javafx.scene.Node node : tabPane.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area") && node instanceof Region header) {
                header.setVisible(visible);
                if (visible) {
                    header.setManaged(true);
                    header.setPrefHeight(Region.USE_COMPUTED_SIZE);
                    header.setMaxHeight(Region.USE_PREF_SIZE);
                    header.setMinHeight(Region.USE_PREF_SIZE);
                } else {
                    header.setManaged(false);
                    header.setPrefHeight(0);
                    header.setMaxHeight(0);
                    header.setMinHeight(0);
                }
                tabPane.requestLayout();
                return true;
            }
        }
        return false;
    }

    /**
     * 展开状态下，鼠标离开顶栏区域后延迟折叠。
     * 在外层 TabPane 的 tab-header-area 上安装 mouse-exit 监听，
     * 加上 topBarNode（菜单栏）的 mouse-exit 监听。
     * 两者之一收到 mouse-entered 都取消延迟折叠。
     */
    private void installTopBarExitListener() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        // 找到顶栏区域的 Node（macOS/Linux 才需要，Windows 菜单栏始终可见）
        javafx.scene.Node topBarNode = null;
        if (!isWindows && topArea != null) {
            topBarNode = topArea;
        }

        // 找到外层 tab-header-area
        javafx.scene.Node outerTabHeader = null;
        for (javafx.scene.Node node : workspaceTabs.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area")) {
                outerTabHeader = node;
                break;
            }
        }

        // 给所有顶栏区域节点安装 mouse-exit/enter 监听
        List<javafx.scene.Node> topBarNodes = new ArrayList<>();
        if (topBarNode != null) topBarNodes.add(topBarNode);
        if (outerTabHeader != null) topBarNodes.add(outerTabHeader);

        for (javafx.scene.Node node : topBarNodes) {
            node.setOnMouseExited(e -> {
                if (!topBarCollapsed) {
                    // 检查鼠标是否真的离开了所有顶栏区域节点
                    javafx.geometry.Point2D mousePoint = new javafx.geometry.Point2D(e.getScreenX(), e.getScreenY());
                    boolean stillInAny = topBarNodes.stream().anyMatch(n -> {
                        if (!n.isVisible()) return false;
                        javafx.geometry.Bounds screenBounds = n.localToScreen(n.getBoundsInLocal());
                        return screenBounds != null && screenBounds.contains(mousePoint);
                    });
                    if (!stillInAny) {
                        topBarCollapsed = true;
                        collapseDelay.playFromStart();
                    }
                }
            });
            node.setOnMouseEntered(e -> {
                collapseDelay.stop();
                topBarCollapsed = false;
            });
        }
    }

    /** 清除顶栏 exit 监听 */
    private void removeTopBarExitListener() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        javafx.scene.Node topBarNode = null;
        if (!isWindows && topArea != null) {
            topBarNode = topArea;
        }
        if (topBarNode != null) {
            topBarNode.setOnMouseExited(null);
            topBarNode.setOnMouseEntered(null);
        }

        for (javafx.scene.Node node : workspaceTabs.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area")) {
                node.setOnMouseExited(null);
                node.setOnMouseEntered(null);
                break;
            }
        }
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
                // 重新应用当前搜索过滤
                if (searchField != null && !searchField.getText().isBlank()) {
                    sidebarTreeView.applyFilter(searchField.getText());
                }
            }
            if (welcomePane != null) {
                welcomePane.refresh();
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
                        themeService.applyToDialog(alert);
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
        themeService.applyToDialog(dialog);
        return dialog.showAndWait()
                .map(String::trim)
                .filter(s -> !s.isBlank());
    }

    private int connectTimeoutSeconds() {
        try { return Integer.parseInt(appSettingsService.get("connection.timeout", "10")); }
        catch (NumberFormatException e) { return 10; }
    }

    private void createConnection(Stage stage) {
        ConnectionDialog.show(stage, i18nService, themeService, ConnectionFormData.empty(activeProjectId),
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .ifPresent(form -> saveConnection(form));
    }

    private void createConnectionInFolder(Stage stage, String folderId) {
        ConnectionDialog.show(stage, i18nService, themeService, ConnectionFormData.emptyWithFolder(activeProjectId, folderId),
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .ifPresent(form -> saveConnection(form));
    }

    private void duplicateConnection(Stage stage, ConnectionProfile source) {
        ConnectionFormData copyData = source.toCopyFormData();
        ConnectionDialog.show(stage, i18nService, themeService, copyData,
                connectionProfileService.listProjects(),
                connectionProfileService.listFolders(activeProjectId),
                vaultService,
                this::testConnection, connectTimeoutSeconds())
                .ifPresent(form -> saveConnection(form));
    }

    private void editSelectedConnection(Stage stage) {
        ConnectionProfile selected = selectedConnection();
        if (selected == null) {
            return;
        }
        CompletableFuture.supplyAsync(() -> connectionProfileService.loadForm(selected.id()), executor)
                .whenComplete((formData, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("status.connectionSaveFailed", throwable.getMessage()));
                        return;
                    }
                    ConnectionDialog.show(stage, i18nService, themeService, formData,
                            connectionProfileService.listProjects(),
                            connectionProfileService.listFolders(activeProjectId),
                            vaultService,
                            this::testConnection, connectTimeoutSeconds())
                            .ifPresent(this::saveConnection);
                }));
    }

    private CompletableFuture<String> testConnection(com.jlshell.core.model.ConnectionRequest request) {
        return sessionManager.openSession(request)
                .thenCompose(session -> sessionManager.closeSession(session.sessionId())
                        .thenApply(v -> i18nService.get("testConnection.success")))
                .whenComplete((msg, ex) -> {
                    if (ex == null && request.credential() != null) request.credential().clear();
                });
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
        themeService.applyToDialog(alert);
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

    private record DeletePreview(List<String> connNames, List<String> folderNames, List<String> folderConnNames) {}

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
        localShellHandles.add(viewHandle);
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(profile.displayName());
        tab.setClosable(true);
        tab.setContextMenu(buildTabContextMenu(tab));
        javax.swing.JComponent component = (javax.swing.JComponent) viewHandle.component();
        javafx.embed.swing.SwingNode swingNode = new javafx.embed.swing.SwingNode();
        swingNode.setFocusTraversable(true);
        swingNode.setCursor(javafx.scene.Cursor.TEXT);
        swingNode.setContent(component);
        swingNode.focusedProperty().addListener((obs, oldFocused, focused) -> {
            if (focused) {
                viewHandle.requestFocus();
            }
        });
        swingNode.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> viewHandle.requestFocus());
        com.jlshell.ui.support.SwingNodeImeBridge.attach(swingNode, viewHandle);
        tab.setContent(swingNode);
        tab.setOnCloseRequest(event -> {
            event.consume();
            viewHandle.closeAsync().whenComplete((unused, t) -> FxThread.run(() -> {
                localShellHandles.remove(viewHandle);
                workspaceTabs.getTabs().remove(tab);
            }));
        });
        workspaceTabs.getTabs().add(tab);
        workspaceTabs.getSelectionModel().select(tab);
        FxThread.run(viewHandle::requestFocus);
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
                    themeService,
                    pluginManager,
                    capabilityBus,
                    storageFactory
            );
            tab.setClosable(true);
            tab.setContextMenu(buildTabContextMenu(tab));
            tab.setOnCloseRequest(event -> {
                event.consume();
                tab.closeWorkspace().whenComplete((unused, t) -> FxThread.run(() -> {
                    workspaceTabs.getTabs().remove(tab);
                    if (workspaceTabs.getTabs().isEmpty() && welcomePane != null) {
                        welcomePane.refresh();
                    }
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

    /**
     * 为工作区 Tab 构建右键菜单：关闭当前 / 关闭左边所有 / 关闭右边所有 / 关闭所有 / 复制会话。
     */
    private ContextMenu buildTabContextMenu(javafx.scene.control.Tab tab) {
        I18nService i18n = i18nService;

        MenuItem closeCurrent = new MenuItem(i18n.get("tab.close"));
        closeCurrent.setOnAction(e -> closeTab(tab));

        MenuItem closeLeft = new MenuItem(i18n.get("tab.closeLeft"));
        closeLeft.setOnAction(e -> closeTabsLeftOf(tab));

        MenuItem closeRight = new MenuItem(i18n.get("tab.closeRight"));
        closeRight.setOnAction(e -> closeTabsRightOf(tab));

        MenuItem closeAll = new MenuItem(i18n.get("tab.closeAll"));
        closeAll.setOnAction(e -> closeAllTabs());

        MenuItem duplicate = new MenuItem(i18n.get("tab.duplicateSession"));
        duplicate.setOnAction(e -> {
            if (tab instanceof SessionWorkspaceTab swt) {
                // 复制会话：用同一连接配置新建 SSH 连接
                connectSsh(swt.getConnectionProfile());
            }
        });

        // 非会话 Tab（本地 Shell）不显示"复制会话"
        ContextMenu menu = new ContextMenu();
        if (tab instanceof SessionWorkspaceTab) {
            menu.getItems().addAll(closeCurrent, closeLeft, closeRight, closeAll,
                    new SeparatorMenuItem(), duplicate);
        } else {
            menu.getItems().addAll(closeCurrent, closeLeft, closeRight, closeAll);
        }

        // 菜单显示时刷新禁用状态
        menu.setOnShowing(ev -> {
            int idx = workspaceTabs.getTabs().indexOf(tab);
            closeLeft.setDisable(idx <= 0);
            closeRight.setDisable(idx >= workspaceTabs.getTabs().size() - 1);
            closeAll.setDisable(workspaceTabs.getTabs().isEmpty());
        });

        return menu;
    }

    /** 关闭单个 Tab（SSH / 本地 Shell 通用） */
    private void closeTab(javafx.scene.control.Tab tab) {
        if (tab instanceof SessionWorkspaceTab swt) {
            swt.closeWorkspace().whenComplete((unused, t) -> FxThread.run(() -> {
                workspaceTabs.getTabs().remove(swt);
                if (workspaceTabs.getTabs().isEmpty() && welcomePane != null) {
                    welcomePane.refresh();
                }
                if (t != null) {
                    showError(i18nService.get("status.sessionCloseFailed", t.getMessage()));
                }
            }));
        } else {
            // 本地 Shell Tab
            tab.getOnCloseRequest().handle(new javafx.event.Event(tab, tab, javafx.scene.control.Tab.TAB_CLOSE_REQUEST_EVENT));
        }
    }

    /** 关闭目标 Tab 左侧的所有 Tab */
    private void closeTabsLeftOf(javafx.scene.control.Tab target) {
        int idx = workspaceTabs.getTabs().indexOf(target);
        if (idx <= 0) return;
        // 复制列表避免 ConcurrentModification
        List<javafx.scene.control.Tab> toClose = new ArrayList<>(workspaceTabs.getTabs().subList(0, idx));
        toClose.forEach(this::closeTab);
    }

    /** 关闭目标 Tab 右侧的所有 Tab */
    private void closeTabsRightOf(javafx.scene.control.Tab target) {
        int idx = workspaceTabs.getTabs().indexOf(target);
        if (idx < 0 || idx >= workspaceTabs.getTabs().size() - 1) return;
        List<javafx.scene.control.Tab> toClose = new ArrayList<>(workspaceTabs.getTabs().subList(idx + 1, workspaceTabs.getTabs().size()));
        toClose.forEach(this::closeTab);
    }

    /** 关闭所有工作区 Tab */
    private void closeAllTabs() {
        List<javafx.scene.control.Tab> toClose = new ArrayList<>(workspaceTabs.getTabs());
        toClose.forEach(this::closeTab);
    }

    /**
     * 为 TabPane 安装 Tab 拖动重排功能。
     * 拖动时根据鼠标位移计算目标位置并重排 Tab 顺序。
     */
    private void installTabDragReorder(TabPane tabPane) {
        // 记录拖动起始信息，存储在 TabPane 的 properties 中避免反射
        final String DRAG_TAB = "tabDragSourceTab";
        final String DRAG_START_X = "tabDragStartX";
        final String DRAG_INDEX = "tabDragFromIndex";

        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            // 点击时 TabPane 已自动切换选中 Tab
            javafx.scene.control.Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            // 只在 Tab 头部区域响应（y 在 Tab 头部高度内）
            double headerHeight = getTabHeaderAreaHeight(tabPane);
            if (e.getY() > headerHeight) return;

            tabPane.getProperties().put(DRAG_TAB, selected);
            tabPane.getProperties().put(DRAG_START_X, e.getScreenX());
            tabPane.getProperties().put(DRAG_INDEX, tabPane.getTabs().indexOf(selected));
        });

        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!tabPane.getProperties().containsKey(DRAG_TAB)) return;
            Object dragTabObj = tabPane.getProperties().get(DRAG_TAB);
            if (!(dragTabObj instanceof javafx.scene.control.Tab dragTab)) return;
            if (!tabPane.getTabs().contains(dragTab)) return;

            double startX = (Double) tabPane.getProperties().get(DRAG_START_X);
            double dx = e.getScreenX() - startX;
            if (Math.abs(dx) < 20) return; // 至少拖动 20px

            double tabWidth = estimateTabHeaderWidth(tabPane);
            if (tabWidth <= 0) return;

            int fromIndex = tabPane.getTabs().indexOf(dragTab);
            int offset = (int) Math.round(dx / tabWidth);
            int toIndex = Math.max(0, Math.min(tabPane.getTabs().size() - 1, fromIndex + offset));
            if (toIndex == fromIndex) return;

            tabPane.getTabs().remove(dragTab);
            tabPane.getTabs().add(toIndex, dragTab);
            tabPane.getSelectionModel().select(dragTab);

            // 更新基准，实现连续拖动
            tabPane.getProperties().put(DRAG_START_X, e.getScreenX());
        });

        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            tabPane.getProperties().remove(DRAG_TAB);
            tabPane.getProperties().remove(DRAG_START_X);
            tabPane.getProperties().remove(DRAG_INDEX);
        });
    }

    /** 获取 Tab 头部区域高度 */
    private double getTabHeaderAreaHeight(TabPane tabPane) {
        for (javafx.scene.Node node : tabPane.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area")) {
                return node.getLayoutBounds().getHeight();
            }
        }
        return 36; // 默认值
    }

    /** 估算单个 Tab 头部的宽度 */
    private double estimateTabHeaderWidth(TabPane tabPane) {
        int count = tabPane.getTabs().size();
        if (count <= 0) return 0;
        if (count == 1) return tabPane.getWidth();
        for (javafx.scene.Node node : tabPane.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains("tab-header-area")) {
                for (javafx.scene.Node child : ((javafx.scene.layout.Pane) node).getChildrenUnmodifiable()) {
                    if (child.getStyleClass().contains("headers-region")) {
                        return child.getLayoutBounds().getWidth() / count;
                    }
                }
            }
        }
        return tabPane.getWidth() / count;
    }

    private ConnectionProfile selectedConnection() {
        ConnectionProfile fromViewModel = viewModel.selectedConnectionProperty().get();
        if (fromViewModel != null) return fromViewModel;
        if (sidebarTreeView != null) {
            return sidebarTreeView.getSelectedConnection(cachedProfiles);
        }
        return null;
    }

    private void installWindowResizeHandler(Stage stage, Scene scene) {
        final int border = 6;
        final Map<javafx.scene.Cursor, String> resizeDirections = Map.of(
                javafx.scene.Cursor.W_RESIZE, "W",
                javafx.scene.Cursor.E_RESIZE, "E",
                javafx.scene.Cursor.N_RESIZE, "N",
                javafx.scene.Cursor.S_RESIZE, "S",
                javafx.scene.Cursor.NW_RESIZE, "NW",
                javafx.scene.Cursor.NE_RESIZE, "NE",
                javafx.scene.Cursor.SW_RESIZE, "SW",
                javafx.scene.Cursor.SE_RESIZE, "SE"
        );

        javafx.scene.Cursor[] activeCursor = {null};
        double[] startDragX = {0}, startDragY = {0};
        double[] startStageX = {0}, startStageY = {0}, startW = {0}, startH = {0};

        // 窗口获得焦点时重置光标，避免从其他程序切换回来时
        // 光标还停留在 resize 箭头样式
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                scene.setCursor(javafx.scene.Cursor.DEFAULT);
                activeCursor[0] = null;
            }
        });

        scene.setOnMouseMoved(e -> {
            if (stage.isMaximized() || stage.isFullScreen()) {
                scene.setCursor(javafx.scene.Cursor.DEFAULT);
                activeCursor[0] = null;
                return;
            }
            double x = e.getSceneX(), y = e.getSceneY();
            double w = scene.getWidth(), h = scene.getHeight();
            boolean onLeft = x < border, onRight = x > w - border;
            boolean onTop = y < border, onBottom = y > h - border;
            javafx.scene.Cursor cursor = javafx.scene.Cursor.DEFAULT;
            if (onTop && onLeft)       cursor = javafx.scene.Cursor.NW_RESIZE;
            else if (onTop && onRight) cursor = javafx.scene.Cursor.NE_RESIZE;
            else if (onBottom && onLeft)  cursor = javafx.scene.Cursor.SW_RESIZE;
            else if (onBottom && onRight) cursor = javafx.scene.Cursor.SE_RESIZE;
            else if (onLeft)   cursor = javafx.scene.Cursor.W_RESIZE;
            else if (onRight)  cursor = javafx.scene.Cursor.E_RESIZE;
            else if (onTop)    cursor = javafx.scene.Cursor.N_RESIZE;
            else if (onBottom) cursor = javafx.scene.Cursor.S_RESIZE;
            scene.setCursor(cursor);
            activeCursor[0] = cursor;
        });

        scene.setOnMousePressed(e -> {
            if (activeCursor[0] != null && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                startDragX[0] = e.getScreenX();
                startDragY[0] = e.getScreenY();
                startStageX[0] = stage.getX();
                startStageY[0] = stage.getY();
                startW[0] = stage.getWidth();
                startH[0] = stage.getHeight();
                e.consume();
            }
        });

        scene.setOnMouseDragged(e -> {
            if (activeCursor[0] == null) return;
            String dir = resizeDirections.get(activeCursor[0]);
            if (dir == null) return;
            double dx = e.getScreenX() - startDragX[0];
            double dy = e.getScreenY() - startDragY[0];
            double newX = startStageX[0], newY = startStageY[0];
            double newW = startW[0], newH = startH[0];
            if (dir.contains("W")) { newX += dx; newW -= dx; }
            if (dir.contains("E")) { newW += dx; }
            if (dir.contains("N")) { newY += dy; newH -= dy; }
            if (dir.contains("S")) { newH += dy; }
            // Enforce minimum size
            if (newW < stage.getMinWidth()) {
                if (dir.contains("W")) newX = startStageX[0] + startW[0] - stage.getMinWidth();
                newW = stage.getMinWidth();
            }
            if (newH < stage.getMinHeight()) {
                if (dir.contains("N")) newY = startStageY[0] + startH[0] - stage.getMinHeight();
                newH = stage.getMinHeight();
            }
            stage.setX(newX);
            stage.setY(newY);
            stage.setWidth(newW);
            stage.setHeight(newH);
            // Keep cursor consistent during drag
            scene.setCursor(activeCursor[0]);
            e.consume();
        });

        scene.setOnMouseReleased(e -> {
            if (activeCursor[0] != null) {
                activeCursor[0] = null;
                scene.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        });
    }

    private void showError(String message) {
        viewModel.statusMessageProperty().set(message);
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        themeService.applyToDialog(alert);
        alert.showAndWait();
    }
}
