package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import com.jlshell.ui.service.VaultService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.viewmodel.MainViewModel;
import com.jlshell.ui.dialog.AboutDialog;
import com.jlshell.ui.dialog.PreferencesDialog;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 主窗口视图。
 */

public class MainWindow {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);
    private static final String TERMINAL_SWING_NODE_STYLE_CLASS = "terminal-swing-node";
    private static final String WORKSPACE_TAB_TITLE_KEY = "workspaceTabTitle";
    private static final String SETTINGS_SIDEBAR_VISIBLE = "ui.sidebar.visible";
    private static final String SETTINGS_SIDEBAR_DIVIDER_POSITION = "ui.sidebar.dividerPosition";
    private static final String SETTINGS_SIDEBAR_WIDTH = "ui.sidebar.width";
    private static final String SETTINGS_TOPBAR_COLLAPSED = "ui.topbar.collapsed";
    private static final String SETTINGS_FOCUS_MODE = "ui.focusMode";
    private static final String SETTINGS_FOCUS_SIDEBAR_BEFORE = "ui.focusMode.sidebarVisibleBefore";
    private static final String SETTINGS_FOCUS_TOPBAR_BEFORE = "ui.focusMode.topbarCollapsedBefore";
    private static final double WINDOWS_OUTER_CORNER_RADIUS = 10;

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
    private final ApiServer apiServer;
    private final CapabilityBus capabilityBus;
    private final VaultService vaultService;
    private final ConnectionShareService connectionShareService = new ConnectionShareService();
    private final TabPane workspaceTabs = new TabPane();
    private final List<com.jlshell.terminal.service.TerminalViewHandle> localShellHandles = new ArrayList<>();
    private final Set<String> connectingConnectionIds = new HashSet<>();
    private final ListView<ConnectionProfile> connectionListView = new ListView<>();
    private SidebarTreeView sidebarTreeView;
    private WelcomePane welcomePane;
    private VBox topArea; // stored for locale rebuild
    private Label sectionLabel;
    private Label projectSwitchLabel;
    private Label statusLabel;
    private Region statusDot;
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
    private static final double SIDEBAR_EXPANDED_MIN_WIDTH = 360;
    private static final double SIDEBAR_EXPANDED_POSITION = 0.26;
    private static final double SIDEBAR_MAX_POSITION = 1.0 / 3.0;

    // ── 顶栏折叠/展开 ──
    private boolean topBarCollapsed = false;
    private boolean focusMode = false;
    private boolean sidebarVisibleBeforeFocus = true;
    private boolean topBarCollapsedBeforeFocus = false;
    private javafx.animation.PauseTransition collapseDelay;
    private CustomTitleBar customTitleBar; // Windows 专用
    private StackPane workspaceStack; // workspace StackPane
    private Button topBarCollapseBtn; // 顶栏中的折叠按钮（▾）
    private Button topBarExpandBtn; // 折叠后的展开按钮（▴）
    private Region topHoverZone; // 折叠后顶部 4px hover 感应条
    private BorderPane rootPane;
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
            ProgramPluginManager programPluginManager,
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
        this.programPluginManager = programPluginManager;
        this.apiServer = apiServer;
        this.capabilityBus = capabilityBus;
        this.storageFactory = storageFactory;

        // Restore saved active project
        String savedProject = appSettingsService.get("ui.activeProject", "");
        if (savedProject != null && !savedProject.isBlank()) {
            this.activeProjectId = savedProject;
        }
        this.sidebarVisible = Boolean.parseBoolean(appSettingsService.get(SETTINGS_SIDEBAR_VISIBLE, "true"));
        this.topBarCollapsed = Boolean.parseBoolean(appSettingsService.get(SETTINGS_TOPBAR_COLLAPSED, "false"));
        this.focusMode = Boolean.parseBoolean(appSettingsService.get(SETTINGS_FOCUS_MODE, "false"));
        if (this.focusMode) {
            this.sidebarVisibleBeforeFocus = Boolean.parseBoolean(
                    appSettingsService.get(SETTINGS_FOCUS_SIDEBAR_BEFORE, "true"));
            this.topBarCollapsedBeforeFocus = Boolean.parseBoolean(
                    appSettingsService.get(SETTINGS_FOCUS_TOPBAR_BEFORE, "false"));
            this.sidebarVisible = false;
            this.topBarCollapsed = true;
        }
    }

    public Scene createScene(Stage stage) {
        this.primaryStage = stage;
        BorderPane root = new BorderPane();
        rootPane = root;
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
        installApplicationShortcuts(scene, stage);

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
            installWindowsRoundedWindowClip(stage, root);
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
        applyUiFontSettings();
        viewModel.activeThemeProperty().bind(themeService.currentThemeProperty());
        themeService.currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            themeService.apply(scene);
            applyUiFontSettings();
            TerminalColorScheme scheme = themeService.activeColorScheme();
            workspaceTabs.getTabs().stream()
                    .filter(SessionWorkspaceTab.class::isInstance)
                    .map(SessionWorkspaceTab.class::cast)
                    .forEach(tab -> tab.applyColorScheme(scheme));
            pluginManager.setThemeName(newTheme.name().toLowerCase());
            if (programPluginManager != null) {
                programPluginManager.setThemeName(newTheme.name().toLowerCase());
            }
        });

        themeService.accentColorProperty().addListener((obs, oldAccent, newAccent) -> {
            themeService.apply(scene);
            applyUiFontSettings();
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
            if (programPluginManager != null) {
                programPluginManager.setLocale(newLocale);
            }
            updateWindowTitle();
        });

        loadConnections();
        updateWindowTitle();
        return scene;
    }

    private void installWindowsRoundedWindowClip(Stage stage, Region root) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        root.setClip(clip);

        Runnable updateClipArc = () -> {
            double radius = stage.isMaximized() || stage.isFullScreen() ? 0 : WINDOWS_OUTER_CORNER_RADIUS * 2;
            clip.setArcWidth(radius);
            clip.setArcHeight(radius);
        };
        updateClipArc.run();
        stage.maximizedProperty().addListener((obs, oldValue, newValue) -> updateClipArc.run());
        stage.fullScreenProperty().addListener((obs, oldValue, newValue) -> updateClipArc.run());
    }

    private MenuBar buildMenuBar(Stage stage) {
        MenuBar menuBar = new MenuBar();

        // File 菜单
        Menu fileMenu = new Menu(i18nService.get("menu.file"));
        MenuItem newConnection = new MenuItem(i18nService.get("action.newConnection"));
        MenuItem pasteShare = new MenuItem(i18nService.get("connection.share.pasteMenu"));
        MenuItem refreshConnections = new MenuItem(i18nService.get("action.refresh"));
        MenuItem exit = new MenuItem(i18nService.get("action.exit"));
        newConnection.setOnAction(event -> createConnection(stage));
        pasteShare.setOnAction(event -> pasteSharedConnection(stage));
        refreshConnections.setOnAction(event -> loadConnections());
        exit.setOnAction(event -> stage.close());

        Menu projectsMenu = new Menu(i18nService.get("project.menu.projects"));
        MenuItem manageProjects = new MenuItem(i18nService.get("project.menu.manage"));
        manageProjects.setOnAction(e -> {
            ProjectManagerDialog.show(stage, connectionProfileService, i18nService, themeService, vaultService, activeProjectId,
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

        fileMenu.getItems().addAll(newConnection, pasteShare, refreshConnections, projectsMenu, manageVault, new SeparatorMenuItem(), exit);

        Menu connectionMenu = new Menu(i18nService.get("menu.connections"));
        connectionMenu.setOnShowing(event -> rebuildOpenConnectionsMenu(connectionMenu));
        rebuildOpenConnectionsMenu(connectionMenu);

        // View 菜单
        Menu viewMenu = new Menu(i18nService.get("menu.view"));
        MenuItem toggleSidebarItem = new MenuItem(i18nService.get("sidebar.toggle"));
        toggleSidebarItem.setOnAction(event -> toggleSidebar());
        MenuItem collapseTopBarItem = new MenuItem(i18nService.get("topbar.collapse"));
        collapseTopBarItem.setOnAction(event -> toggleTopBarCollapse());
        CheckMenuItem focusModeItem = new CheckMenuItem(i18nService.get("focusMode.toggle"));
        focusModeItem.setSelected(focusMode);
        focusModeItem.setOnAction(event -> setFocusMode(focusModeItem.isSelected()));
        ToggleGroup themeGroup = new ToggleGroup();
        RadioMenuItem darkTheme = new RadioMenuItem(i18nService.get("theme.dark"));
        RadioMenuItem lightTheme = new RadioMenuItem(i18nService.get("theme.light"));
        darkTheme.setToggleGroup(themeGroup);
        lightTheme.setToggleGroup(themeGroup);
        viewMenu.setOnShowing(event -> {
            AppTheme currentTheme = themeService.currentTheme();
            darkTheme.setSelected(currentTheme == AppTheme.DARK);
            lightTheme.setSelected(currentTheme == AppTheme.LIGHT);
            focusModeItem.setSelected(focusMode);
        });
        darkTheme.setOnAction(event -> themeService.setTheme(AppTheme.DARK));
        lightTheme.setOnAction(event -> themeService.setTheme(AppTheme.LIGHT));
        viewMenu.getItems().addAll(toggleSidebarItem, collapseTopBarItem, focusModeItem,
                new SeparatorMenuItem(), darkTheme, lightTheme);

        // Preferences menu item
        // On macOS, JavaFX automatically moves a MenuItem with Cmd+, shortcut to the app menu.
        MenuItem preferences = new MenuItem(i18nService.get("action.preferences"));
        preferences.setOnAction(event -> openPreferences(stage));

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (isMac) {
            // Add to fileMenu; JavaFX moves Preferences with Cmd+, to the macOS app menu
            fileMenu.getItems().add(4, preferences);
            // Help → About: JavaFX automatically moves "About" to the macOS app menu
            Menu helpMenu = new Menu(i18nService.get("menu.help"));
            MenuItem aboutItem = new MenuItem(i18nService.get("menu.help.about"));
            aboutItem.setOnAction(event -> AboutDialog.show(stage, i18nService, themeService));
            helpMenu.getItems().add(aboutItem);
            menuBar.getMenus().addAll(fileMenu, connectionMenu, viewMenu, helpMenu);
        } else {
            Menu settingsMenu = new Menu(i18nService.get("menu.settings"));
            settingsMenu.getItems().add(preferences);
            Menu helpMenu = new Menu(i18nService.get("menu.help"));
            MenuItem aboutItem = new MenuItem(i18nService.get("menu.help.about"));
            aboutItem.setOnAction(event -> AboutDialog.show(stage, i18nService, themeService));
            helpMenu.getItems().add(aboutItem);
            menuBar.getMenus().addAll(fileMenu, connectionMenu, viewMenu, settingsMenu, helpMenu);
        }
        installMenuCursorRecovery(menuBar);
        return menuBar;
    }

    private void rebuildOpenConnectionsMenu(Menu connectionMenu) {
        connectionMenu.getItems().clear();
        ToggleGroup group = new ToggleGroup();
        javafx.scene.control.Tab selectedTab = workspaceTabs.getSelectionModel().getSelectedItem();
        int index = 1;
        for (javafx.scene.control.Tab tab : workspaceTabs.getTabs()) {
            String title = workspaceTabTitle(tab);
            if (title == null || title.isBlank()) {
                title = i18nService.get("menu.connections.untitled");
            }
            RadioMenuItem item = new RadioMenuItem(index + ". " + title);
            item.setToggleGroup(group);
            item.setSelected(tab == selectedTab);
            item.setOnAction(event -> workspaceTabs.getSelectionModel().select(tab));
            connectionMenu.getItems().add(item);
            index++;
        }
        if (connectionMenu.getItems().isEmpty()) {
            MenuItem empty = new MenuItem(i18nService.get("menu.connections.empty"));
            empty.setDisable(true);
            connectionMenu.getItems().add(empty);
        }
    }

    private void installMenuCursorRecovery(MenuBar menuBar) {
        Runnable resetCursor = () -> {
            Scene scene = menuBar.getScene();
            if (scene != null) {
                scene.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        };
        menuBar.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_ENTERED, event -> resetCursor.run());
        menuBar.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, event -> resetCursor.run());
        menuBar.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> resetCursor.run());
        for (Menu menu : menuBar.getMenus()) {
            installMenuCursorRecovery(menu, resetCursor);
        }
    }

    private void installMenuCursorRecovery(Menu menu, Runnable resetCursor) {
        menu.addEventHandler(Menu.ON_SHOWING, event -> resetCursor.run());
        menu.addEventHandler(Menu.ON_SHOWN, event -> resetCursor.run());
        menu.addEventHandler(Menu.ON_HIDDEN, event -> resetCursor.run());
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu childMenu) {
                installMenuCursorRecovery(childMenu, resetCursor);
            }
        }
    }

    private boolean isEventFromMenuBar(javafx.event.Event event) {
        Object target = event.getTarget();
        if (!(target instanceof Node node)) {
            return false;
        }
        while (node != null) {
            if (node instanceof MenuBar || node.getStyleClass().contains("embedded-menu-bar")) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private void installApplicationShortcuts(Scene scene, Stage stage) {
        installApplicationShortcut(scene, KeyCode.N, () -> createConnection(stage));
        installApplicationShortcut(scene, KeyCode.R, this::loadConnections);
        installApplicationShortcut(scene, KeyCode.Q, stage::close);
        installApplicationShortcut(scene, KeyCode.B, this::toggleSidebar);
        installApplicationShortcut(scene, KeyCode.T, this::toggleTopBarCollapse);
        installApplicationShortcut(scene, KeyCode.F, this::toggleFocusMode, KeyCombination.SHIFT_DOWN);
        installApplicationShortcut(scene, KeyCode.COMMA, () -> openPreferences(stage));
    }

    private void installApplicationShortcut(Scene scene, KeyCode keyCode, Runnable action) {
        installApplicationShortcut(scene, keyCode, action, new KeyCombination.Modifier[0]);
    }

    private void installApplicationShortcut(Scene scene, KeyCode keyCode, Runnable action,
                                            KeyCombination.Modifier... modifiers) {
        KeyCombination.Modifier[] allModifiers = new KeyCombination.Modifier[modifiers.length + 1];
        allModifiers[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, allModifiers, 1, modifiers.length);
        KeyCodeCombination shortcut = new KeyCodeCombination(keyCode, allModifiers);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isConsumed() || !shortcut.match(event)) {
                return;
            }
            // Let shell/readline programs receive Ctrl+B/N/Q/R/T and similar chords
            // when focus is inside the embedded terminal. Cmd shortcuts on macOS
            // remain application shortcuts because Meta is not terminal input here.
            if (isTerminalFocusOwner(scene) && event.isControlDown() && !event.isMetaDown()) {
                return;
            }
            action.run();
            event.consume();
        });
    }

    private boolean isTerminalFocusOwner(Scene scene) {
        Node node = scene.getFocusOwner();
        while (node != null) {
            if (node instanceof javafx.embed.swing.SwingNode
                    && node.getStyleClass().contains(TERMINAL_SWING_NODE_STYLE_CLASS)) {
                return true;
            }
            node = node.getParent();
        }
        return false;
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
        updateWindowTitle();
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
        String selectedSessionId = selectedApiSessionId();
        PreferencesDialog.show(stage, fontProfileService, appSettingsService, i18nService, themeService,
                connectionProfileService, activeProjectId, apiServer, capabilityBus, programPluginManager,
                pluginManager, selectedSessionId, 0);
        // 导入后刷新侧边栏
        loadConnections();
        // 应用可能变更的 UI 字体设置
        applyUiFontSettings();
    }

    private String selectedApiSessionId() {
        javafx.scene.control.Tab selected = workspaceTabs.getSelectionModel().getSelectedItem();
        return selected instanceof SessionWorkspaceTab sessionTab ? sessionTab.getSessionId() : null;
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
                applyTopBarCollapsedDeferred(true);
            }
            updateTerminalFocusLayoutStyle();
        }
    }

    private SplitPane buildCenterArea(Stage stage) {
        sidebarVBox = buildSidebar(stage);
        sidebarVBox.setMinWidth(SIDEBAR_EXPANDED_MIN_WIDTH);
        workspaceTabs.getStyleClass().add("workspace-tabs");
        installTabDragReorder(workspaceTabs);
        workspaceTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> updateWindowTitle());
        workspaceTabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> updateWindowTitle());

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

        // ── 顶栏折叠按钮 overlay ──
        // 不放在外层 workspace tab header 右侧：JavaFX 在标签溢出时也会在该位置
        // 生成“更多标签”按钮，两者会重叠。下移到内层导航行右侧，避开标签溢出区。
        topBarCollapseBtn = new Button("⌄");
        topBarCollapseBtn.getStyleClass().add("topbar-collapse-btn");
        topBarCollapseBtn.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("topbar.collapse")));
        topBarCollapseBtn.setOnAction(e -> {
            toggleTopBarCollapse();
        });
        StackPane.setAlignment(topBarCollapseBtn, javafx.geometry.Pos.TOP_RIGHT);
        StackPane.setMargin(topBarCollapseBtn, new Insets(34, 8, 0, 0));
        // 初始无 tab 时不显示；有 tab 时由 applyTopBarCollapsed 管理
        topBarCollapseBtn.setVisible(false);
        workspaceTabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) c -> {
            if (!topBarCollapsed && !c.getList().isEmpty()) {
                topBarCollapseBtn.setVisible(true);
            } else if (topBarCollapsed) {
                topBarCollapseBtn.setVisible(false);
                topBarCollapseBtn.setManaged(false);
            }
        });

        // ── 折叠后的展开按钮（透明悬浮在终端右上角） ──
        topBarExpandBtn = new Button("⌃");
        topBarExpandBtn.getStyleClass().add("topbar-expand-btn");
        topBarExpandBtn.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("topbar.expand")));
        topBarExpandBtn.setOnAction(e -> {
            topBarCollapsed = false;
            applyTopBarCollapsed(false);
            removeTopBarExitListener();
            saveLayoutState();
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
                saveLayoutState();
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
        if (topBarCollapsed) {
            applyTopBarCollapsedDeferred(true);
        }

        centerSplitPane = new SplitPane(sidebarVBox, workspaceStackPane);
        installSidebarDividerLimit(centerSplitPane);
        if (sidebarVisible) {
            restoreSidebarDividerPosition();
        } else {
            applySidebarVisibility();
        }
        return centerSplitPane;
    }

    private void installSidebarDividerLimit(SplitPane splitPane) {
        splitPane.getDividers().getFirst().positionProperty().addListener((obs, oldPosition, newPosition) -> {
            if (!sidebarVisible) {
                return;
            }
            double position = newPosition.doubleValue();
            if (position > SIDEBAR_MAX_POSITION) {
                splitPane.setDividerPositions(SIDEBAR_MAX_POSITION);
            }
        });
        javafx.application.Platform.runLater(() -> installSplitPaneCursorRecovery(splitPane));
        splitPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> saveSidebarDividerPosition());
    }

    private double savedSidebarDividerPosition() {
        double width = savedSidebarWidth();
        if (width > 0 && centerSplitPane != null && centerSplitPane.getWidth() > 0) {
            return Math.max(0.05, Math.min(SIDEBAR_MAX_POSITION, width / centerSplitPane.getWidth()));
        }
        try {
            double position = Double.parseDouble(appSettingsService.get(
                    SETTINGS_SIDEBAR_DIVIDER_POSITION,
                    String.valueOf(SIDEBAR_EXPANDED_POSITION)));
            if (!Double.isFinite(position)) {
                return SIDEBAR_EXPANDED_POSITION;
            }
            return Math.max(0.05, Math.min(SIDEBAR_MAX_POSITION, position));
        } catch (NumberFormatException ignored) {
            return SIDEBAR_EXPANDED_POSITION;
        }
    }

    private double savedSidebarWidth() {
        try {
            double width = Double.parseDouble(appSettingsService.get(SETTINGS_SIDEBAR_WIDTH, "0"));
            return Double.isFinite(width) ? width : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void restoreSidebarDividerPosition() {
        if (centerSplitPane == null) {
            return;
        }
        centerSplitPane.setDividerPositions(savedSidebarDividerPosition());
        javafx.application.Platform.runLater(() -> {
            if (sidebarVisible && centerSplitPane != null) {
                centerSplitPane.setDividerPositions(savedSidebarDividerPosition());
            }
        });
    }

    private void saveSidebarDividerPosition() {
        if (centerSplitPane == null || !sidebarVisible || centerSplitPane.getDividers().isEmpty()) {
            return;
        }
        double position = centerSplitPane.getDividers().getFirst().getPosition();
        if (position > 0 && position <= SIDEBAR_MAX_POSITION) {
            appSettingsService.set(SETTINGS_SIDEBAR_DIVIDER_POSITION, String.format(Locale.ROOT, "%.4f", position));
            double width = centerSplitPane.getWidth() * position;
            if (Double.isFinite(width) && width > 0) {
                appSettingsService.set(SETTINGS_SIDEBAR_WIDTH, String.format(Locale.ROOT, "%.0f", width));
            }
        }
    }

    private void installSplitPaneCursorRecovery(SplitPane splitPane) {
        Scene scene = splitPane.getScene();
        if (scene == null) {
            return;
        }
        for (Node node : splitPane.lookupAll(".split-pane-divider")) {
            node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
                saveSidebarDividerPosition();
                scene.setCursor(javafx.scene.Cursor.DEFAULT);
            });
            node.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, event -> {
                if (!event.isPrimaryButtonDown()) {
                    scene.setCursor(javafx.scene.Cursor.DEFAULT);
                }
            });
        }
    }

    private void updateWindowTitle() {
        String title = buildWindowTitle(workspaceTabs.getSelectionModel().getSelectedItem());
        if (primaryStage != null) {
            primaryStage.setTitle(title);
        }
        if (customTitleBar != null) {
            customTitleBar.setTitleText(title);
        }
    }

    private String buildWindowTitle(javafx.scene.control.Tab tab) {
        String detail = null;
        if (tab instanceof SessionWorkspaceTab sessionTab) {
            ConnectionProfile profile = sessionTab.getConnectionProfile();
            detail = formatConnectionTitle(profile);
        } else if (tab != null && tab.getUserData() instanceof ConnectionProfile profile) {
            detail = formatConnectionTitle(profile);
        }
        return detail == null || detail.isBlank() ? "JLShell" : detail + " — JLShell";
    }

    private void installWorkspaceTabHeader(javafx.scene.control.Tab tab, String title) {
        tab.getProperties().put(WORKSPACE_TAB_TITLE_KEY, title);
        tab.setText(null);
        tab.setClosable(false);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("workspace-tab-title");
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMaxWidth(190);

        Label closeGlyph = new Label("×");
        closeGlyph.getStyleClass().add("workspace-tab-close-glyph");
        closeGlyph.setAlignment(Pos.CENTER);

        StackPane closeGraphic = new StackPane(closeGlyph);
        closeGraphic.getStyleClass().add("workspace-tab-close-graphic");
        closeGraphic.setAlignment(Pos.CENTER);

        Button closeButton = new Button();
        closeButton.getStyleClass().add("workspace-tab-close");
        closeButton.setGraphic(closeGraphic);
        closeButton.setFocusTraversable(false);
        closeButton.setTooltip(new javafx.scene.control.Tooltip(i18nService.get("tab.close")));
        closeButton.setOnAction(event -> {
            event.consume();
            closeTab(tab);
        });

        HBox header = new HBox(5, titleLabel, closeButton);
        header.getStyleClass().add("workspace-tab-header");
        header.setAlignment(Pos.CENTER);
        tab.setGraphic(header);
    }

    private String workspaceTabTitle(javafx.scene.control.Tab tab) {
        Object title = tab.getProperties().get(WORKSPACE_TAB_TITLE_KEY);
        if (title instanceof String text && !text.isBlank()) {
            return text;
        }
        return tab.getText();
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
        sidebarTreeView.setOnCopyShare(item -> {
            if (item instanceof SidebarItem.ConnectionItem conn) {
                copyConnectionShare(stage, conn.id());
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
        connectButton.setText(i18nService.get("action.connect"));
        connectButton.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
        connectButton.getStyleClass().add("icon-btn-primary");

        HBox actionBar = new HBox(4, createButton, editButton, deleteButton, newFolderButton,
                new javafx.scene.layout.Region(), connectButton, refreshButton, toggleSidebarBtn);
        HBox.setHgrow(actionBar.getChildren().get(4), Priority.ALWAYS);
        actionBar.getStyleClass().add("sidebar-action-bar");
        VBox.setMargin(actionBar, new Insets(0, 6, 10, 6));

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
        searchRow.setMaxWidth(420);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) ->
                sidebarTreeView.applyFilter(newVal));

        sectionLabel = new Label(i18nService.get("sidebar.connections"));
        sectionLabel.getStyleClass().add("sidebar-section-label");

        projectSwitchLabel = new Label(i18nService.get("project.switch.label"));
        projectSwitchLabel.getStyleClass().add("sidebar-project-label");
        projectSwitchLabel.setMinWidth(Region.USE_PREF_SIZE);

        projectCombo.getStyleClass().add("sidebar-project-combo");
        projectCombo.setMinWidth(180);
        projectCombo.setMaxWidth(Double.MAX_VALUE);
        projectCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setText(empty ? "" : (item == DEFAULT_PROJECT ? i18nService.get("project.label.default") : item.name()));
            }
        });
        projectCombo.setCellFactory(lv -> new ListCell<>() {
            {
                if (!lv.getStyleClass().contains("sidebar-project-combo-popup")) {
                    lv.getStyleClass().add("sidebar-project-combo-popup");
                }
            }

            @Override
            protected void updateItem(ProjectProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (!getStyleClass().contains("sidebar-project-cell")) {
                    getStyleClass().add("sidebar-project-cell");
                }
                setTextOverrun(OverrunStyle.ELLIPSIS);
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
                    ProjectManagerDialog.show(stage, connectionProfileService, i18nService, themeService, vaultService, activeProjectId,
                            projectId -> FxThread.run(() -> switchProject(projectId)));
                    refreshProjectCombo();
                    loadConnections();
                });

        HBox.setHgrow(projectCombo, Priority.ALWAYS);
        HBox projectRow = new HBox(8, projectSwitchLabel, projectCombo, manageProjectBtn, settingsButton);
        projectRow.getStyleClass().add("sidebar-project-row");
        VBox.setMargin(projectRow, new Insets(10, 6, 8, 6));

        VBox listPanel = new VBox(0, sectionLabel, searchRow, sidebarTreeView.getTreeView());
        listPanel.getStyleClass().add("sidebar-list-panel");
        VBox.setMargin(listPanel, new Insets(0, 6, 8, 6));
        VBox.setVgrow(listPanel, Priority.ALWAYS);

        VBox sidebar = new VBox(0, projectRow, listPanel, actionBar);
        sidebar.getStyleClass().add("sidebar");
        VBox.setVgrow(sidebarTreeView.getTreeView(), Priority.ALWAYS);
        return sidebar;
    }

    private HBox buildStatusBar() {
        statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.statusMessageProperty());
        statusLabel.getStyleClass().add("status-label");
        statusDot = new Region();
        statusDot.getStyleClass().add("status-dot");
        updateStatusDotState(viewModel.statusMessageProperty().get());
        viewModel.statusMessageProperty().addListener((obs, oldValue, newValue) -> updateStatusDotState(newValue));
        HBox statusBar = new HBox(6, statusDot, statusLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        return statusBar;
    }

    private void updateStatusDotState(String message) {
        if (statusDot == null) {
            return;
        }
        statusDot.getStyleClass().remove("status-dot-error");
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean error = lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("error")
                || (message != null && (message.contains("失败") || message.contains("错误")));
        if (error) {
            statusDot.getStyleClass().add("status-dot-error");
        }
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
        saveLayoutState();
    }

    private void toggleFocusMode() {
        setFocusMode(!focusMode);
    }

    private void setFocusMode(boolean enabled) {
        if (focusMode == enabled) {
            return;
        }
        focusMode = enabled;
        if (enabled) {
            sidebarVisibleBeforeFocus = sidebarVisible;
            topBarCollapsedBeforeFocus = topBarCollapsed;
            appSettingsService.set(SETTINGS_FOCUS_SIDEBAR_BEFORE, String.valueOf(sidebarVisibleBeforeFocus));
            appSettingsService.set(SETTINGS_FOCUS_TOPBAR_BEFORE, String.valueOf(topBarCollapsedBeforeFocus));
            if (sidebarVisible) {
                toggleSidebar();
            }
            if (!topBarCollapsed) {
                toggleTopBarCollapse();
            }
        } else {
            if (sidebarVisible != sidebarVisibleBeforeFocus) {
                toggleSidebar();
            }
            if (topBarCollapsed != topBarCollapsedBeforeFocus) {
                toggleTopBarCollapse();
            }
        }
        saveLayoutState();
    }

    private void applySidebarVisibility() {
        if (sidebarVisible) {
            centerSplitPane.getStyleClass().remove("sidebar-collapsed");
            sidebarVBox.setPrefWidth(Region.USE_COMPUTED_SIZE);
            sidebarVBox.setMinWidth(SIDEBAR_EXPANDED_MIN_WIDTH);
            sidebarVBox.setMaxWidth(Region.USE_COMPUTED_SIZE);
            restoreSidebarDividerPosition();
            revealSidebarBtn.setVisible(false);
        } else {
            if (!centerSplitPane.getStyleClass().contains("sidebar-collapsed")) {
                centerSplitPane.getStyleClass().add("sidebar-collapsed");
            }
            sidebarVBox.setPrefWidth(0);
            sidebarVBox.setMinWidth(0);
            sidebarVBox.setMaxWidth(0);
            centerSplitPane.setDividerPositions(0);
            revealSidebarBtn.setVisible(true);
        }
        updateTerminalFocusLayoutStyle();
    }

    /** 应用 UI 字体设置（inline style 覆盖 CSS .root 规则） */
    private void applyUiFontSettings() {
        if (primaryStage != null && primaryStage.getScene() != null) {
            primaryStage.getScene().getRoot().setStyle(themeService.uiStyle());
        }
    }

    // ── 顶栏折叠/展开 ────────────────────────────────────────────

    private void toggleTopBarCollapse() {
        topBarCollapsed = !topBarCollapsed;
        applyTopBarCollapsed(topBarCollapsed);
        saveLayoutState();
    }

    private void saveLayoutState() {
        appSettingsService.set(SETTINGS_SIDEBAR_VISIBLE, String.valueOf(sidebarVisible));
        appSettingsService.set(SETTINGS_TOPBAR_COLLAPSED, String.valueOf(topBarCollapsed));
        appSettingsService.set(SETTINGS_FOCUS_MODE, String.valueOf(focusMode));
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
        updateTerminalFocusLayoutStyle();
    }

    private void applyTopBarCollapsedDeferred(boolean collapsed) {
        applyTopBarCollapsed(collapsed);
        applyTopBarCollapsedDeferred(collapsed, 4);
    }

    private void applyTopBarCollapsedDeferred(boolean collapsed, int remainingAttempts) {
        if (remainingAttempts <= 0) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            if (topBarCollapsed != collapsed) {
                return;
            }
            applyTopBarCollapsed(collapsed);
            applyTopBarCollapsedDeferred(collapsed, remainingAttempts - 1);
        });
    }

    private void updateTerminalFocusLayoutStyle() {
        if (rootPane == null) {
            return;
        }
        boolean focusedTerminalLayout = !sidebarVisible && topBarCollapsed;
        if (focusedTerminalLayout) {
            if (!rootPane.getStyleClass().contains("terminal-focus-layout")) {
                rootPane.getStyleClass().add("terminal-focus-layout");
            }
        } else {
            rootPane.getStyleClass().remove("terminal-focus-layout");
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
                        saveLayoutState();
                    }
                }
            });
            node.setOnMouseEntered(e -> {
                collapseDelay.stop();
                topBarCollapsed = false;
                saveLayoutState();
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

    private void pasteSharedConnection(Stage stage) {
        ConnectionDialog.showPasteShareDialog(stage, i18nService, themeService)
                .ifPresent(shared -> ConnectionDialog.show(stage, i18nService, themeService, withActiveProject(shared),
                        connectionProfileService.listProjects(),
                        connectionProfileService.listFolders(activeProjectId),
                        vaultService,
                        this::testConnection, connectTimeoutSeconds())
                        .ifPresent(this::saveConnection));
    }

    private ConnectionFormData withActiveProject(ConnectionFormData form) {
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
                form.folderId(),
                form.vaultEntryId(),
                form.keyContent()
        );
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

    private void copyConnectionShare(Stage stage, String connectionId) {
        CompletableFuture.supplyAsync(() -> connectionProfileService.loadForm(connectionId), executor)
                .whenComplete((formData, throwable) -> FxThread.run(() -> {
                    if (throwable != null) {
                        showError(i18nService.get("connection.share.loadFailed", throwable.getMessage()));
                        return;
                    }
                    showConnectionShareDialog(stage, formData);
                }));
    }

    private void showConnectionShareDialog(Stage stage, ConnectionFormData formData) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(i18nService.get("connection.share.title"));
        dialog.setHeaderText(i18nService.get("connection.share.header", formData.displayName()));
        themeService.applyToDialog(dialog);

        TextField codeField = new TextField(connectionShareService.generateShareCode());
        codeField.setPrefColumnCount(22);
        CheckBox includeShareCode = new CheckBox(i18nService.get("connection.share.includeShareCode"));
        includeShareCode.setSelected(false);

        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefSize(520, 150);

        Runnable refreshPreview = () -> {
            try {
                preview.setText(connectionShareService.exportShareText(
                        formData,
                        codeField.getText() == null ? "" : codeField.getText().trim(),
                        includeShareCode.isSelected()));
            } catch (RuntimeException e) {
                preview.setText("");
            }
        };
        codeField.textProperty().addListener((obs, oldValue, newValue) -> refreshPreview.run());
        includeShareCode.selectedProperty().addListener((obs, oldValue, newValue) -> refreshPreview.run());
        refreshPreview.run();

        Label hint = new Label(i18nService.get("connection.share.codeHint"));
        hint.setWrapText(true);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(i18nService.get("connection.share.code")), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(new Label(i18nService.get("connection.share.codeCopy")), 0, 1);
        grid.add(includeShareCode, 1, 1);
        grid.add(hint, 1, 2);
        grid.add(new Label(i18nService.get("connection.share.text")), 0, 3);
        grid.add(preview, 1, 3);
        dialog.getDialogPane().setContent(grid);

        ButtonType copyButtonType = new ButtonType(i18nService.get("connection.share.copyButton"), ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(copyButtonType, ButtonType.CANCEL);
        javafx.scene.Node copyButton = dialog.getDialogPane().lookupButton(copyButtonType);
        copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            String code = codeField.getText() == null ? "" : codeField.getText().trim();
            if (code.isBlank()) {
                showError(i18nService.get("connection.share.codeRequired"));
                return;
            }
            String shareText = connectionShareService.exportShareText(formData, code, includeShareCode.isSelected());
            String clipboardText = i18nService.get("connection.share.clipboardHeader",
                    formData.host() == null || formData.host().isBlank() ? "-" : formData.host())
                    + System.lineSeparator()
                    + shareText;
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(clipboardText);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            dialog.close();
            Alert done = new Alert(Alert.AlertType.INFORMATION, i18nService.get("connection.share.copied"), ButtonType.OK);
            themeService.applyToDialog(done);
            done.initOwner(stage);
            done.showAndWait();
        });
        dialog.showAndWait();
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
        connectProfile(selected);
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
                .whenComplete((viewHandle, throwable) -> FxThread.run(() -> {
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

    private void openLocalShellTab(ConnectionProfile profile, com.jlshell.terminal.service.TerminalViewHandle viewHandle) {
        localShellHandles.add(viewHandle);
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(profile.displayName());
        tab.setUserData(profile);
        installWorkspaceTabHeader(tab, profile.displayName());
        tab.setContextMenu(buildTabContextMenu(tab));
        javax.swing.JComponent component = (javax.swing.JComponent) viewHandle.component();
        javafx.embed.swing.SwingNode swingNode = new javafx.embed.swing.SwingNode();
        swingNode.setFocusTraversable(true);
        swingNode.getStyleClass().add(TERMINAL_SWING_NODE_STYLE_CLASS);
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
        applyTopBarCollapsedAfterTabAdded(tab);
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
                        FxThread.run(() -> {
                            showError(i18nService.get("status.connectionFailed",
                                    throwable.getCause() == null ? throwable.getMessage() : throwable.getCause().getMessage()));
                            finishConnecting(selected);
                        });
                        return;
                    }
                    log.info("SSH connection future completed for session {}", sshSession.sessionId());
                    FxThread.run(() -> {
                        try {
                            openWorkspace(selected, sshSession);
                        } catch (Throwable t) {
                            finishConnecting(selected);
                            throw t;
                        }
                    });
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
            installWorkspaceTabHeader(tab, profile.displayName());
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
            applyTopBarCollapsedAfterTabAdded(tab);
            log.info("Workspace tab added for session {}", sshSession.sessionId());
            tab.initialize().whenComplete((unused, t) -> FxThread.run(() -> {
                try {
                    if (t != null) {
                        log.error("Workspace initialization failed for session {}", sshSession.sessionId(), t);
                        showError(i18nService.get("status.terminalOpenFailed", t.getMessage()));
                        workspaceTabs.getTabs().remove(tab);
                    } else {
                        log.info("Workspace initialization completed for session {}", sshSession.sessionId());
                        viewModel.statusMessageProperty().set(i18nService.get("status.connected", profile.summary()));
                        applyTopBarCollapsedAfterTabAdded(tab);
                    }
                } finally {
                    finishConnecting(profile);
                }
            }));
        }));
    }

    private void applyTopBarCollapsedAfterTabAdded(javafx.scene.control.Tab tab) {
        if (!topBarCollapsed) {
            return;
        }
        applyTopBarCollapsed(true);
        applyTopBarCollapsedToTab(tab, false);
        applyTopBarCollapsedToTabDeferred(tab, false, 4);
    }

    private void applyTopBarCollapsedToTabDeferred(javafx.scene.control.Tab tab, boolean visible, int remainingAttempts) {
        if (remainingAttempts <= 0) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            if (topBarCollapsed == visible) {
                return;
            }
            setTabHeaderVisible(workspaceTabs, visible);
            applyTopBarCollapsedToTab(tab, visible);
            applyTopBarCollapsedToTabDeferred(tab, visible, remainingAttempts - 1);
        });
    }

    private void applyTopBarCollapsedToTab(javafx.scene.control.Tab tab, boolean visible) {
        if (tab instanceof SessionWorkspaceTab swt) {
            setTabHeaderVisible(swt.getInnerTabPane(), visible);
            swt.setToolbarVisible(visible);
        }
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
                connectProfile(swt.getConnectionProfile());
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
        boolean[] resizing = {false};
        double[] startDragX = {0}, startDragY = {0};
        double[] startStageX = {0}, startStageY = {0}, startW = {0}, startH = {0};
        Runnable resetResizeCursor = () -> {
            activeCursor[0] = null;
            resizing[0] = false;
            scene.setCursor(javafx.scene.Cursor.DEFAULT);
        };

        // 窗口获得焦点时重置光标，避免从其他程序切换回来时
        // 光标还停留在 resize 箭头样式
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> resetResizeCursor.run());
        stage.maximizedProperty().addListener((obs, wasMaximized, isMaximized) -> resetResizeCursor.run());
        stage.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> resetResizeCursor.run());

        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> resetResizeCursor.run());
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED, event -> {
            if (!event.isPrimaryButtonDown()) {
                resetResizeCursor.run();
            }
        });

        scene.setOnMouseMoved(e -> {
            if (resizing[0]) {
                return;
            }
            if (isEventFromMenuBar(e)) {
                resetResizeCursor.run();
                return;
            }
            if (stage.isMaximized() || stage.isFullScreen()) {
                resetResizeCursor.run();
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
            activeCursor[0] = resizeDirections.containsKey(cursor) ? cursor : null;
        });

        scene.setOnMousePressed(e -> {
            if (isEventFromMenuBar(e)) {
                resetResizeCursor.run();
                return;
            }
            if (activeCursor[0] != null
                    && resizeDirections.containsKey(activeCursor[0])
                    && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                resizing[0] = true;
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
            if (!resizing[0] || activeCursor[0] == null) return;
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

        scene.setOnMouseReleased(e -> resetResizeCursor.run());
    }

    private void showError(String message) {
        viewModel.statusMessageProperty().set(message);
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        themeService.applyToDialog(alert);
        alert.showAndWait();
    }
}
