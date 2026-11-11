package com.jlshell.ui.swing;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FavoriteConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
import com.jlshell.ui.model.FolderHierarchy;
import com.jlshell.ui.model.ProjectProfile;
import com.jlshell.ui.model.RecentSessionProfile;
import com.jlshell.ui.model.SidebarItem;
import com.jlshell.ui.theme.AppTheme;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;

/** Swing 主工作台。只负责布局、输入与绘制，通过 Actions 使用原有应用动作。 */
public final class SwingWorkbench implements AutoCloseable {
    public record Actions(Runnable create, Runnable folder, Runnable edit, Runnable delete, Runnable refresh,
                          Runnable preferences, Runnable projects, Runnable toggleSidebar, Runnable quit,
                          Consumer<ConnectionProfile> connect, Consumer<List<SidebarItem>> select,
                          Consumer<ProjectProfile> project, Consumer<Set<String>> collapsed,
                          Consumer<Object> selectTab, java.util.function.BiConsumer<Object, Integer> reorder,
                          java.util.function.BiConsumer<List<SidebarItem>, String> move,
                          java.util.function.BiConsumer<SidebarItem, java.util.function.Consumer<List<SwingMenuBridge.Item>>> context,
                          java.util.function.BiConsumer<Object, java.util.function.Consumer<List<SwingMenuBridge.Item>>> tabContext,
                          java.util.function.BiConsumer<String, String> setSetting,
                          Runnable focusActiveConnection) {
        /** Backward-compatible constructor for UI tests and embedders using the original action set. */
        public Actions(Runnable create, Runnable folder, Runnable edit, Runnable delete, Runnable refresh,
                       Runnable preferences, Runnable projects, Runnable toggleSidebar, Runnable quit,
                       Consumer<ConnectionProfile> connect, Consumer<List<SidebarItem>> select,
                       Consumer<ProjectProfile> project, Consumer<Set<String>> collapsed,
                       Consumer<Object> selectTab, java.util.function.BiConsumer<Object, Integer> reorder,
                       java.util.function.BiConsumer<List<SidebarItem>, String> move,
                       java.util.function.BiConsumer<SidebarItem, Consumer<List<SwingMenuBridge.Item>>> context,
                       java.util.function.BiConsumer<Object, Consumer<List<SwingMenuBridge.Item>>> tabContext) {
            this(create, folder, edit, delete, refresh, preferences, projects, toggleSidebar, quit,
                    connect, select, project, collapsed, selectTab, reorder, move, context, tabContext,
                    (key, value) -> {}, () -> {});
        }
    }

    public static final List<String> TEXT_KEYS = List.of("statusbar.tasks", "statusbar.activity", "statusbar.needsAttention", "statusbar.noLogs", "statusbar.recentLogs", "statusbar.local", "statusbar.connected", "statusbar.connecting", "statusbar.disconnected", "statusbar.transfers", "statusbar.time", "statusbar.level", "statusbar.source", "statusbar.message", "statusbar.session","action.newConnection", "sidebar.newFolder",
            "action.editConnection", "action.deleteConnection", "action.refresh", "action.preferences",
            "action.connect", "sidebar.toggle", "sidebar.connections", "sidebar.search.prompt", "project.menu.manage",
            "project.label.default", "welcome.tagline", "welcome.favoriteHint", "welcome.recentHint", "welcome.title", "welcome.subtitle", "welcome.quickStart",
            "welcome.help.shortcuts", "welcome.help.newConnection", "welcome.help.refreshConnections",
            "welcome.help.doubleClick", "welcome.help.rightClick", "welcome.recentConnections",
            "welcome.favorites", "welcome.noRecentConnections", "welcome.noFavorites", "tab.close", "tab.close.tooltip",
            "workspace.tools", "workspace.hideTools", "workspace.maximizeTool", "workspace.restoreTool", "workspace.toolConnections", "workspace.monitoring", "workspace.moveToolBottom", "workspace.moveToolRight", "workspace.moveToolBottomLeft", "workspace.moveToolBottomRight", "workspace.dragCancel", "workspace.plugins", "menu.main",
            "project.popup.current", "project.popup.other", "project.popup.defaultDescription",
            "project.search.prompt", "settings.quick.plugins", "settings.quick.shortcuts", "settings.quick.theme",
            "settings.quick.view", "settings.quick.account", "menu.help.about", "menu.help.checkUpdates",
            "menu.view", "menu.account", "menu.file", "menu.connections", "menu.help", "menu.settings",
            "theme.dark", "theme.light", "preferences.tab.plugins", "preferences.tab.shortcuts",
            "workspace.openSessions", "workspace.noOpenSessions", "tab.closeLeft", "tab.closeRight",
            "tab.closeOthersInGroup", "tab.closeAllInGroup", "tab.splitRight", "tab.splitDown", "tab.moveToGroup",
            "tab.changeSplitOrientation", "tab.unsplit", "tab.unsplitAll", "tab.emptyGroup", "tab.closeEmptyGroup", "tab.duplicateSession", "tab.groupMenu",
            "sidebar.sortBy", "sidebar.sort.name", "sidebar.sort.type", "sidebar.sort.modifiedNewest", "sidebar.sort.modifiedOldest",
            "sidebar.sort.foldersFirst", "sidebar.expandAll", "sidebar.collapseAll", "sidebar.focus");

    private final JFrame frame = new JFrame("JLShell");
    private final Actions actions;
    private final Map<String, String> texts;
    private String sidebarSort = "name";
    private boolean foldersAlwaysOnTop = true;
    private final JPanel sidebar = new IslandPanel(new BorderLayout(), "panel");
    private TreePath hoveredConnectionPath;
    private final JTree tree = new JTree(new DefaultMutableTreeNode()) {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
                Rectangle bounds = hoveredConnectionPath == null ? null : getPathBounds(hoveredConnectionPath);
                if (bounds != null && !isPathSelected(hoveredConnectionPath)) {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(SwingTheme.color("treeHover"));
                    g.fillRoundRect(4, bounds.y, Math.max(0, getWidth() - 8), bounds.height, 6, 6);
                }
                super.paintComponent(g);
            } finally { g.dispose(); }
        }
    };
    private final JTextField search = new JTextField();
    private final JButton projects = new ChevronButton("JLShell");
    private List<ProjectProfile> projectChoices = List.of();
    private ProjectProfile activeProject;
    private java.util.function.Consumer<java.util.function.Consumer<List<SwingMenuBridge.Item>>> menuProvider;
    private Map<String, Runnable> settingsActions = Map.of();
    private String preferenceShortcut = "";
    private final JButton settingsButton;
    private JTabbedPane tabs;
    private final WorkspaceSplitLayout workspaceSplits;
    private final Map<JTabbedPane, TabDragController> tabDrags = new IdentityHashMap<>();
    private final Map<Object, Runnable> tabCloseActions = new LinkedHashMap<>();
    private final java.beans.PropertyChangeListener groupFocus = event -> {
        if (!(event.getNewValue() instanceof Component focused)) return;
        for (JTabbedPane group : List.copyOf(tabDrags.keySet())) {
            if (SwingUtilities.isDescendingFrom(focused, group) && group != tabs) { activateGroup(group, true); break; }
        }
    };
    private final JPanel headerMenus = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel workspace = new IslandPanel(new CardLayout(), "canvas");
    private final JPanel welcome = new JPanel(new BorderLayout());
    private final JPanel recent = new JPanel();
    private final JPanel favorites = new JPanel();
    private SwingStatusBar status;
    private final JPanel mainToolbar = new JPanel(new BorderLayout()) {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setPaint(new GradientPaint(0, 0, SwingTheme.color("headerTint"),
                        Math.max(400, getWidth() * .55f), 0, SwingTheme.color("frame")));
                g.fillRect(0, 0, getWidth(), getHeight());
            } finally { g.dispose(); }
        }
    };
    private final java.util.concurrent.CompletableFuture<Void> firstPaint = new java.util.concurrent.CompletableFuture<>();
    private final JPanel root = new JPanel(new BorderLayout()) {
        @Override protected void paintChildren(Graphics graphics) {
            super.paintChildren(graphics);
            if (isShowing() && getWidth() > 0 && getHeight() > 0) firstPaint.complete(null);
        }
    };

    public java.util.concurrent.CompletableFuture<Void> firstPaint() { return firstPaint; }
    private final JSplitPane vertical = new ResizeSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JPanel toolDock = new IslandPanel(new BorderLayout(), "canvas");
    private final JPanel dockBody = new JPanel(new BorderLayout());
    private final JLabel dockTitle = new JLabel() {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(UIManager.getColor("JLShell.formRaised"));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            } finally { g.dispose(); }
            super.paintComponent(graphics);
        }
    };
    private final JPanel toolNavigationHost = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel rightDockContainer = new JPanel(new BorderLayout());
    private final JPanel rightToolDock = new IslandPanel(new BorderLayout(), "canvas");
    private final JPanel rightDockBody = new JPanel(new BorderLayout());
    private final JLabel rightDockTitle = new JLabel();
    private final JButton toolConnection = new ChevronButton();
    private final JPanel rightToolNavigationHost = new JPanel();
    private final JPanel leftBottomStripe = stripeColumn();
    private final JPanel rightBottomStripe = stripeColumn();
    private JPanel leftRail;
    private final JPanel rightRail = new JPanel(new BorderLayout());
    private final JButton toolStripe;
    private final JButton projectStripe;
    private final JSplitPane mainSplit = new ResizeSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JPanel horizontalDock = new JPanel(new BorderLayout());
    private final JPanel workspaceCards = new JPanel(new CardLayout());
    private final JPanel maximizedWorkspace = new JPanel(new BorderLayout());
    private final JSplitPane maximizedSplit = new ResizeSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private JButton maximizeToolButton;
    private int dockDragGeneration;
    private final ToolDragFeedback toolDragFeedback = new ToolDragFeedback(frame);
    private JComponent toolDragSource;
    private String draggingToolKey;
    private final java.awt.event.AWTEventListener toolDragEventListener = event -> {
        if (draggingToolKey == null) return;
        if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_RELEASED
                && SwingUtilities.isLeftMouseButton(mouse) && mouse.getSource() != toolDragSource) {
            forwardToolDragEvent(mouse);
        } else if (event instanceof WindowEvent window && window.getWindow() == frame
                && window.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
            Window opposite = window.getOppositeWindow();
            if (!isOwnedByWorkbench(opposite)) cancelToolDrag();
        }
    };
    private AbstractButton draggedStripeButton;
    private final JComponent stripeDropSlot = new JComponent() {
        protected void paintComponent(Graphics g) {
            Graphics2D copy=(Graphics2D)g.create();
            try { copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(new Color(73,126,231));copy.fillRoundRect(1,1,getWidth()-2,getHeight()-2,7,7);
            } finally {copy.dispose();}
        }
    };
    private JPanel dropStripeHost;
    private int dropStripeIndex=-1;
    private Cursor toolDragCursor;
    private final ToolDockState toolLayout;
    private final boolean hasSavedToolLayout;
    private final java.util.Map<SwingSessionView, java.util.Set<String>> closedBottomTools = new java.util.WeakHashMap<>();
    private String rightKey = "files", bottomLeftKey = "monitor", bottomRightKey;
    private final JPanel bottomRightBody = new JPanel(new BorderLayout());
    private final JLabel bottomRightConnection = new JLabel();
    private final JPanel bottomRightNavigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel bottomRightDock = new IslandPanel(new BorderLayout(), "canvas");
    private final JSplitPane bottomSplit = new ResizeSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private boolean layingOutTools;
    private final javax.swing.Timer saveLayoutTimer = new javax.swing.Timer(250, event -> persistToolLayout());
    private final JComponent dockPreview = new JComponent() {
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try { g.setColor(new Color(54,106,207,100)); g.fillRect(0, 0, getWidth(), getHeight());
            } finally { g.dispose(); }
        }
    };
    private boolean rightToolMaximized;
    private int rightDockRestoreDivider = -1;
    private final JButton menuButton;
    private final JButton runButton;
    private final JButton connectionName = new ChevronButton();
    private final JPanel searchBar = new JPanel(new BorderLayout());
    private List<JMenu> mainMenus = List.of();
    private JMenuBar appMenuBar;
    private boolean toolWindowVisible = true;
    private boolean bottomRightWindowVisible;
    private Component toolWindowOwner;
    private static final int MIN_MONITOR_DOCK_HEIGHT = 96;
    private int dockHeight = 112;
    private boolean rightToolWindowVisible;
    private Component rightToolWindowOwner;
    private int rightToolPageIndex = -1;
    private final Map<Object, JPanel> tabHosts = new LinkedHashMap<>();
    private final JSplitPane split = new ResizeSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final Map<Object, Component> tabViews = new LinkedHashMap<>();
    private final Map<Object, String> tabConnectionSummaries = new LinkedHashMap<>();
    private final Map<Component, Object> viewTabs = new IdentityHashMap<>();
    private List<ConnectionProfile> profiles = List.of();
    private List<FolderProfile> folders = List.of();
    private FolderHierarchy folderHierarchy = FolderHierarchy.from(List.of());
    private Map<String, java.time.Instant> profileUpdatedAt = Map.of();
    private final Set<String> collapsed = new HashSet<>();
    private boolean rebuilding;
    private boolean selectingTab;
    private boolean sidebarVisible = true;
    private static final String WINDOW_SIZE_SETTING = "ui.workspace.windowSize";
    private boolean windowSizeReady;
    private Dimension lastSavedWindowSize;
    private Map<String, String> shutdownLayoutSnapshot;
    private final javax.swing.Timer saveWindowSizeTimer = new javax.swing.Timer(300, event -> persistWindowSize());
    private int sidebarWidth = 280;
    private final Map<KeyStroke, Runnable> shortcuts = new HashMap<>();
    private final KeyEventDispatcher shortcutDispatcher = event -> {
        if (event.getID() != java.awt.event.KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
        if (event.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE && toolDragFeedback.isShowing()) {
            cancelToolDrag(); return true;
        }
        Runnable action = shortcuts.get(KeyStroke.getKeyStrokeForEvent(event));
        if (action == null) {
            KeyStroke pressed = KeyStroke.getKeyStrokeForEvent(event);
            if (KeyStroke.getKeyStroke("shift F10").equals(pressed)
                    || KeyStroke.getKeyStroke("CONTEXT_MENU").equals(pressed)) {
                Object id = viewTabs.get(tabs.getSelectedComponent());
                if (id == null) return false;
                Rectangle bounds = tabs.getBoundsAt(tabs.getSelectedIndex());
                showTabContext(id, tabs, new Point(bounds.x, bounds.y + bounds.height));
                event.consume();
                return true;
            }
            KeyStroke closeKey = KeyStroke.getKeyStroke(com.formdev.flatlaf.util.SystemInfo.isMacOS ? "meta W" : "ctrl F4");
            if (!closeKey.equals(KeyStroke.getKeyStrokeForEvent(event)) || tabs.getSelectedComponent() == null) return false;
            action = tabCloseActions.get(viewTabs.get(tabs.getSelectedComponent()));
            if (action == null) return false;
        }
        action.run();
        event.consume();
        return true;
    };

    public SwingWorkbench(Actions actions, Map<String, String> texts) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Workbench requires EDT");
        this.actions = actions;
        this.texts = Map.copyOf(texts);
        hasSavedToolLayout=texts.get(ToolDockState.SETTING)!=null && !texts.get(ToolDockState.SETTING).isBlank();
        toolLayout = ToolDockState.decode(texts.get(ToolDockState.SETTING));
        sidebarWidth = toolLayout.sidebar;
        dockHeight = toolLayout.height;
        toolWindowVisible = toolLayout.leftVisible;
        bottomRightWindowVisible = toolLayout.bottomRightVisible;
        rightToolWindowVisible = toolLayout.rightVisible;
        bottomLeftKey = toolLayout.leftSelected; bottomRightKey = toolLayout.bottomRightSelected; rightKey = toolLayout.rightSelected;
        saveLayoutTimer.setRepeats(false);
        saveWindowSizeTimer.setRepeats(false);
        toolDragFeedback.setEventForwarder(this::forwardToolDragEvent);
        dockPreview.setName("workspace.toolDropPreview");
        stripeDropSlot.setName("workspace.toolDropSlot");
        stripeDropSlot.setPreferredSize(new Dimension(30,30));stripeDropSlot.setMinimumSize(new Dimension(30,30));stripeDropSlot.setMaximumSize(new Dimension(30,30));
        stripeDropSlot.setAlignmentX(Component.CENTER_ALIGNMENT);
        String configuredSort = texts.get("ui.sidebar.sort");
        sidebarSort = configuredSort != null
                && Set.of("name", "type", "modified-newest", "modified-oldest").contains(configuredSort)
                ? configuredSort : "name";
        foldersAlwaysOnTop = !"false".equals(texts.get("ui.sidebar.foldersFirst"));
        tabs = createTabGroup();
        workspaceSplits = new WorkspaceSplitLayout(tabs, this::createTabGroup);
        toolStripe = button(WorkbenchIcon.Kind.MONITOR, "workspace.tools", () -> toggleDockedTool("monitor"));
        projectStripe = railButton(WorkbenchIcon.Kind.SSH, "sidebar.toggle", () -> actions.toggleSidebar().run());
        menuButton = button(WorkbenchIcon.Kind.MENU, "menu.main", this::showMainMenu);
        runButton = button(WorkbenchIcon.Kind.RUN, "action.connect", this::connectSelection);
        settingsButton = new HeaderButton(new WorkbenchIcon(WorkbenchIcon.Kind.SETTINGS));
        settingsButton.setPreferredSize(new Dimension(28, 28));
        settingsButton.setFocusable(false);
        settingsButton.setToolTipText(text("action.preferences"));
        settingsButton.getAccessibleContext().setAccessibleName(text("action.preferences"));
        settingsButton.addActionListener(event -> showSettingsMenu());
        configureWindowHeader();
        frame.getRootPane().putClientProperty("jlshell.workbench", this);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcutDispatcher);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent event) { actions.quit().run(); }
        });
        var icon = getClass().getResource("/icons/brand/jlshell.png");
        if (icon != null) frame.setIconImage(new ImageIcon(icon).getImage());
        root.setBackground(SwingTheme.color("frame"));
        root.add(buildMainToolbar(), BorderLayout.NORTH);
        leftRail = buildRail();
        root.add(leftRail, BorderLayout.WEST);
        buildSidebar();
        workspace.add(buildWelcome(), "welcome");
        workspace.add(workspaceSplits, "tabs");
        split.setLeftComponent(sidebar);
        split.setRightComponent(workspace);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        sidebar.setMinimumSize(new Dimension(240, 0));
        workspace.setMinimumSize(new Dimension(240, 0));
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(4);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (rightToolMaximized || !sidebarVisible || split.getDividerLocation() <= 0) return;
            sidebarWidth = split.getDividerLocation();
        });
        buildToolDock();
        buildRightToolDock();
        mainSplit.setLeftComponent(split);
        mainSplit.setName("workspace.rightSplit");
        mainSplit.setRightComponent(null);
        mainSplit.setResizeWeight(1);
        mainSplit.setContinuousLayout(true);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        mainSplit.setOpaque(false);
        mainSplit.setDividerSize(0);
        mainSplit.setEnabled(false);
        mainSplit.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (mainSplit.getRightComponent() != null && rightToolDock.isVisible()) updateRightDockWidth(true);
            }
        });
        horizontalDock.setOpaque(false);
        horizontalDock.add(mainSplit, BorderLayout.CENTER);
        root.add(rightRail, BorderLayout.EAST);
        vertical.setTopComponent(horizontalDock);
        vertical.setResizeWeight(1);
        vertical.setContinuousLayout(true);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        vertical.setOpaque(false);
        vertical.setDividerSize(0);
        JPanel islands = new JPanel(new BorderLayout());
        islands.setOpaque(false);
        islands.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        workspaceCards.setOpaque(false);
        maximizedWorkspace.setOpaque(false);
        workspaceCards.add(vertical, "normal");
        maximizedSplit.setName("workspace.maximizedSplit");
        maximizedSplit.setRightComponent(maximizedWorkspace);
        maximizedSplit.setResizeWeight(0);
        maximizedSplit.setContinuousLayout(true);
        maximizedSplit.setBorder(BorderFactory.createEmptyBorder());
        maximizedSplit.setOpaque(false);
        maximizedSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!layingOutTools && rightToolMaximized && sidebarVisible && maximizedSplit.getDividerLocation() > 0) {
                sidebarWidth = maximizedSplit.getDividerLocation();
                toolLayout.sidebar = sidebarWidth; saveLayoutTimer.restart();
            }
        });
        workspaceCards.add(maximizedSplit, "maximizedTool");
        islands.add(workspaceCards);
        root.add(islands, BorderLayout.CENTER);
        status = new SwingStatusBar(this::text, com.jlshell.core.support.RecentApplicationLogs.shared());
        status.sources(() -> {
            Object id = viewTabs.get(tabs.getSelectedComponent());
            return tabViews.get(id) instanceof SwingSessionView session ? session.statusInfo() : null;
        }, () -> tabViews.values().stream().filter(SwingSessionView.class::isInstance)
                .map(SwingSessionView.class::cast).map(SwingSessionView::activeTransfer)
                .filter(java.util.Objects::nonNull).toList());
        root.add(status, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(640, 480));
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
        Dimension usable = new Dimension(screen.width - screenInsets.left - screenInsets.right, screen.height - screenInsets.top - screenInsets.bottom);
        frame.setSize(restoredWindowSize(texts.get(WINDOW_SIZE_SETTING), usable));
        lastSavedWindowSize = frame.getSize();
        frame.setLocationRelativeTo(null);
        frame.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (windowSizeReady && frame.isShowing() && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) saveWindowSizeTimer.restart();
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", groupFocus);

    }

    private JTabbedPane createTabGroup() {
        JTabbedPane group = new IslandTabbedPane();
        group.setOpaque(false);
        group.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        group.setMinimumSize(new Dimension(100, 80));
        group.putClientProperty("JLShell.emptyGroupText", text("tab.emptyGroup"));
        JButton more = button(WorkbenchIcon.Kind.MORE, "tab.groupMenu", () -> {
            Object id = viewTabs.get(group.getSelectedComponent());
            if (id != null) showTabContext(id, group, new Point(Math.max(0, group.getWidth() - 230), 30));
        });
        JButton closeEmpty = new JButton(text("tab.closeEmptyGroup"), new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE));
        closeEmpty.setName("workspace.closeEmptyGroup");
        closeEmpty.putClientProperty("JButton.buttonType", "toolBarButton");
        closeEmpty.setToolTipText(text("tab.closeEmptyGroup"));
        closeEmpty.getAccessibleContext().setAccessibleName(text("tab.closeEmptyGroup"));
        closeEmpty.addActionListener(event -> closeEmptyGroup(group));
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        trailing.setOpaque(false);
        trailing.add(more);
        trailing.add(closeEmpty);
        more.setVisible(false);
        group.putClientProperty("JTabbedPane.trailingComponent", trailing);
        group.addChangeListener(event -> {
            boolean empty = group.getTabCount() == 0;
            more.setVisible(!empty);
            closeEmpty.setVisible(empty);
            trailing.revalidate();
            if (!selectingTab) activateGroup(group, true);
        });
        var drag = new TabDragController(group, host -> {
            selectTab(viewTabs.get(host));
            actions.selectTab().accept(viewTabs.get(host));
        }, (host, position) -> actions.reorder().accept(viewTabs.get(host), orderedHosts().indexOf(host)),
                (host, position) -> moveTabLocally(group, host, position), (host, rootPoint) -> {
                    Point point = SwingUtilities.convertPoint(frame.getLayeredPane(), rootPoint, workspaceSplits);
                    WorkspaceSplitLayout.DropLocation drop = workspaceSplits.dropLocation(point);
                    if (drop == null) return false;
                    JTabbedPane destination = drop.group();
                    boolean splitSourceGroup = drop.side() != WorkspaceSplitLayout.DropSide.CENTER && destination == group;
                    if (drop.side() == WorkspaceSplitLayout.DropSide.CENTER) {
                        if (destination == group) return false;
                        transferTab(host, group, destination);
                    } else {
                        int orientation = drop.side() == WorkspaceSplitLayout.DropSide.LEFT
                                || drop.side() == WorkspaceSplitLayout.DropSide.RIGHT
                                ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
                        boolean newGroupFirst = drop.side() == WorkspaceSplitLayout.DropSide.LEFT
                                || drop.side() == WorkspaceSplitLayout.DropSide.TOP;
                        JTabbedPane splitTarget = workspaceSplits.split(destination, orientation, newGroupFirst);
                        transferTab(host, group, splitTarget);
                        destination = splitTarget;
                    }
                    activateGroup(destination, true);
                    // Splitting a group's only tab still leaves the original pane as the other side of the split.
                    if (!splitSourceGroup) pruneEmptyGroup(group);
                    actions.reorder().accept(viewTabs.get(host), orderedHosts().indexOf(host));
                    return true;
                }, rootPoint -> {
                    Point point = SwingUtilities.convertPoint(frame.getLayeredPane(), rootPoint, workspaceSplits);
                    WorkspaceSplitLayout.DropLocation drop = workspaceSplits.dropLocation(point);
                    if (drop == null || drop.side() == WorkspaceSplitLayout.DropSide.CENTER) return null;
                    return SwingUtilities.convertRectangle(workspaceSplits, drop.preview(), frame.getLayeredPane());
                });
        tabDrags.put(group, drag);
        group.addMouseListener(new MouseAdapter() {
            private boolean popupShown;
            public void mousePressed(MouseEvent event) {
                popupShown = false;
                if (SwingUtilities.isLeftMouseButton(event) && group.getTabCount() == 0) activateGroup(group, false);
                if (isPopupRequest(event)) { popup(event); popupShown = true; }
            }
            public void mouseReleased(MouseEvent event) {
                if (!popupShown && isPopupRequest(event)) popup(event);
                popupShown = false;
            }
            private void popup(MouseEvent event) {
                int index = group.indexAtLocation(event.getX(), event.getY());
                if (index >= 0) showTabContext(viewTabs.get(group.getComponentAt(index)), group, event.getPoint());
            }
        });
        return group;
    }

    private void activateGroup(JTabbedPane group, boolean notify) {
        tabs = group;
        if (toolStripe == null) return; // 构造初始空标签组时尚未安装工作台控件。
        syncToolWindow(); updateSessionSelector();
        Object id = viewTabs.get(group.getSelectedComponent());
        if (notify && id != null) actions.selectTab().accept(id);
    }

    private JTabbedPane groupFor(Component host) {
        return host == null ? null : (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, host);
    }

    private List<Component> orderedHosts() {
        var order = new ArrayList<Component>();
        for (JTabbedPane group : workspaceSplits.groups()) {
            for (int i = 0; i < group.getTabCount(); i++) order.add(group.getComponentAt(i));
        }
        return order;
    }

    private String text(String key) { return texts.getOrDefault(key, key); }

    private void configureWindowHeader() {
        // OpenJDK 的完整窗口内容 + FlatLaf 原生按钮占位，与 IDEA 的一体化 header 对齐。
        var pane = frame.getRootPane();
        pane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.FULL_WINDOW_CONTENT, true);
        pane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        pane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        if (com.formdev.flatlaf.util.SystemInfo.isMacOS) {
            pane.putClientProperty("apple.awt.fullWindowContent", true);
            pane.putClientProperty("apple.awt.transparentTitleBar", true);
            pane.putClientProperty("apple.awt.windowTitleVisible", false);
            // Leave spacing unset: FlatLaf's medium/large options add padding around traffic lights.
        }
    }

    private JPanel buildMainToolbar() {
        mainToolbar.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        mainToolbar.setPreferredSize(new Dimension(0, 34));
        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        identity.setOpaque(false);
        JPanel macButtons = new JPanel();
        macButtons.putClientProperty(com.formdev.flatlaf.FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac horizontal");
        macButtons.setOpaque(false);
        identity.add(macButtons);
        identity.add(menuButton);
        projects.putClientProperty("JButton.buttonType", "toolBarButton");
        projects.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 22));
        projects.setHorizontalAlignment(SwingConstants.LEFT);
        projects.setIconTextGap(6);
        projects.setFocusable(false);
        projects.addActionListener(event -> showProjectPopup());
        identity.add(projects);
        headerMenus.setOpaque(false);
        identity.add(headerMenus);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        toolbar.setOpaque(false);
        connectionName.setForeground(SwingTheme.color("secondary"));
        connectionName.putClientProperty("JButton.buttonType", "toolBarButton");
        connectionName.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 22));
        connectionName.setFocusable(false);
        connectionName.setToolTipText(text("workspace.openSessions"));
        connectionName.getAccessibleContext().setAccessibleName(text("workspace.openSessions"));
        connectionName.addActionListener(event -> showSessionPopup());
        connectionName.setText(text("workspace.noOpenSessions"));
        connectionName.setEnabled(false);
        toolbar.add(connectionName);
        toolbar.add(settingsButton);
        JPanel windowButtons = WindowHeaderSupport.buttonsPlaceholder("win", 12);
        toolbar.add(windowButtons);
        mainToolbar.add(identity, BorderLayout.WEST);
        mainToolbar.add(toolbar, BorderLayout.EAST);
        mainToolbar.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { updateHeaderMenuVisibility(); }
        });
        // 控件仍可点击；空白区域拖动窗口。最大化窗口不做手动移动。
        mainToolbar.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    frame.setExtendedState(frame.getExtendedState() == JFrame.NORMAL ? JFrame.MAXIMIZED_BOTH : JFrame.NORMAL);
                }
            }
        });
        WindowHeaderSupport.installDragSupport(frame, mainToolbar);
        return mainToolbar;
    }

    private JPanel stripeColumn() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        return column;
    }

    private void addStripe(JPanel column, JButton button) {
        button.setPreferredSize(new Dimension(30, 30));
        button.setMaximumSize(new Dimension(30, 30));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(button);
        column.add(Box.createVerticalStrut(8));
    }

    private JPanel buildRail() {
        JPanel rail = new JPanel(new BorderLayout());
        rail.setOpaque(false);
        rail.setPreferredSize(new Dimension(36, 0));
        JPanel top = stripeColumn();
        top.setBorder(BorderFactory.createEmptyBorder(4, 3, 0, 3));
        projectStripe.setSelected(sidebarVisible);
        addStripe(top, projectStripe);
        rail.add(top, BorderLayout.NORTH);
        rail.setName("workspace.leftStripe");
        leftBottomStripe.setName("workspace.bottomLeftStripe");
        leftBottomStripe.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        rail.add(leftBottomStripe, BorderLayout.SOUTH);
        return rail;
    }

    private void buildSidebar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        JLabel heading = new JLabel(text("sidebar.connections"));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setHorizontalTextPosition(SwingConstants.LEFT);
        heading.setIconTextGap(2);
        header.add(heading);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        tools.setOpaque(false);
        tools.add(sidebarButton(WorkbenchIcon.Kind.ADD, "action.newConnection", actions.create()));
        tools.add(sidebarButton(WorkbenchIcon.Kind.FOCUS, "sidebar.focus", this::focusSelection));
        tools.add(sidebarButton(WorkbenchIcon.Kind.EXPAND_ALL, "sidebar.expandAll", this::expandAll));
        tools.add(sidebarButton(WorkbenchIcon.Kind.COLLAPSE_ALL, "sidebar.collapseAll", this::collapseAll));
        JButton more = sidebarButton(WorkbenchIcon.Kind.MORE, "menu.main", () -> {});
        more.addActionListener(event -> {
            var rows = List.of(
                    new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text("sidebar.sortBy"), null, null, "", null,
                            true, sortRows(), null),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("sidebar.search.prompt"),
                            new WorkbenchIcon(WorkbenchIcon.Kind.SEARCH), "", null, this::toggleSearch),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("sidebar.newFolder"), new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER), "", null, actions.folder()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.editConnection"), null, "", null, actions.edit()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.deleteConnection"), null, "", null, actions.delete()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.refresh"), null, "", "", actions.refresh()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("project.menu.manage"), new WorkbenchIcon(WorkbenchIcon.Kind.PROJECT), "", null, actions.projects()));
            var popup = new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 300, false, "");
            popup.show(more, 0, more.getHeight());
        });
        tools.add(more);
        tools.add(sidebarButton(WorkbenchIcon.Kind.HIDE, "sidebar.toggle", actions.toggleSidebar()));
        header.add(tools, BorderLayout.EAST);
        top.add(header, BorderLayout.NORTH);
        search.putClientProperty("JTextField.placeholderText", text("sidebar.search.prompt"));
        search.putClientProperty("JTextField.showClearButton", true);
        searchBar.setOpaque(false);
        searchBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        searchBar.add(search);
        searchBar.setVisible(false);
        search.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "hideSearch");
        search.getActionMap().put("hideSearch", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) { toggleSearch(); }
        });
        top.add(searchBar, BorderLayout.SOUTH);
        sidebar.add(top, BorderLayout.NORTH);
        tree.setOpaque(false);
        tree.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                TreePath path = tree.getClosestPathForLocation(event.getX(), event.getY());
                Rectangle bounds = path == null ? null : tree.getPathBounds(path);
                updateConnectionHover(bounds != null && event.getY() >= bounds.y && event.getY() < bounds.y + bounds.height ? path : null);
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent event) { updateConnectionHover(null); }
        });
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setLargeModel(true);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new SidebarTransferHandler());
        updateTreeRowHeight();
        tree.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            { putClientProperty("html.disable", true); setIconTextGap(6); }
            @Override public void setBounds(int x, int y, int width, int height) {
                // CellRendererPane 给出节点缩进后的实际 x；限制标签宽度，让 JLabel 原生绘制省略号。
                // 不改变树节点的名称、首选宽度及命中范围，缩放侧栏时也无需重建树模型。
                int available = tree.getWidth() - tree.getInsets().right - x;
                super.setBounds(x, y, tree.getWidth() > 0 && x >= 0 ? Math.min(width, Math.max(0, available)) : width, height);
            }
            private final Icon folderIcon = new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER);
            private final Icon serverIcon = new WorkbenchIcon(WorkbenchIcon.Kind.SERVER);
            private final Icon terminalIcon = new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL);
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                         boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object data = ((DefaultMutableTreeNode) value).getUserObject();
                setFont(tree.getFont());
                setBackgroundNonSelectionColor(null);
                setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
                setText(""); setIcon(null); setToolTipText(null);
                if (data instanceof SidebarItem item) {
                    setText(item.displayName());
                    setIcon(item instanceof SidebarItem.FolderItem ? folderIcon
                            : ((SidebarItem.ConnectionItem) item).connectionType() == ConnectionType.LOCAL_SHELL ? terminalIcon : serverIcon);
                    String name = item.displayName().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    String summary = item instanceof SidebarItem.ConnectionItem connection ? connection.summary() : "";
                    setToolTipText("<html><div width='320'>" + name + (summary == null || summary.isBlank() ? ""
                            : "<br>" + summary.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")) + "</div></html>");
                }
                return this;
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(event -> {
            List<SidebarItem> selection = selectedItems();
            actions.select().accept(selection);
            runButton.setEnabled(selection.stream().anyMatch(SidebarItem.ConnectionItem.class::isInstance));
        });
        runButton.setEnabled(false);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            public void treeExpanded(TreeExpansionEvent event) { change(event, true); }
            public void treeCollapsed(TreeExpansionEvent event) { change(event, false); }
            private void change(TreeExpansionEvent event, boolean expanded) {
                if (rebuilding || !search.getText().isBlank()) return;
                var node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (node.getUserObject() instanceof SidebarItem.FolderItem folder) {
                    if (expanded) collapsed.remove(folder.id()); else collapsed.add(folder.id());
                    actions.collapsed().accept(Set.copyOf(collapsed));
                }
            }
        });
        javax.swing.Timer searchDelay = new javax.swing.Timer(140, event -> rebuildTree());
        searchDelay.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { searchDelay.restart(); }
            public void removeUpdate(DocumentEvent event) { searchDelay.restart(); }
            public void changedUpdate(DocumentEvent event) { searchDelay.restart(); }
        });
        tree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) connectSelection(); }
            public void mousePressed(MouseEvent event) { popup(event); }
            public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                if (!tree.isPathSelected(path)) tree.setSelectionPath(path);
                var node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof SidebarItem item) actions.context().accept(item,
                        specs -> SwingUtilities.invokeLater(() -> popupMenu(specs).show(tree, event.getX(), event.getY())));
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "connect");
        tree.getActionMap().put("connect", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent event) { connectSelection(); }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        sidebar.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        runButton.setText(text("action.connect"));
        runButton.setHorizontalAlignment(SwingConstants.LEFT);
        runButton.putClientProperty("JButton.buttonType", "roundRect");
        runButton.setMargin(new Insets(5, 10, 5, 10));
        runButton.setPreferredSize(new Dimension(0, 32));
        footer.add(runButton);
        sidebar.add(footer, BorderLayout.SOUTH);
    }

    private JPanel buildWelcome() {
        welcome.setName("welcome.page");
        JPanel content = new JPanel(new BorderLayout(0, 24)); content.setOpaque(false);
        content.setName("welcome.content");
        JPanel hero = new JPanel(new BorderLayout(0, 22)); hero.setOpaque(false);
        JPanel brand = new JPanel(new BorderLayout(18, 0)); brand.setOpaque(false);
        brand.add(new JLabel(new SwingBrandIcon(60)), BorderLayout.WEST);
        JPanel titles = new JPanel(); titles.setOpaque(false); titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(text("welcome.title")); title.setFont(title.getFont().deriveFont(Font.BOLD, 34f));
        titles.add(title); titles.add(Box.createVerticalStrut(5));
        titles.add(welcomeSectionLabel(text("welcome.subtitle")));
        brand.add(titles); hero.add(brand, BorderLayout.NORTH);
        JPanel introduction = new JPanel(); introduction.setOpaque(false); introduction.setLayout(new BoxLayout(introduction, BoxLayout.Y_AXIS));
        JLabel tagline = new JLabel(text("welcome.tagline")); tagline.setFont(tagline.getFont().deriveFont(Font.PLAIN,18f));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT); introduction.add(tagline); introduction.add(Box.createVerticalStrut(20));
        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); actionsRow.setOpaque(false);
        actionsRow.setBorder(BorderFactory.createEmptyBorder(0,-10,0,0)); actionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton create = new JButton(text("action.newConnection")); create.setName("welcome.create");
        create.putClientProperty("JButton.buttonType", "roundRect");
        create.setBackground(UIManager.getColor("Component.focusColor")); create.setForeground(Color.WHITE);
        create.putClientProperty("JLShell.primaryAction", true);
        create.setFont(create.getFont().deriveFont(Font.BOLD));
        create.setMargin(new Insets(10, 22, 10, 22)); create.addActionListener(event -> actions.create().run()); actionsRow.add(create);
        JButton folder = new JButton(text("sidebar.newFolder"), new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER));
        folder.setName("welcome.folder"); folder.putClientProperty("JButton.buttonType", "roundRect");
        folder.setMargin(new Insets(10,16,10,16)); folder.addActionListener(event -> actions.folder().run()); actionsRow.add(folder);
        introduction.add(actionsRow); hero.add(introduction); content.add(hero, BorderLayout.NORTH);

        JPanel lists = new JPanel(new GridLayout(1, 2, 18, 18)); lists.setOpaque(false);
        lists.add(welcomeList(text("welcome.recentConnections"), recent));
        lists.add(welcomeList(text("welcome.favorites"), favorites));
        content.add(lists);
        JPanel footer = new JPanel(new BorderLayout(0, 12)); footer.setOpaque(false);
        JPanel shortcuts = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 0)); shortcuts.setOpaque(false);
        shortcuts.setBorder(BorderFactory.createEmptyBorder(0,-20,0,0));
        String mod = com.formdev.flatlaf.util.SystemInfo.isMacOS ? "⌘" : "Ctrl";
        shortcuts.add(welcomeSectionLabel(text("welcome.help.newConnection").replace("{0}", mod)));
        shortcuts.add(welcomeSectionLabel(text("welcome.help.refreshConnections").replace("{0}", mod)));
        footer.add(shortcuts); footer.add(welcomeSectionLabel(text("welcome.help.doubleClick")), BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);

        class Canvas extends JPanel implements Scrollable {
            Canvas() { super(null); setOpaque(false); add(content); }
            private int contentWidth() { int available=getParent()==null?getWidth():getParent().getWidth(); return Math.min(840, Math.max(220, available-64)); }
            private int contentHeight() {
                boolean narrow = contentWidth() < 580;
                ((GridLayout)lists.getLayout()).setColumns(narrow ? 1 : 2);
                ((GridLayout)lists.getLayout()).setRows(narrow ? 2 : 1);
                lists.setPreferredSize(new Dimension(0, narrow ? 500 : 320));
                return hero.getPreferredSize().height + footer.getPreferredSize().height + (narrow ? 500 : 320) + 48;
            }
            @Override public void doLayout() {
                int width = contentWidth(), height = contentHeight();
                content.setBounds(Math.max(20, (getWidth()-width)/2), Math.max(32, (getHeight()-height)/3), width, height);
                content.doLayout();
            }
            @Override public Dimension getPreferredSize() { return new Dimension(0, contentHeight()+64); }
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r,int o,int d) { return 24; }
            public int getScrollableBlockIncrement(Rectangle r,int o,int d) { return Math.max(24,r.height-24); }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() { return getParent()!=null && getPreferredSize().height<getParent().getHeight(); }
        }
        JScrollPane scroll = new JScrollPane(new Canvas()); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        welcome.add(scroll); return welcome;
    }

    private JLabel welcomeSectionLabel(String value) {
        JLabel label = new JLabel(value); label.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN)); label.setForeground(SwingTheme.color("secondary"));
        label.putClientProperty("JLShell.secondaryForeground", true); return label;
    }

    private JPanel welcomeList(String title, JPanel rows) {
        JPanel card = new JPanel(new BorderLayout(0, 14)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(SwingTheme.color("formRaised")); g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,20,20);
                g.setColor(SwingTheme.color("border")); g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,20,20); g.dispose();
            }
        };
        card.setOpaque(false); card.setBorder(BorderFactory.createEmptyBorder(18,14,12,14));
        JLabel heading = new JLabel(title); heading.setFont(heading.getFont().deriveFont(Font.BOLD,14f));
        heading.setBorder(BorderFactory.createEmptyBorder(0,4,0,0)); card.add(heading, BorderLayout.NORTH);
        rows.setLayout(new BoxLayout(rows,BoxLayout.Y_AXIS)); rows.setOpaque(false);
        JScrollPane scroll = new JScrollPane(rows); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24); card.add(scroll); return card;
    }

    public void updateWelcome(List<RecentSessionProfile> sessions, List<FavoriteConnectionProfile> starred) {
        recent.removeAll(); favorites.removeAll();
        var seen = new java.util.HashSet<String>();
        for (var session : sessions) if (seen.add(session.connectionId()))
            recent.add(welcomeRow(session.connectionId(),session.displayName(),session.summary()));
        for (var item : starred) favorites.add(welcomeRow(item.id(),item.displayName(),item.hostInfo()));
        if (recent.getComponentCount()==0) recent.add(welcomeEmpty(text("welcome.noRecentConnections"),WorkbenchIcon.Kind.SSH));
        if (favorites.getComponentCount()==0) favorites.add(welcomeEmpty(text("welcome.noFavorites"),WorkbenchIcon.Kind.PIN));
        recent.revalidate(); favorites.revalidate(); recent.repaint(); favorites.repaint();
    }

    private JComponent welcomeEmpty(String text, WorkbenchIcon.Kind icon) {
        JPanel empty = new JPanel(new BorderLayout(0,12)); empty.setOpaque(false);
        empty.setBorder(BorderFactory.createEmptyBorder(32,8,24,8));
        JLabel symbol = new JLabel(new WorkbenchIcon(icon)); symbol.setHorizontalAlignment(SwingConstants.CENTER);
        JLabel label = welcomeSectionLabel(text); label.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel copy = new JPanel(new BorderLayout(0,10)); copy.setOpaque(false); copy.add(label, BorderLayout.NORTH);
        String hintText = text(icon == WorkbenchIcon.Kind.PIN ? "welcome.favoriteHint" : "welcome.recentHint");
        JLabel hint = welcomeSectionLabel("<html><div style='text-align:center' width='220'>"+hintText+"</div></html>");
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        copy.add(hint); empty.add(symbol,BorderLayout.NORTH); empty.add(copy);
        empty.setMaximumSize(new Dimension(Integer.MAX_VALUE,190));
        return empty;
    }

    private JButton welcomeRow(String id, String name, String summary) {
        JButton row = new JButton() {
            @Override protected void paintComponent(Graphics graphics) {
                if (getModel().isRollover() || getModel().isPressed() || isFocusOwner()) {
                    Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(SwingTheme.color("headerHover"));g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);g.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        row.setLayout(new BorderLayout(12,0)); row.setContentAreaFilled(false); row.setBorderPainted(false);
        row.setBorder(BorderFactory.createEmptyBorder(10,10,10,10)); row.setRolloverEnabled(true);
        row.setName("welcome.connection."+id); row.setToolTipText(name+" · "+summary);
        row.getAccessibleContext().setAccessibleName(name+" · "+summary);
        // Decorative children must not become separate mouse targets: the whole row is one button.
        row.add(new JLabel(new WorkbenchIcon(WorkbenchIcon.Kind.SERVER)) {
            @Override public boolean contains(int x, int y) { return false; }
        },BorderLayout.WEST);
        JPanel details=new JPanel(new GridLayout(2,1,0,4)) {
            @Override public boolean contains(int x, int y) { return false; }
        }; details.setOpaque(false);
        JLabel title=new JLabel(name); title.setFont(title.getFont().deriveFont(Font.BOLD));
        details.add(title); details.add(welcomeSectionLabel(summary)); row.add(details);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,64)); row.setPreferredSize(new Dimension(0,64));
        row.addActionListener(event -> profiles.stream().filter(profile -> profile.id().equals(id)).findFirst().ifPresent(actions.connect()));
        MouseAdapter context = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { showContext(event); }
            @Override public void mouseReleased(MouseEvent event) { showContext(event); }
            private void showContext(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                profiles.stream().filter(profile -> profile.id().equals(id)).findFirst().ifPresent(profile -> {
                    SidebarItem item = new SidebarItem.ConnectionItem(profile.id(), profile.displayName(),
                            profile.connectionType(), profile.summary());
                    actions.select().accept(List.of(item));
                    Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), row);
                    actions.context().accept(item, specs -> SwingUtilities.invokeLater(() -> {
                        if (row.isShowing()) popupMenu(specs).show(row, point.x, point.y);
                    }));
                });
                event.consume();
            }
        };
        installWelcomeContext(row, context);
        return row;
    }

    private static void installWelcomeContext(Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        if (component instanceof Container parent)
            for (Component child : parent.getComponents()) installWelcomeContext(child, listener);
    }

    private JButton button(WorkbenchIcon.Kind kind, String tooltip, Runnable action) {
        JButton button = new JButton(new WorkbenchIcon(kind));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText(text(tooltip));
        button.getAccessibleContext().setAccessibleName(text(tooltip));
        button.setPreferredSize(new Dimension(28, 28));
        button.setFocusable(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JButton sidebarButton(WorkbenchIcon.Kind kind, String tooltip, Runnable action) {
        JButton button = new HeaderButton(new WorkbenchIcon(kind), 6);
        button.setToolTipText(text(tooltip));
        button.getAccessibleContext().setAccessibleName(text(tooltip));
        button.setPreferredSize(new Dimension(28, 28));
        button.setFocusable(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private JButton railButton(WorkbenchIcon.Kind kind, String tooltip, Runnable action) {
        JButton button = new JButton(new WorkbenchIcon(kind)) {
            @Override protected void paintComponent(Graphics graphics) {
                if (isSelected()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(SwingTheme.color("railSelection"));
                        g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 8, 8);
                    } finally { g.dispose(); }
                }
                super.paintComponent(graphics);
            }
        };
        button.putClientProperty("JLShell.selectedRail", true);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setToolTipText(text(tooltip));
        button.getAccessibleContext().setAccessibleName(text(tooltip));
        button.addActionListener(event -> action.run());
        return button;
    }

    private List<SidebarItem> selectedItems() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return List.of();
        return Arrays.stream(paths).map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
                .filter(SidebarItem.class::isInstance).map(SidebarItem.class::cast).toList();
    }

    private void connectSelection() {
        selectedItems().stream().filter(SidebarItem.ConnectionItem.class::isInstance).findFirst().ifPresent(item ->
                profiles.stream().filter(profile -> profile.id().equals(item.id())).findFirst().ifPresent(actions.connect()));
    }

    private List<com.jlshell.ui.swing.idea.IdeaListPopup.Row> sortRows() {
        return List.of(
                sortRow("sidebar.sort.name", "name"),
                sortRow("sidebar.sort.type", "type"),
                sortRow("sidebar.sort.modifiedNewest", "modified-newest"),
                sortRow("sidebar.sort.modifiedOldest", "modified-oldest"),
                new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text("sidebar.sort.foldersFirst"), null,
                        foldersAlwaysOnTop ? new WorkbenchIcon(WorkbenchIcon.Kind.CHECK) : null,
                        "", null, true, List.of(), () -> {
                    foldersAlwaysOnTop = !foldersAlwaysOnTop;
                    actions.setSetting().accept("ui.sidebar.foldersFirst", Boolean.toString(foldersAlwaysOnTop));
                    rebuildTree();
                })
        );
    }

    private com.jlshell.ui.swing.idea.IdeaListPopup.Row sortRow(String titleKey, String value) {
        return new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text(titleKey), null,
                value.equals(sidebarSort) ? new WorkbenchIcon(WorkbenchIcon.Kind.CHECK) : null,
                "", null, true, List.of(), () -> setSidebarSort(value));
    }

    private void setSidebarSort(String value) {
        sidebarSort = value;
        actions.setSetting().accept("ui.sidebar.sort", value);
        rebuildTree();
    }

    private void expandAll() {
        collapsed.clear();
        actions.collapsed().accept(Set.of());
        rebuilding = true;
        try { for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row); }
        finally { rebuilding = false; }
    }

    private void collapseAll() {
        collapsed.clear();
        folders.forEach(folder -> collapsed.add(folder.id()));
        actions.collapsed().accept(Set.copyOf(collapsed));
        rebuilding = true;
        try { for (int row = tree.getRowCount() - 1; row >= 0; row--) tree.collapseRow(row); }
        finally { rebuilding = false; }
    }

    private void focusSelection() {
        // 回调通过活动会话模型异步更新 EDT；不要抢先滚动或聚焦当前旧选择。
        actions.focusActiveConnection().run();
    }

    private void updateConnectionHover(TreePath path) {
        if (java.util.Objects.equals(path, hoveredConnectionPath)) return;
        TreePath previous = hoveredConnectionPath; hoveredConnectionPath = path;
        for (TreePath changed : new TreePath[]{previous, path}) {
            Rectangle bounds = changed == null ? null : tree.getPathBounds(changed);
            if (bounds != null) tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }

    /** Reveal and select a connection for the currently active session. */
    public void focusConnection(String profileId) {
        if (profileId == null) return;
        if (!search.getText().isBlank()) { search.setText(""); rebuildTree(); }
        var nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof SidebarItem item && profileId.equals(item.id())) {
                TreePath path = new TreePath(node.getPath());
                rebuilding = true;
                try {
                    TreePath parent = path.getParentPath();
                    while (parent != null) { tree.expandPath(parent); parent = parent.getParentPath(); }
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                } finally { rebuilding = false; }
                tree.requestFocusInWindow();
                return;
            }
        }
    }

    public void updateConnections(List<FolderProfile> folders, List<ConnectionProfile> profiles, Set<String> collapsed) {
        folderHierarchy = FolderHierarchy.from(folders);
        this.folders = folderHierarchy.folders(); this.profiles = List.copyOf(profiles);
        this.profileUpdatedAt = profiles.stream().collect(java.util.stream.Collectors.toMap(ConnectionProfile::id,
                profile -> profile.updatedAt() == null ? java.time.Instant.EPOCH : profile.updatedAt(), (left, right) -> left));
        this.collapsed.clear(); this.collapsed.addAll(collapsed);
        rebuildTree();
    }

    private void rebuildTree() {
        hoveredConnectionPath = null;
        Set<String> selection = new HashSet<>();
        selectedItems().forEach(item -> selection.add(item.id()));
        rebuilding = true;
        try {
            var root = new DefaultMutableTreeNode();
            Map<String, DefaultMutableTreeNode> directory = new HashMap<>();
            for (var folder : folders) directory.put(folder.id(), new DefaultMutableTreeNode(
                    new SidebarItem.FolderItem(folder.id(), folder.name(), folder.parentId())));
            for (var folder : folders) directory.getOrDefault(folder.parentId(), root).add(directory.get(folder.id()));
            for (var profile : profiles) directory.getOrDefault(profile.folderId(), root).add(new DefaultMutableTreeNode(
                    new SidebarItem.ConnectionItem(profile.id(), profile.displayName(), profile.connectionType(), profile.summary())));
            sortTree(root);
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            if (!query.isBlank()) filter(root, query);
            tree.setModel(new DefaultTreeModel(root));
            List<TreePath> selected = new ArrayList<>();
            var nodes = root.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                var node = (DefaultMutableTreeNode) nodes.nextElement();
                TreePath path = new TreePath(node.getPath());
                if (node.getUserObject() instanceof SidebarItem item) {
                    if (item instanceof SidebarItem.FolderItem && (!query.isBlank() || !collapsed.contains(item.id()))) tree.expandPath(path);
                    if (selection.contains(item.id())) selected.add(path);
                }
            }
            tree.setSelectionPaths(selected.toArray(TreePath[]::new));
        } finally { rebuilding = false; }
    }

    private void sortTree(DefaultMutableTreeNode parent) {
        var children = new ArrayList<DefaultMutableTreeNode>();
        for (int i = 0; i < parent.getChildCount(); i++) children.add((DefaultMutableTreeNode) parent.getChildAt(i));
        children.sort((left, right) -> compareTreeItems(left.getUserObject(), right.getUserObject()));
        parent.removeAllChildren();
        children.forEach(parent::add);
        children.stream().filter(node -> node.getUserObject() instanceof SidebarItem.FolderItem).forEach(this::sortTree);
    }

    private int compareTreeItems(Object left, Object right) {
        boolean leftFolder = left instanceof SidebarItem.FolderItem;
        boolean rightFolder = right instanceof SidebarItem.FolderItem;
        if (leftFolder != rightFolder && foldersAlwaysOnTop) return leftFolder ? -1 : 1;
        if ("type".equals(sidebarSort) && !leftFolder && !rightFolder) {
            var leftItem = (SidebarItem.ConnectionItem) left;
            var rightItem = (SidebarItem.ConnectionItem) right;
            String leftType = leftItem.connectionType() == null ? "" : leftItem.connectionType().name();
            String rightType = rightItem.connectionType() == null ? "" : rightItem.connectionType().name();
            int byType = leftType.compareToIgnoreCase(rightType);
            if (byType != 0) return byType;
        }
        if (sidebarSort.startsWith("modified-") && !leftFolder && !rightFolder) {
            var leftId = ((SidebarItem.ConnectionItem) left).id();
            var rightId = ((SidebarItem.ConnectionItem) right).id();
            var leftUpdated = profileUpdatedAt.getOrDefault(leftId, java.time.Instant.EPOCH);
            var rightUpdated = profileUpdatedAt.getOrDefault(rightId, java.time.Instant.EPOCH);
            int byTime = leftUpdated.compareTo(rightUpdated);
            if (byTime != 0) return "modified-newest".equals(sidebarSort) ? -byTime : byTime;
        }
        String leftName = left instanceof SidebarItem item ? item.displayName() : "";
        String rightName = right instanceof SidebarItem item ? item.displayName() : "";
        return leftName.compareToIgnoreCase(rightName);
    }

    private boolean filter(DefaultMutableTreeNode node, String query) {
        Object value = node.getUserObject();
        if (value instanceof SidebarItem item && (item.displayName().toLowerCase(Locale.ROOT).contains(query)
                || item instanceof SidebarItem.ConnectionItem connection && connection.summary().toLowerCase(Locale.ROOT).contains(query))) return true;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            if (!filter((DefaultMutableTreeNode) node.getChildAt(i), query)) node.remove(i);
        }
        return node.getChildCount() > 0;
    }

    public void updateProjects(List<ProjectProfile> choices, String activeId) {
        var all = new ArrayList<ProjectProfile>();
        all.add(new ProjectProfile(null, text("project.label.default"), text("project.popup.defaultDescription")));
        all.addAll(choices);
        projectChoices = List.copyOf(all);
        activeProject = projectChoices.stream().filter(project -> Objects.equals(project.id(), activeId))
                .findFirst().orElse(projectChoices.getFirst());
        projects.setText(activeProject.name());
        projects.setIcon(new ProjectAvatar(activeProject.name(), activeProject.id(), 20));
    }

    private void showProjectPopup() {
        var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
        rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("project.menu.manage"),
                new WorkbenchIcon(WorkbenchIcon.Kind.PROJECT), "", null, actions.projects()));
        boolean others = false;
        for (var project : projectChoices) {
            if (Objects.equals(project, activeProject)) rows.add(projectRow(project, text("project.popup.current")));
        }
        for (var project : projectChoices) {
            if (Objects.equals(project, activeProject)) continue;
            rows.add(projectRow(project, others ? null : text("project.popup.other")));
            others = true;
        }
        var popup = new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 420, true, text("project.search.prompt"));
        popup.show(projects, 0, projects.getHeight() + 4);
    }

    private com.jlshell.ui.swing.idea.IdeaListPopup.Row projectRow(ProjectProfile project, String group) {
        return new com.jlshell.ui.swing.idea.IdeaListPopup.Row(project.name(), project.description(),
                new ProjectAvatar(project.name(), project.id(), 24), Objects.equals(project, activeProject) ? "✓" : "", group,
                true, List.of(), () -> actions.project().accept(project));
    }

    public void addTab(Object id, String title, JComponent view, Runnable close) {
        addTab(id, title, view, close, "");
    }

    public void addTab(Object id, String title, JComponent view, Runnable close, String connectionSummary) {
        tabConnectionSummaries.put(id, connectionSummary == null ? "" : connectionSummary);
        JPanel host = new IslandPanel(new BorderLayout(), "canvas");
        host.setOpaque(false);
        host.add(view);
        tabViews.put(id, view); tabHosts.put(id, host); viewTabs.put(host, id);
        tabCloseActions.put(id, close);
        if (view instanceof SwingSessionView session) {
            session.setToolPagesChanged(() -> {
                if (tabViews.get(viewTabs.get(tabs.getSelectedComponent())) == session) syncToolWindow();
            });
            session.setToolPageAction(index -> {
                if (!Objects.equals(viewTabs.get(tabs.getSelectedComponent()), id)) return;
                String key = session.toolKey(index);
                if (key != null) showDockedTool(key);
                syncToolWindow();
            });
        }
        tabs.addTab(title, host);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(title, new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL), SwingConstants.LEFT);
        header.add(label);
        tabDrags.get(tabs).install(header);
        tabDrags.get(tabs).install(label);
        MouseAdapter context = new MouseAdapter() {
            private boolean popupShown;
            public void mousePressed(MouseEvent event) {
                popupShown = false;
                if (isPopupRequest(event)) { popup(event); popupShown = true; }
            }
            public void mouseReleased(MouseEvent event) {
                if (!popupShown && isPopupRequest(event)) popup(event);
                popupShown = false;
            }
            private void popup(MouseEvent event) {
                JTabbedPane group = groupFor(host);
                if (group != null) showTabContext(id, group, SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), group));
            }
        };
        header.addMouseListener(context);
        label.addMouseListener(context);
        TabCloseButton closeButton = new TabCloseButton(modifiers -> {
            if ((modifiers & (java.awt.event.ActionEvent.ALT_MASK | java.awt.event.InputEvent.ALT_DOWN_MASK)) != 0) {
                JTabbedPane owner = groupFor(host);
                if (owner != null) {
                    List<Object> otherTabs = new ArrayList<>();
                    for (int i = 0; i < owner.getTabCount(); i++) {
                        Object other = viewTabs.get(owner.getComponentAt(i));
                        if (other != null && !Objects.equals(other, id)) otherTabs.add(other);
                    }
                    otherTabs.forEach(this::closeExistingTab);
                }
            } else {
                close.run();
            }
        });
        String closeShortcut = com.formdev.flatlaf.util.SystemInfo.isMacOS ? "⌘W" : "Ctrl+F4";
        String closeModifier = com.formdev.flatlaf.util.SystemInfo.isMacOS ? "Option" : "Alt";
        closeButton.setToolTipText(text("tab.close.tooltip").replace("{shortcut}", closeShortcut)
                .replace("{modifier}", closeModifier));
        closeButton.getAccessibleContext().setAccessibleName(text("tab.close"));
        header.add(closeButton);
        closeButton.addMouseListener(context);
        tabs.setTabComponentAt(tabs.indexOfComponent(host), header);
        syncToolWindow();
        updateSessionSelector();
        ((CardLayout) workspace.getLayout()).show(workspace, "tabs");
    }

    private static boolean isPopupRequest(MouseEvent event) {
        return event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event)
                || (event.getModifiersEx() & MouseEvent.BUTTON3_DOWN_MASK) != 0;
    }

    public void removeTab(Object id) {
        Component view = tabViews.remove(id);
        tabConnectionSummaries.remove(id);
        if (view == null) return;
        if (view == toolWindowOwner) releaseToolWindow();
        JPanel host = tabHosts.remove(id);
        JTabbedPane owner = groupFor(host);
        tabCloseActions.remove(id);
        viewTabs.remove(host);
        selectingTab = true;
        try { if (owner != null) owner.remove(host); } finally { selectingTab = false; }
        if (owner != null) pruneEmptyGroup(owner);
        if (!workspaceSplits.groups().contains(tabs)) tabs = workspaceSplits.groups().getFirst();
        activateGroup(tabs, true);
        syncToolWindow();
        if (view instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        updateSessionSelector();
        if (tabViews.isEmpty()) ((CardLayout) workspace.getLayout()).show(workspace, "welcome");
    }

    public void ensureFiles(Object id) {
        if(!(tabViews.get(id) instanceof SwingSessionView session)) throw new IllegalStateException("Session surface is not ready");
        session.ensureFiles();
    }
    public void selectTab(Object id) {
        Component host = tabHosts.get(id);
        JTabbedPane owner = groupFor(host);
        if (owner == null) return;
        tabs = owner;
        selectingTab = true;
        try { owner.setSelectedComponent(host); } finally { selectingTab = false; }
        syncToolWindow();
        updateSessionSelector();
    }

    private void moveTabLocally(JTabbedPane group, Component host, Integer target) {
        var order = new ArrayList<Object>();
        for (int i = 0; i < group.getTabCount(); i++) order.add(viewTabs.get(group.getComponentAt(i)));
        Object id = viewTabs.get(host);
        if (order.remove(id)) { order.add(Math.min(target, order.size()), id); reorderTabs(order); }
    }

    public void reorderTabs(List<Object> order) {
        selectingTab = true;
        try {
            for (JTabbedPane group : workspaceSplits.groups()) {
                Component selected = group.getSelectedComponent();
                int position = 0;
                for (Object id : order) {
                    Component host = tabHosts.get(id);
                    int index = group.indexOfComponent(host);
                    if (index < 0) continue;
                    if (index != position) {
                        String title = group.getTitleAt(index);
                        Component header = group.getTabComponentAt(index);
                        group.removeTabAt(index);
                        group.insertTab(title, null, host, null, position);
                        group.setTabComponentAt(position, header);
                    }
                    position++;
                }
                if (selected != null) group.setSelectedComponent(selected);
            }
        } finally { selectingTab = false; }
    }

    private void updateSessionSelector() {
        int index = tabs.getSelectedIndex();
        connectionName.setText(index < 0 ? text("workspace.noOpenSessions") : tabs.getTitleAt(index));
        connectionName.setIcon(index < 0 ? null : new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL));
        connectionName.setEnabled(!tabViews.isEmpty());
        if (status != null) status.refresh();
    }

    private void showSessionPopup() {
        var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
        Component selected = tabs.getSelectedComponent();
        for (JTabbedPane group : workspaceSplits.groups()) for (int i = 0; i < group.getTabCount(); i++) {
            Component host = group.getComponentAt(i);
            Object id = viewTabs.get(host);
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(group.getTitleAt(i),
                    new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL), host == selected ? "✓" : "", null,
                    () -> { selectTab(id); actions.selectTab().accept(id); }));
        }
        new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 240, true, text("workspace.openSessions"))
                .show(connectionName, 0, connectionName.getHeight() + 4);
    }

    private void showToolConnectionPopup() {
        if (!(rightToolWindowOwner instanceof SwingSessionView source)) return;
        source.selectToolPage(source.toolIndex(rightKey));
        var page = source.selectedToolPage();
        var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
        for (JTabbedPane group : workspaceSplits.groups()) for (int i = 0; i < group.getTabCount(); i++) {
            Object id = viewTabs.get(group.getComponentAt(i));
            if (!(tabViews.get(id) instanceof SwingSessionView session) || session.findToolPage(page) < 0) continue;
            rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(group.getTitleAt(i),
                    tabConnectionSummaries.getOrDefault(id, ""), new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL),
                    session == source ? "✓" : "", null, true, List.of(), () -> {
                // 弹窗显示期间插件页/会话可能被关闭，点击时重新核对快照。
                if (tabViews.get(id) != session || rightToolWindowOwner != source
                        || !Objects.equals(page, source.selectedToolPage())) return;
                int index = session.findToolPage(page);
                if (index < 0) return;
                session.selectToolPage(index);
                rightToolWindowVisible = true;
                rightKey = session.toolKey(index);
                selectTab(id);
                actions.selectTab().accept(id);
            }));
        }
        new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 360, true, text("workspace.toolConnections"))
                .show(toolConnection, 0, toolConnection.getHeight() + 4);
    }

    private void rebindHeader(Component header, JTabbedPane source, JTabbedPane destination) {
        if (header == null) return;
        var old = tabDrags.get(source);
        var next = tabDrags.get(destination);
        old.uninstall(header); next.install(header);
        if (header instanceof Container parent) for (Component child : parent.getComponents()) {
            if (child instanceof JLabel) { old.uninstall(child); next.install(child); }
        }
    }

    private void transferTab(Component host, JTabbedPane source, JTabbedPane target) {
        int index = source.indexOfComponent(host);
        if (index < 0 || source == target) return;
        String title = source.getTitleAt(index);
        Component header = source.getTabComponentAt(index);
        selectingTab = true;
        try {
            source.removeTabAt(index);
            target.addTab(title, host);
            target.setTabComponentAt(target.indexOfComponent(host), header);
            rebindHeader(header, source, target);
            target.setSelectedComponent(host);
        } finally { selectingTab = false; }
    }

    private void pruneEmptyGroup(JTabbedPane group) {
        if (group.getTabCount() == 0 && workspaceSplits.inSplit(group)) {
            workspaceSplits.removeEmpty(group);
            var drag = tabDrags.remove(group);
            if (drag != null) drag.close();
        }
    }

    private void closeEmptyGroup(JTabbedPane group) {
        if (group.getTabCount() != 0 || !workspaceSplits.inSplit(group)) return;
        boolean active = tabs == group;
        JTabbedPane next = workspaceSplits.siblings(group).stream()
                .filter(candidate -> candidate.getSelectedComponent() != null).findFirst()
                .orElseGet(() -> workspaceSplits.groups().stream()
                        .filter(candidate -> candidate != group && candidate.getSelectedComponent() != null)
                        .findFirst().orElseGet(() -> workspaceSplits.siblings(group).getFirst()));
        pruneEmptyGroup(group);
        if (active || !workspaceSplits.groups().contains(tabs)) activateGroup(next, true);
    }

    void splitTab(Object id, int orientation) {
        Component host = tabHosts.get(id);
        JTabbedPane source = groupFor(host);
        if (source == null) return;
        JTabbedPane destination = workspaceSplits.split(source, orientation);
        transferTab(host, source, destination);
        // 保留空来源组，可把另一已有会话拖入，不创建/复制 SSH 连接。
        activateGroup(destination, true);
        host.requestFocusInWindow();
    }

    void moveTabToOppositeGroup(Object id) {
        Component host = tabHosts.get(id);
        JTabbedPane source = groupFor(host);
        if (source == null) return;
        List<JTabbedPane> others = workspaceSplits.siblings(source);
        if (others.isEmpty()) return;
        JTabbedPane target = others.getFirst();
        transferTab(host, source, target);
        activateGroup(target, true);
        pruneEmptyGroup(source);
    }

    void unsplitTab(Object id, boolean all) {
        Component host = tabHosts.get(id);
        JTabbedPane target = groupFor(host);
        if (target == null) return;
        for (JTabbedPane removed : workspaceSplits.unsplit(target, all, (source, destination) -> {
            while (source.getTabCount() > 0) transferTab(source.getComponentAt(0), source, destination);
        })) {
            var drag = tabDrags.remove(removed);
            if (drag != null) drag.close();
        }
        selectTab(id);
        actions.selectTab().accept(id);
    }

    private void showTabContext(Object id, JTabbedPane group, Point point) {
        actions.tabContext().accept(id, specs -> SwingUtilities.invokeLater(() -> {
            if (!frame.isShowing() || !group.isShowing() || !tabHosts.containsKey(id) || groupFor(tabHosts.get(id)) != group) return;
            var rows = tabContextRows(id, specs);
            var popup = new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 220, false, "",
                    com.jlshell.ui.swing.idea.IdeaListPopup.Style.CONTEXT);
            KeyStroke closeKey = KeyStroke.getKeyStroke(com.formdev.flatlaf.util.SystemInfo.isMacOS ? "meta W" : "ctrl F4");
            if (!shortcuts.containsKey(closeKey)) popup.bindShortcut(closeKey, rows.getFirst().action());
            popup.show(group, point.x, point.y);
        }));
    }

    List<com.jlshell.ui.swing.idea.IdeaListPopup.Row> tabContextRows(Object id, List<SwingMenuBridge.Item> specs) {
        Component host = tabHosts.get(id);
        JTabbedPane group = groupFor(host);
        if (group == null) return List.of();
        int current = group.indexOfComponent(host);
        var ids = new ArrayList<Object>();
        for (int i = 0; i < group.getTabCount(); i++) ids.add(viewTabs.get(group.getComponentAt(i)));
        var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
        KeyStroke closeKey = KeyStroke.getKeyStroke(com.formdev.flatlaf.util.SystemInfo.isMacOS ? "meta W" : "ctrl F4");
        rows.add(tabMenuRow("tab.close", null, "", true, () -> tabCloseActions.getOrDefault(id, () -> {}).run(),
                shortcuts.containsKey(closeKey) ? "" : com.formdev.flatlaf.util.SystemInfo.isMacOS ? "⌘W" : "Ctrl+F4"));
        rows.add(tabMenuRow("tab.closeOthersInGroup", null, null, ids.size() > 1,
                () -> ids.stream().filter(other -> !Objects.equals(other, id)).forEach(this::closeExistingTab), ""));
        rows.add(tabMenuRow("tab.closeAllInGroup", null, null, !ids.isEmpty(), () -> ids.forEach(this::closeExistingTab), ""));
        rows.add(tabMenuRow("tab.closeLeft", null, null, current > 0,
                () -> ids.subList(0, current).forEach(this::closeExistingTab), ""));
        rows.add(tabMenuRow("tab.closeRight", null, null, current < ids.size() - 1,
                () -> ids.subList(current + 1, ids.size()).forEach(this::closeExistingTab), ""));
        rows.add(tabMenuRow("tab.splitRight", WorkbenchIcon.Kind.SPLIT_RIGHT, "", true,
                () -> splitTab(id, JSplitPane.HORIZONTAL_SPLIT), ""));
        rows.add(tabMenuRow("tab.splitDown", WorkbenchIcon.Kind.SPLIT_DOWN, null, true,
                () -> splitTab(id, JSplitPane.VERTICAL_SPLIT), ""));
        boolean split = workspaceSplits.inSplit(group);
        rows.add(tabMenuRow("tab.moveToGroup", null, null, split, () -> moveTabToOppositeGroup(id), ""));
        rows.add(tabMenuRow("tab.changeSplitOrientation", null, null, split,
                () -> workspaceSplits.changeOrientation(group), ""));
        rows.add(tabMenuRow("tab.unsplit", null, null, split, () -> unsplitTab(id, false), ""));
        if (workspaceSplits.groups().size() > 2) rows.add(tabMenuRow("tab.unsplitAll", null, null, true,
                () -> unsplitTab(id, true), ""));
        specs.stream().filter(item -> Objects.equals(item.text(), text("tab.duplicateSession"))).findFirst().ifPresent(item ->
                rows.add(tabMenuRow("tab.duplicateSession", WorkbenchIcon.Kind.TERMINAL, "", !item.disabled(), item.action(), item.shortcut())));
        return rows;
    }

    private void closeExistingTab(Object id) { tabCloseActions.getOrDefault(id, () -> {}).run(); }

    private com.jlshell.ui.swing.idea.IdeaListPopup.Row tabMenuRow(String key, WorkbenchIcon.Kind icon, String group,
                                                                 boolean enabled, Runnable action, String shortcut) {
        return new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text(key), null,
                icon == null ? null : new WorkbenchIcon(icon), shortcut, group, enabled, List.of(), action);
    }

    public void setSidebarVisible(boolean visible) {
        if (sidebarVisible == visible) return;
        sidebarVisible = visible;
        JSplitPane activeSplit = rightToolMaximized ? maximizedSplit : split;
        if (!visible) { sidebarWidth = activeSplit.getDividerLocation(); sidebar.setMinimumSize(new Dimension(0, 0)); }
        else sidebar.setMinimumSize(new Dimension(240, 0));
        sidebar.setVisible(visible);
        activeSplit.setDividerSize(visible ? 4 : 0);
        projectStripe.setSelected(visible);
        activeSplit.setDividerLocation(visible ? sidebarWidth : 0);
    }

    public void setStatus(String message) { status.status(message); }
    public void setTitle(String title) { frame.setTitle(title); }
    public void openPluginPanel() {
        Object id = viewTabs.get(tabs.getSelectedComponent());
        if (!(tabViews.get(id) instanceof SwingSessionView session) || session.toolPageOptions().size() < 2) return;
        session.selectToolPage(2);
        revealSelectedToolPage();
    }
    public void revealSelectedToolPage() {
        Component view = tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if (view instanceof SwingSessionView session) showDockedTool(session.toolKey(session.selectedPageIndex()));
    }

    private void showDockedTool(String key) {
        if (key == null) return;
        Component active = tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if (active instanceof SwingSessionView session) closedBottomTools.computeIfAbsent(session, ignored -> new java.util.HashSet<>()).remove(key);
        ToolDockState.Anchor anchor = toolLayout.anchor(key);
        if (rightToolMaximized && anchor != ToolDockState.Anchor.RIGHT) rightToolMaximized = false;
        switch (anchor) {
            case RIGHT -> { rightKey = key; rightToolWindowVisible = true; }
            case BOTTOM_LEFT -> { bottomLeftKey = key; toolWindowVisible = true; }
            case BOTTOM_RIGHT -> { bottomRightKey = key; bottomRightWindowVisible = true; }
        }
        syncToolWindow(); persistToolLayout();
    }

    public void restoreDefaultLayout() {
        closedBottomTools.clear();
        toolLayout.positions.clear(); toolLayout.sidebar = 280; toolLayout.height = 112; toolLayout.width = 380; toolLayout.bottomPercent = 50;
        sidebarWidth = 280; dockHeight = 112;
        bottomLeftKey = "monitor"; bottomRightKey = null; rightKey = "files";
        rightToolWindowVisible = false; toolWindowVisible = true; bottomRightWindowVisible = false;
        rightToolMaximized = false;
        if (!sidebarVisible) setSidebarVisible(true);
        split.setDividerLocation(280);
        syncToolWindow(); persistToolLayout();
    }

    static Dimension restoredWindowSize(String stored, Dimension screen) {
        int width = Math.min(1480, (int)(screen.width * .9));
        int height = Math.min(920, (int)(screen.height * .9));
        if (stored != null && stored.matches("[0-9]{1,5}x[0-9]{1,5}")) {
            String[] values = stored.split("x");
            width = Integer.parseInt(values[0]); height = Integer.parseInt(values[1]);
            if (width < 640 || height < 480) { width = Math.min(1480, (int)(screen.width * .9)); height = Math.min(920, (int)(screen.height * .9)); }
        }
        return new Dimension(Math.max(640, Math.min(width, screen.width)), Math.max(480, Math.min(height, screen.height)));
    }

    private void persistWindowSize() {
        if (windowSizeReady && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0
                && !frame.getSize().equals(lastSavedWindowSize)) {
            lastSavedWindowSize = frame.getSize();
            actions.setSetting().accept(WINDOW_SIZE_SETTING, frame.getWidth() + "x" + frame.getHeight());
        }
    }

    private String snapshotToolLayout() {
        toolLayout.leftVisible = toolWindowVisible; toolLayout.bottomRightVisible = bottomRightWindowVisible;
        toolLayout.rightVisible = rightToolWindowVisible;
        toolLayout.leftSelected = bottomLeftKey; toolLayout.bottomRightSelected = bottomRightKey; toolLayout.rightSelected = rightKey;
        return toolLayout.encode();
    }

    private void persistToolLayout() {
        actions.setSetting().accept(ToolDockState.SETTING, snapshotToolLayout());
    }

    /** EDT snapshot for shutdown: stop deferred writes before the settings database closes. */
    public Map<String, String> captureLayoutForShutdown() {
        if (shutdownLayoutSnapshot != null) return shutdownLayoutSnapshot;
        saveLayoutTimer.stop(); saveWindowSizeTimer.stop();
        Map<String, String> result = new LinkedHashMap<>();
        result.put(ToolDockState.SETTING, snapshotToolLayout());
        if (windowSizeReady && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == 0
                && !frame.getSize().equals(lastSavedWindowSize)) {
            lastSavedWindowSize = frame.getSize();
            result.put(WINDOW_SIZE_SETTING, frame.getWidth() + "x" + frame.getHeight());
        }
        shutdownLayoutSnapshot = Map.copyOf(result);
        return shutdownLayoutSnapshot;
    }

    public void setMenuBar(JMenuBar menu) {
        appMenuBar = menu;
        mainMenus = Arrays.stream(menu.getComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast).toList();
        headerMenus.removeAll();
        if (com.formdev.flatlaf.util.SystemInfo.isMacOS) {
            menuButton.setVisible(false);
            MacMenuBridge.install(menu);
        }
        // 对照 IDEA IdeMenuHelper：Windows/Linux 在同一标题区横向展示菜单。
        if (!com.formdev.flatlaf.util.SystemInfo.isMacOS) {
            for (JMenu source : mainMenus) {
                JButton button = new HeaderButton(source.getText());
                button.setName("header.menu." + source.getText());
                button.putClientProperty("JButton.buttonType", "toolBarButton");
                button.setFocusable(false);
                button.setMargin(new Insets(4, 7, 4, 7));
                int mnemonic = source.getText().equals(text("menu.file")) ? 'F'
                        : source.getText().equals(text("menu.connections")) ? 'C'
                        : source.getText().equals(text("menu.account")) ? 'A'
                        : source.getText().equals(text("menu.view")) ? 'V'
                        : source.getText().equals(text("workspace.plugins")) ? 'P'
                        : source.getText().equals(text("menu.settings")) ? 'S'
                        : source.getText().equals(text("menu.help")) ? 'H' : 0;
                if (mnemonic != 0) button.setMnemonic(mnemonic);
                button.addActionListener(event -> requestMenus(specs -> specs.stream()
                        .filter(item -> item.text().equals(source.getText())).findFirst().ifPresent(item ->
                                new com.jlshell.ui.swing.idea.IdeaListPopup(menuRows(item.children()), 220, false, "")
                                        .show(button, 0, button.getHeight() + 4))));
                headerMenus.add(button);
            }
            menuButton.setVisible(false);
        }
        headerMenus.revalidate();
        headerMenus.repaint();
        SwingUtilities.invokeLater(this::updateHeaderMenuVisibility);
    }

    private void updateHeaderMenuVisibility() {
        if (com.formdev.flatlaf.util.SystemInfo.isMacOS || mainMenus.isEmpty()) return;
        int menuWidth = Arrays.stream(headerMenus.getComponents()).mapToInt(component -> component.getPreferredSize().width).sum();
        Component right = ((BorderLayout) mainToolbar.getLayout()).getLayoutComponent(BorderLayout.EAST);
        boolean fits = mainToolbar.getWidth() >= menuWidth + projects.getPreferredSize().width + right.getPreferredSize().width + 70;
        if (headerMenus.isVisible() != fits) {
            headerMenus.setVisible(fits);
            menuButton.setVisible(!fits);
            mainToolbar.revalidate();
        }
    }
    public void setShortcuts(Map<KeyStroke, Runnable> bindings) { shortcuts.clear(); shortcuts.putAll(bindings); }
    public void setPreferenceShortcut(String displayText) { preferenceShortcut = displayText == null ? "" : displayText; }
    public void setEnabled(boolean enabled) {
        boolean allowed = enabled && !Boolean.TRUE.equals(frame.getRootPane().getClientProperty("jlshell.settingsModal"));
        if (frame.isEnabled() != allowed) frame.setEnabled(allowed);
    }
    public void show() {
        boolean previous = layingOutTools; layingOutTools = true;
        try {
            frame.setVisible(true);
            split.setDividerLocation(sidebarVisible ? sidebarWidth : 0);
            if (vertical.getBottomComponent() != null) {
                vertical.setDividerLocation(Math.max(150, vertical.getHeight() - dockHeight - 4));
                vertical.doLayout();
                if (vertical.getBottomComponent() == bottomSplit) bottomSplit.setDividerLocation(toolLayout.bottomPercent / 100.0);
            }
        } finally { layingOutTools = previous; }
        SwingUtilities.invokeLater(() -> windowSizeReady = true);
        frame.toFront(); tree.requestFocusInWindow();
    }
    public void hide() { frame.setVisible(false); }
    public JFrame frame() { return frame; }

    public void applyTheme(AppTheme theme, String accent, String family, int fontSize) {
        if (SwingTheme.isCurrent(theme, accent, family, fontSize)) return;
        SwingTheme.install(theme, accent, family, fontSize);
        SwingTheme.update(frame);
        root.setBackground(SwingTheme.color("frame"));
        status.applyTheme();
        connectionName.setForeground(SwingTheme.color("secondary"));
        for (JMenu menu : mainMenus) SwingTheme.update(menu);
        dockTitle.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        dockTitle.setForeground(SwingTheme.color("secondary"));
        updateTreeRowHeight();
        updateHeaderMenuVisibility();
        frame.revalidate(); frame.repaint();
    }

    private void updateTreeRowHeight() {
        // 按实际字体度量行高，避免系统字体回退或用户放大字体后相邻行文字相互覆盖。
        tree.setRowHeight(Math.max(24, tree.getFontMetrics(tree.getFont()).getHeight() + 8));
    }

    private void toggleSearch() {
        if (!sidebarVisible) actions.toggleSidebar().run();
        boolean show = !searchBar.isVisible();
        searchBar.setVisible(show);
        if (show) search.requestFocusInWindow();
        else { search.setText(""); tree.requestFocusInWindow(); }
        sidebar.revalidate();
    }

    public void setMenuProvider(java.util.function.Consumer<java.util.function.Consumer<List<SwingMenuBridge.Item>>> provider,
                                Map<String, Runnable> settingsActions) {
        this.menuProvider = provider;
        this.settingsActions = Map.copyOf(settingsActions);
    }

    private void showMainMenu() {
        requestMenus(specs -> new com.jlshell.ui.swing.idea.IdeaListPopup(menuRows(specs, true), 144, false, "")
                .show(menuButton, 0, menuButton.getHeight() + 4));
    }

    private void requestMenus(java.util.function.Consumer<List<SwingMenuBridge.Item>> reply) {
        if (menuProvider != null) menuProvider.accept(items -> SwingUtilities.invokeLater(() -> {
            if (frame.isShowing()) reply.accept(items);
        }));
    }

    private void showSettingsMenu() {
        requestMenus(menus -> {
            var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
            String shortcut = preferenceShortcut;
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("project.menu.manage"), new WorkbenchIcon(WorkbenchIcon.Kind.PROJECT), "", null, actions.projects()));
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.preferences"), new WorkbenchIcon(WorkbenchIcon.Kind.SETTINGS), shortcut, null, actions.preferences()));
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("settings.quick.plugins"), null, "", null, settingsActions.get("plugins")));
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("settings.quick.account"), null, "", "", settingsActions.get("account")));
            var view = menus.stream().filter(menu -> text("menu.view").equals(menu.text())).findFirst();
            List<SwingMenuBridge.Item> viewItems = view.map(SwingMenuBridge.Item::children).orElse(List.of());
            List<SwingMenuBridge.Item> theme = viewItems.stream().filter(item -> text("theme.dark").equals(item.text()) || text("theme.light").equals(item.text())).toList();
            rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text("settings.quick.theme"), null, null, "", "", true, menuRows(theme), null));
            rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("settings.quick.shortcuts"), null, "", null, settingsActions.get("shortcuts")));
            rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(text("settings.quick.view"), null, null, "", null, true,
                    menuRows(viewItems.stream().filter(item -> !theme.contains(item)).toList()), null));
            for (var menu : menus) for (var item : menu.children()) {
                if (text("menu.help.about").equals(item.text()) || text("menu.help.checkUpdates").equals(item.text())) {
                    var converted = menuRows(List.of(item)).getFirst();
                    rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(converted.title(), null, converted.icon(), "", "", converted.enabled(), converted.children(), converted.action()));
                }
            }
            var popup = new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 260, false, "");
            popup.show(settingsButton, settingsButton.getWidth() - popup.getPreferredSize().width, settingsButton.getHeight() + 4);
        });
    }

    private List<com.jlshell.ui.swing.idea.IdeaListPopup.Row> menuRows(List<SwingMenuBridge.Item> specs) {
        return menuRows(specs, false);
    }

    private List<com.jlshell.ui.swing.idea.IdeaListPopup.Row> menuRows(List<SwingMenuBridge.Item> specs, boolean topLevel) {
        var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
        String group = null;
        for (var item : specs) {
            if (item.kind() == 1) { group = ""; continue; }
            Icon icon = item.text().equals(text("action.preferences")) ? new WorkbenchIcon(WorkbenchIcon.Kind.SETTINGS)
                    : item.text().equals(text("action.newConnection")) ? new WorkbenchIcon(WorkbenchIcon.Kind.ADD) : null;
            if (topLevel) {
                WorkbenchIcon.Kind kind = item.text().equals(text("menu.file")) ? WorkbenchIcon.Kind.FOLDER
                        : item.text().equals(text("menu.connections")) ? WorkbenchIcon.Kind.SERVER
                        : item.text().equals(text("menu.account")) ? WorkbenchIcon.Kind.USER
                        : item.text().equals(text("menu.view")) ? WorkbenchIcon.Kind.LAYOUT
                        : item.text().equals(text("workspace.plugins")) ? WorkbenchIcon.Kind.PLUGIN
                        : item.text().equals(text("menu.settings")) ? WorkbenchIcon.Kind.SETTINGS
                        : item.text().equals(text("menu.help")) ? WorkbenchIcon.Kind.HELP : null;
                if (kind != null) icon = new WorkbenchIcon(kind);
            }
            rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(item.text(), null, icon,
                    item.checked() ? "✓" : item.shortcut(), group, !item.disabled(), menuRows(item.children()), item.action()));
            group = null;
        }
        return rows;
    }

    private void buildToolDock() {
        vertical.setName("workspace.verticalSplit");
        toolDock.setName("monitor.dock");
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        dockTitle.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        dockTitle.setForeground(SwingTheme.color("secondary"));
        dockTitle.setName("monitor.connectionLabel");
        toolNavigationHost.setOpaque(false);
        header.add(toolNavigationHost, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 1)); tools.setOpaque(false);
        tools.add(dockTitle);
        JButton hide = button(WorkbenchIcon.Kind.HIDE, "workspace.hideTools", this::toggleToolWindow);
        hide.setName("workspace.hideBottomLeft"); tools.add(hide);
        header.add(tools, BorderLayout.EAST);
        installToolDrag(header, () -> bottomLeftKey);
        toolDock.add(header, BorderLayout.NORTH);
        dockBody.setOpaque(false);
        dockBody.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        toolDock.add(dockBody);
        toolDock.setMinimumSize(new Dimension(0, MIN_MONITOR_DOCK_HEIGHT));
    }

    private void buildRightToolDock() {
        rightDockContainer.setOpaque(false);
        rightDockContainer.setVisible(false);
        rightDockContainer.setPreferredSize(new Dimension(36, 0));

        rightRail.setOpaque(false); rightRail.setName("workspace.rightStripe");
        rightRail.setPreferredSize(new Dimension(36, 0));
        rightToolNavigationHost.setName("workspace.rightTopStripe");
        rightToolNavigationHost.setLayout(new BoxLayout(rightToolNavigationHost, BoxLayout.Y_AXIS));
        rightToolNavigationHost.setOpaque(false);
        rightToolNavigationHost.setBorder(BorderFactory.createEmptyBorder(4, 3, 0, 3));
        rightBottomStripe.setName("workspace.bottomRightStripe");
        rightBottomStripe.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        rightRail.add(rightToolNavigationHost, BorderLayout.NORTH);
        rightRail.add(rightBottomStripe, BorderLayout.SOUTH);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        rightDockTitle.setFont(rightDockTitle.getFont().deriveFont(Font.BOLD));
        rightDockTitle.setName("workspace.rightToolTitle");
        JPanel labels = new JPanel();
        labels.setLayout(new BoxLayout(labels, BoxLayout.X_AXIS));
        labels.setOpaque(false);
        labels.add(rightDockTitle);
        labels.add(Box.createHorizontalStrut(10));
        toolConnection.setName("workspace.toolConnection");
        toolConnection.putClientProperty("html.disable", true);
        toolConnection.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 24));
        toolConnection.setHorizontalAlignment(SwingConstants.LEFT);
        toolConnection.setIcon(new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL));
        toolConnection.setFocusable(false);
        toolConnection.setVisible(false);
        toolConnection.setSelected(true);
        toolConnection.setMinimumSize(new Dimension(64, 26));
        toolConnection.setMaximumSize(new Dimension(340, Integer.MAX_VALUE));
        toolConnection.addActionListener(event -> showToolConnectionPopup());
        labels.add(toolConnection);
        labels.add(Box.createHorizontalGlue());
        header.add(labels, BorderLayout.CENTER);
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        headerActions.setOpaque(false);
        JButton dockBottom = button(WorkbenchIcon.Kind.DOCK, "workspace.moveToolBottom", this::moveToolBottom);
        dockBottom.setName("workspace.moveToolBottom");
        headerActions.add(dockBottom);
        maximizeToolButton = button(WorkbenchIcon.Kind.MAXIMIZE, "workspace.maximizeTool", this::toggleMaximizedTool);
        maximizeToolButton.setName("workspace.maximizeTool");
        headerActions.add(maximizeToolButton);
        headerActions.add(button(WorkbenchIcon.Kind.HIDE, "workspace.hideTools", this::toggleRightToolWindow));
        header.add(headerActions, BorderLayout.EAST);
        rightToolDock.add(header, BorderLayout.NORTH);
        rightDockBody.setOpaque(false);
        rightDockBody.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        rightToolDock.add(rightDockBody, BorderLayout.CENTER);
        rightToolDock.setName("right.toolWindow");
        rightToolDock.setMinimumSize(new Dimension(180, 0));
        rightToolDock.setVisible(false);
        rightDockContainer.add(rightToolDock, BorderLayout.CENTER);
        installToolDrag(rightDockTitle, () -> rightKey);
        installToolDrag(header, () -> rightKey);
        bottomRightDock.setName("workspace.bottomRightDock");
        JPanel otherHeader = new JPanel(new BorderLayout()); otherHeader.setOpaque(false);
        otherHeader.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        bottomRightNavigation.setOpaque(false); otherHeader.add(bottomRightNavigation, BorderLayout.CENTER);
        JPanel otherActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 1)); otherActions.setOpaque(false);
        bottomRightConnection.setForeground(SwingTheme.color("secondary"));
        bottomRightConnection.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        otherActions.add(bottomRightConnection);
        JButton hide = button(WorkbenchIcon.Kind.HIDE, "workspace.hideTools", () -> hideDock(ToolDockState.Anchor.BOTTOM_RIGHT));
        hide.setName("workspace.hideBottomRight"); otherActions.add(hide); otherHeader.add(otherActions, BorderLayout.EAST);
        installToolDrag(otherHeader, () -> bottomRightKey);
        bottomRightDock.add(otherHeader, BorderLayout.NORTH);
        bottomRightBody.setOpaque(false); bottomRightDock.add(bottomRightBody);
        bottomSplit.setName("workspace.bottomSplit");
        bottomSplit.setBorder(BorderFactory.createEmptyBorder()); bottomSplit.setOpaque(false);
        bottomSplit.setResizeWeight(.5); bottomSplit.setContinuousLayout(true);
        bottomSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!layingOutTools && bottomSplit.getWidth() > 0) {
                toolLayout.bottomPercent = Math.max(15, Math.min(85, bottomSplit.getDividerLocation() * 100 / bottomSplit.getWidth()));
                saveLayoutTimer.restart();
            }
        });
        vertical.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!layingOutTools && vertical.getBottomComponent() != null && vertical.getHeight() > 0) {
                toolLayout.height = Math.max(96, vertical.getHeight() - vertical.getDividerLocation() - 4);
                dockHeight = toolLayout.height; saveLayoutTimer.restart();
            }
        });
        mainSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!layingOutTools && rightToolWindowVisible && !rightToolMaximized && mainSplit.getWidth() > 0) {
                toolLayout.width = Math.max(180, mainSplit.getWidth() - mainSplit.getDividerLocation() - 4);
                saveLayoutTimer.restart();
            }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!layingOutTools && sidebarVisible && split.getDividerLocation() > 0) {
                toolLayout.sidebar = split.getDividerLocation(); saveLayoutTimer.restart();
            }
        });
    }

    private void moveToolBottom() { moveTool(rightKey, ToolDockState.Anchor.BOTTOM_LEFT); }

    void moveTool(String key, ToolDockState.Anchor anchor) {
        if (key == null) return;
        if (rightToolMaximized) { rightToolMaximized = false; updateRightDockWidth(true); }
        toolLayout.positions.put(key, anchor);
        if (key.equals(rightKey)) rightToolWindowVisible = false;
        if (key.equals(bottomLeftKey)) bottomLeftKey = null;
        if (key.equals(bottomRightKey)) bottomRightKey = null;
        showDockedTool(key); persistToolLayout();
    }

    private Rectangle dockBounds(Component component) {
        return component != null && component.isShowing() ? SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),workspaceCards) : null;
    }

    private ToolDropGeometry dropGeometry() {
        Component bottom=vertical.getBottomComponent();
        return new ToolDropGeometry(new Rectangle(0,0,workspaceCards.getWidth(),workspaceCards.getHeight()),
                dockBounds(bottom),bottom==bottomSplit?dockBounds(toolDock):null,bottom==bottomSplit?dockBounds(bottomRightDock):null,
                dockBounds(rightToolDock),toolLayout.width);
    }

    private JPanel stripeHost(ToolDockState.Anchor anchor) {
        return switch(anchor){case RIGHT->rightToolNavigationHost;case BOTTOM_LEFT->leftBottomStripe;case BOTTOM_RIGHT->rightBottomStripe;};
    }

    private int updateStripeDrop(ToolDockState.Anchor anchor,Point screenPoint) {
        JPanel host=stripeHost(anchor);
        Point point=new Point(screenPoint);SwingUtilities.convertPointFromScreen(point,host);
        int index=0;
        for(Component child:host.getComponents())if(child instanceof JButton button
                && button.getClientProperty("jlshell.toolKey") instanceof String key && !key.equals(draggingToolKey)) {
            if(point.y>=button.getY()+button.getHeight()/2)index++;
        }
        if(host!=dropStripeHost || index!=dropStripeIndex || stripeDropSlot.getParent()!=host) {
            clearStripeDrop();dropStripeHost=host;dropStripeIndex=index;
            int position=0,seen=0;
            while(position<host.getComponentCount()){
                Component child=host.getComponent(position);
                if(child instanceof JButton button && button.getClientProperty("jlshell.toolKey") instanceof String key
                        && !key.equals(draggingToolKey)) {if(seen++==index)break;}
                position++;
            }
            host.add(stripeDropSlot,position);host.revalidate();frame.validate();
            rightRail.doLayout();leftRail.doLayout();host.doLayout();host.repaint();
        }
        return index;
    }

    private void clearStripeDrop() {
        if(stripeDropSlot.getParent()!=null){Container parent=stripeDropSlot.getParent();parent.remove(stripeDropSlot);parent.revalidate();parent.repaint();}
        dropStripeHost=null;dropStripeIndex=-1;
    }

    private void commitToolDrop(String key,ToolDropGeometry.Target target,int index) {
        Component current=tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if(!(current instanceof SwingSessionView currentSession)||!stripeKeys(currentSession,toolLayout.anchor(key)).contains(key))return;
        ToolDockState.Anchor anchor=target.anchor();
        if (currentSession.toolIndex(key) >= 0) moveTool(key,anchor);
        else toolLayout.positions.put(key, anchor);
        // The previewed insertion index becomes the saved order, including moves within one stripe.
        List<String> order=new ArrayList<>(toolLayout.positions.keySet());order.remove(key);
        Component active=tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if(active instanceof SwingSessionView session){
            for(var location:ToolDockState.Anchor.values())for(String peer:stripeKeys(session,location))if(!peer.equals(key)&&!order.contains(peer))order.add(peer);
            List<String> peers=new ArrayList<>(stripeKeys(session,anchor));peers.remove(key);
            int at=index<peers.size()?order.indexOf(peers.get(index)):order.size();
            if(at<0)at=order.size();order.add(at,key);
            var positions=new LinkedHashMap<String,ToolDockState.Anchor>();
            for(String tool:order)positions.put(tool,toolLayout.anchor(tool));
            toolLayout.positions.clear();toolLayout.positions.putAll(positions);
        }
        syncToolWindow();persistToolLayout();
    }

    private void installToolDrag(JComponent component,java.util.function.Supplier<String> key) {
        if(Boolean.TRUE.equals(component.getClientProperty("jlshell.toolDrag")))return;
        component.putClientProperty("jlshell.toolDrag",true);
        MouseAdapter drag=new MouseAdapter(){
            Point start;boolean dragging,stripeDrop,tabDrop;int generation,index;ToolDropGeometry.Target target; JPanel tabHost;
            public void mousePressed(MouseEvent event){if(SwingUtilities.isLeftMouseButton(event)){
                if(toolDragSource!=null)cancelToolDrag();
                start=event.getLocationOnScreen();generation=dockDragGeneration;
                dragging=false;stripeDrop=false;tabDrop=false;target=null;tabHost=null;
            }}
            public void mouseDragged(MouseEvent event){
                if(generation!=dockDragGeneration||start==null||key.get()==null||start.distance(event.getLocationOnScreen())<8)return;
                if(!dragging){
                    toolDragSource=component;toolDragCursor=component.getCursor();draggingToolKey=key.get();
                    Toolkit.getDefaultToolkit().addAWTEventListener(toolDragEventListener,
                            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
                    component.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    for(Component child:stripeHost(toolLayout.anchor(draggingToolKey)).getComponents())if(child instanceof AbstractButton button
                            && draggingToolKey.equals(button.getClientProperty("jlshell.toolKey"))){
                        draggedStripeButton=button;button.putClientProperty("jlshell.draggingTool",true);button.repaint();break;
                    }
                }
                dragging=true;
                if(component instanceof AbstractButton button){button.getModel().setArmed(false);button.getModel().setPressed(false);}
                Point screen=event.getLocationOnScreen(),point=new Point(screen);SwingUtilities.convertPointFromScreen(point,workspaceCards);
                tabDrop=false;
                if(component instanceof AbstractButton && component.getParent() instanceof JPanel host
                        && (host==toolNavigationHost || host==bottomRightNavigation)) {
                    Point local=new Point(screen);SwingUtilities.convertPointFromScreen(local,host);
                    if(host.contains(local)) {
                        tabDrop=true;tabHost=host;index=host.getComponentCount();
                        for(int i=0;i<host.getComponentCount();i++) {
                            Component child=host.getComponent(i);
                            if(local.x<child.getX()+child.getWidth()/2){index=i;break;}
                        }
                        for(int i=0;i<host.getComponentCount();i++) if(host.getComponent(i) instanceof JComponent child) {
                            child.putClientProperty("jlshell.tabInsertion",i==index ? "before" : index==host.getComponentCount()&&i==index-1 ? "after" : null);child.repaint();
                        }
                        target=null;clearStripeDrop();dockPreview.setVisible(false);
                        toolDragFeedback.show(screen,((AbstractButton)component).getText(),toolIcon(draggingToolKey),"",false);
                        return;
                    }
                }
                clearTabInsertion(tabHost);tabHost=null;
                Point right=new Point(screen);SwingUtilities.convertPointFromScreen(right,rightRail);
                Point left=new Point(screen);SwingUtilities.convertPointFromScreen(left,leftRail);
                ToolDockState.Anchor stripe=rightRail.contains(right)?(right.y>rightRail.getHeight()/2?ToolDockState.Anchor.BOTTOM_RIGHT:ToolDockState.Anchor.RIGHT)
                        :leftRail.contains(left)&&left.y>leftRail.getHeight()/2?ToolDockState.Anchor.BOTTOM_LEFT:null;
                stripeDrop=stripe!=null;
                target=dropGeometry().resolve(point,stripe);
                if(target==null)clearStripeDrop();else index=updateStripeDrop(target.anchor(),screen);
                Component active=tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
                String title=active instanceof SwingSessionView session?session.toolTitle(draggingToolKey):"";
                String hint=text(target==null?"workspace.dragCancel":target.anchor()==ToolDockState.Anchor.RIGHT?"workspace.moveToolRight"
                        :target.anchor()==ToolDockState.Anchor.BOTTOM_LEFT?"workspace.moveToolBottomLeft":"workspace.moveToolBottomRight");
                toolDragFeedback.show(screen,title,toolIcon(draggingToolKey),hint,target!=null&&target.anchor()==ToolDockState.Anchor.BOTTOM_LEFT);
                dockPreview.setVisible(target!=null);
                if(target!=null){dockPreview.setBounds(SwingUtilities.convertRectangle(workspaceCards,target.bounds(),frame.getLayeredPane()));
                    frame.getLayeredPane().add(dockPreview,JLayeredPane.DRAG_LAYER);dockPreview.repaint();}
            }
            public void mouseReleased(MouseEvent event){
                String tool=draggingToolKey;clearTabInsertion(tabHost);endToolDrag();
                if(generation==dockDragGeneration&&dragging&&tabDrop&&tabHost!=null) reorderBottomTab(tool,tabHost,index);
                else if(generation==dockDragGeneration&&dragging&&target!=null&&(stripeDrop||target.anchor()!=toolLayout.anchor(tool)))commitToolDrop(tool,target,index);
                start=null;dragging=false;target=null;tabHost=null;tabDrop=false;
            }
        };
        component.addMouseListener(drag);component.addMouseMotionListener(drag);
    }

    private void clearTabInsertion(JPanel host) {
        if(host==null)return;
        for(Component component:host.getComponents()) if(component instanceof JComponent child){child.putClientProperty("jlshell.tabInsertion",null);child.repaint();}
    }

    private void reorderBottomTab(String key,JPanel host,int index) {
        Component active=tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if(!(active instanceof SwingSessionView session))return;
        ToolDockState.Anchor anchor=host==toolNavigationHost?ToolDockState.Anchor.BOTTOM_LEFT:ToolDockState.Anchor.BOTTOM_RIGHT;
        List<String> peers=new ArrayList<>(tools(session,anchor));
        int old=peers.indexOf(key);if(old<0)return;
        peers.remove(old);if(index>old)index--;
        peers.add(Math.max(0,Math.min(index,peers.size())),key);
        List<String> order=new ArrayList<>(toolLayout.positions.keySet());
        for(var location:ToolDockState.Anchor.values())for(String peer:tools(session,location))if(!order.contains(peer))order.add(peer);
        int next=0;for(int i=0;i<order.size();i++)if(peers.contains(order.get(i)))order.set(i,peers.get(next++));
        var positions=new LinkedHashMap<String,ToolDockState.Anchor>();
        for(String tool:order)positions.put(tool,toolLayout.anchor(tool));
        toolLayout.positions.clear();toolLayout.positions.putAll(positions);
        syncToolWindow();persistToolLayout();
    }

    void cancelToolDrag() { dockDragGeneration++; endToolDrag(); }

    private boolean isOwnedByWorkbench(Window window) {
        for (Window current = window; current != null; current = current.getOwner()) {
            if (current == frame) return true;
        }
        return false;
    }

    private void forwardToolDragEvent(MouseEvent event) {
        JComponent source = toolDragSource;
        if (source == null) return;
        if (!source.isShowing()) { cancelToolDrag(); return; }
        Point point = event.getLocationOnScreen();
        SwingUtilities.convertPointFromScreen(point, source);
        source.dispatchEvent(new MouseEvent(source, event.getID(), event.getWhen(), event.getModifiersEx(), point.x, point.y,
                event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(), event.getButton()));
    }

    private void endToolDrag() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(toolDragEventListener);
        clearTabInsertion(toolNavigationHost);clearTabInsertion(bottomRightNavigation);
        toolDragFeedback.close();clearStripeDrop();
        if(draggedStripeButton!=null){draggedStripeButton.putClientProperty("jlshell.draggingTool",false);draggedStripeButton.repaint();}
        draggedStripeButton=null;draggingToolKey=null;
        frame.getLayeredPane().remove(dockPreview); frame.getLayeredPane().repaint();
        if (toolDragSource != null) toolDragSource.setCursor(toolDragCursor);
        toolDragSource = null; toolDragCursor = null;
    }

    private void toggleRightToolWindow() {
        if (rightToolWindowVisible) hideDock(ToolDockState.Anchor.RIGHT); else showDockedTool(rightKey);
    }

    private boolean dockVisible(ToolDockState.Anchor anchor) {
        return switch (anchor) { case RIGHT -> rightToolWindowVisible; case BOTTOM_LEFT -> toolWindowVisible; case BOTTOM_RIGHT -> bottomRightWindowVisible; };
    }

    private String dockSelection(ToolDockState.Anchor anchor) {
        return switch (anchor) { case RIGHT -> rightKey; case BOTTOM_LEFT -> bottomLeftKey; case BOTTOM_RIGHT -> bottomRightKey; };
    }

    private void hideDock(ToolDockState.Anchor anchor) {
        switch (anchor) {
            case RIGHT -> rightToolWindowVisible = false;
            case BOTTOM_LEFT -> toolWindowVisible = false;
            case BOTTOM_RIGHT -> bottomRightWindowVisible = false;
        }
        syncToolWindow(); persistToolLayout();
    }

    private void toggleDockedTool(String key) {
        Component active = tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if (active instanceof SwingSessionView session && closedBottomTools.getOrDefault(session, java.util.Set.of()).contains(key)) {
            showDockedTool(key); return;
        }
        ToolDockState.Anchor anchor = toolLayout.anchor(key);
        if (dockVisible(anchor) && key.equals(dockSelection(anchor)) && (!rightToolMaximized || anchor == ToolDockState.Anchor.RIGHT)) hideDock(anchor);
        else { showDockedTool(key); persistToolLayout(); }
    }

    private void toggleMaximizedTool() {
        if (!rightToolWindowVisible) return;
        if (!rightToolMaximized) rightDockRestoreDivider = mainSplit.getDividerLocation();
        rightToolMaximized = !rightToolMaximized;
        updateRightDockWidth(true);
        root.revalidate(); root.repaint();
    }

    private void updateRightDockWidth(boolean open) {
        int railWidth = 0; // Stripes belong to the outer shell, outside all editor/tool splitters.
        if (open && rightToolMaximized) {
            if (rightDockContainer.getParent() != maximizedWorkspace) {
                mainSplit.setRightComponent(null);
                horizontalDock.remove(rightDockContainer);
                maximizedWorkspace.add(rightDockContainer, BorderLayout.CENTER);
                split.setLeftComponent(null);
                maximizedSplit.setLeftComponent(sidebar);
                maximizedSplit.setDividerSize(sidebarVisible ? 4 : 0);
                maximizedSplit.setDividerLocation(sidebarVisible ? sidebarWidth : 0);
            }
            rightToolDock.setVisible(true);
            ((CardLayout) workspaceCards.getLayout()).show(workspaceCards, "maximizedTool");
            updateMaximizeToolButton();
            return;
        }
        boolean restoring = rightDockContainer.getParent() == maximizedWorkspace;
        int restoreSidebarWidth = sidebarWidth;
        if (restoring) {
            rightToolMaximized = false;
            maximizedWorkspace.remove(rightDockContainer);
            maximizedSplit.setLeftComponent(null);
            split.setLeftComponent(sidebar);
            split.setDividerSize(sidebarVisible ? 4 : 0);
            split.setDividerLocation(sidebarVisible ? restoreSidebarWidth : 0);
        }
        rightToolMaximized = false;
        updateMaximizeToolButton();
        rightToolDock.setVisible(open);
        if (open) {
            if (mainSplit.getRightComponent() != rightDockContainer) mainSplit.setRightComponent(rightDockContainer);
        } else if (mainSplit.getRightComponent() != null) mainSplit.setRightComponent(null);
        mainSplit.setDividerSize(open ? 4 : 0);
        mainSplit.setEnabled(open);
        int availableToolWidth = Math.max(180, mainSplit.getWidth() - (sidebarVisible ? sidebarWidth : 0) - 240 - railWidth);
        int contentWidth = open ? Math.min(availableToolWidth, Math.max(180, toolLayout.width - railWidth)) : 0;
        // 关闭的是文件/插件内容区；导航轨道始终留在最右侧，供用户再次打开工具窗。
        rightDockContainer.setPreferredSize(new Dimension(rightDockContainerWidth(contentWidth, railWidth), 0));
        if (open && mainSplit.getWidth() > 0) {
            int location = restoring && rightDockRestoreDivider >= 0 ? rightDockRestoreDivider
                    : mainSplit.getWidth() - rightDockContainer.getPreferredSize().width - mainSplit.getDividerSize();
            // 侧栏在铺满期间可能被拉宽；还原时给终端保留最小宽度，不能用旧的外层分隔位置挤窄侧栏。
            int minimumLeft = workspace.getMinimumSize().width
                    + (sidebarVisible ? restoreSidebarWidth + split.getDividerSize() : 0);
            int maximumLeft = Math.max(240, mainSplit.getWidth() - 220);
            mainSplit.setDividerLocation(Math.max(Math.min(minimumLeft, maximumLeft), Math.min(location, maximumLeft)));
        }
        if (restoring) split.setDividerLocation(sidebarVisible ? restoreSidebarWidth : 0);
        mainSplit.revalidate();
        horizontalDock.revalidate();
        // CardLayout.show 会立即校验布局；必须先完成两层分隔位置恢复。
        ((CardLayout) workspaceCards.getLayout()).show(workspaceCards, "normal");
        if (restoring) {
            // BasicSplitPaneUI 在子组件重新挂载并改变宽度的首轮布局中按 resizeWeight 分配空间。
            // 外层尺寸稳定后再次应用用户宽度，避免该轮布局把它重置为侧栏的 minimumSize。
            mainSplit.doLayout();
            split.setDividerLocation(sidebarVisible ? restoreSidebarWidth : 0);
            split.doLayout();
        }
    }

    private void updateMaximizeToolButton() {
        maximizeToolButton.setIcon(new WorkbenchIcon(rightToolMaximized ? WorkbenchIcon.Kind.RESTORE : WorkbenchIcon.Kind.MAXIMIZE));
        String label = text(rightToolMaximized ? "workspace.restoreTool" : "workspace.maximizeTool");
        maximizeToolButton.setToolTipText(label);
        maximizeToolButton.getAccessibleContext().setAccessibleName(label);
        toolConnection.setVisible(rightToolMaximized);
    }

    static int rightDockContentWidth(int mainWidth, int leftSidebarWidth, int railWidth, int dividerWidths, boolean pluginList) {
        int half = rightDockContentWidth(mainWidth, leftSidebarWidth, railWidth, dividerWidths);
        return pluginList ? Math.min(320, half) : half;
    }

    static int rightDockContentWidth(int mainWidth, int leftSidebarWidth, int railWidth, int dividerWidths) {
        int workspaceWidth = Math.max(0, mainWidth - leftSidebarWidth - railWidth - dividerWidths);
        return Math.max(180, workspaceWidth / 2);
    }

    static int rightDockContainerWidth(int contentWidth, int railWidth) {
        return Math.max(0, contentWidth) + Math.max(0, railWidth);
    }

    /** 下方仅承载原有文件、插件及监控页面；终端始终留在主工作区。 */
    public void toggleToolWindow() {
        if (toolWindowVisible) hideDock(ToolDockState.Anchor.BOTTOM_LEFT); else showDockedTool(bottomLeftKey == null ? "monitor" : bottomLeftKey);
    }

    private void releaseToolWindow() {
        if (toolWindowOwner == null) return;
        if (toolDock.getHeight() > 0) dockHeight = Math.max(MIN_MONITOR_DOCK_HEIGHT, toolDock.getHeight());
        dockBody.removeAll();
        toolNavigationHost.removeAll(); bottomRightBody.removeAll(); bottomRightNavigation.removeAll();
        toolWindowOwner = null;
    }

    private List<String> tools(SwingSessionView session, ToolDockState.Anchor anchor) {
        List<String> keys = new ArrayList<>(); keys.add("monitor");
        for (var option : session.toolPageOptions()) { String key = session.toolKey(option.index()); if (key != null) keys.add(key); }
        for(String key:keys) if(!toolLayout.positions.containsKey(key)) {
            var options=session.toolOptions(key);
            if(options!=null) {
                toolLayout.positions.put(key,ToolDockState.Anchor.valueOf(options.anchor().name()));
                if(!hasSavedToolLayout) {
                    toolLayout.width=Math.max(180,Math.min(1600,options.preferredWidth()));
                    toolLayout.height=Math.max(96,Math.min(1200,options.preferredHeight()));
                    dockHeight=toolLayout.height;
                }
            }
        }
        List<String> ordered = new ArrayList<>(toolLayout.positions.keySet());
        for (String key : keys) if (!ordered.contains(key)) ordered.add(key);
        return ordered.stream().filter(keys::contains).filter(key -> toolLayout.anchor(key) == anchor).toList();
    }

    private Icon toolIcon(String key) {
        Component active = tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
        if (active instanceof SwingSessionView session && key != null && key.startsWith("plugin:")) {
            for (Component child : session.toolNavigation().getComponents()) if (child instanceof JToggleButton source
                    && (key.equals(source.getClientProperty("jlshell.pluginKey"))
                    || source.getClientProperty("jlshell.pageIndex") instanceof Integer index && index >= 0
                    && key.equals(session.toolKey(index)))) return source.getIcon();
        }
        return new WorkbenchIcon("monitor".equals(key) ? WorkbenchIcon.Kind.MONITOR : "files".equals(key)
                ? WorkbenchIcon.Kind.FOLDER : WorkbenchIcon.Kind.PLUGIN);
    }

    private String fillBottom(SwingSessionView session, ToolDockState.Anchor anchor, String selected,
                              JPanel navigation, JPanel body) {
        List<String> keys = tools(session, anchor).stream()
                .filter(key -> !closedBottomTools.getOrDefault(session, java.util.Set.of()).contains(key)).toList();
        if (!keys.contains(selected)) selected = keys.isEmpty() ? null : keys.getFirst();
        navigation.removeAll();
        for (String key : keys) {
            JToggleButton button = new JToggleButton(session.toolTitle(key), toolIcon(key)) {
                private boolean closing;
                @Override protected void processMouseEvent(MouseEvent event) {
                    if (event.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event)
                            && event.getX() >= getWidth() - 24) { closing = true; event.consume(); return; }
                    if (closing && event.getID() == MouseEvent.MOUSE_RELEASED) {
                        closing = false; event.consume();
                        if (contains(event.getPoint()) && event.getX() >= getWidth() - 24) closeBottomTool(session, anchor, key);
                        return;
                    }
                    super.processMouseEvent(event);
                }
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g=(Graphics2D)graphics.create();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                        if(isSelected() || getModel().isRollover()) {
                            Color fg=SwingTheme.color("secondary");
                            g.setColor(new Color(fg.getRed(),fg.getGreen(),fg.getBlue(),isSelected()?38:20));
                            g.fillRoundRect(0,1,getWidth()-1,getHeight()-2,7,7);
                            if(isSelected()){g.setColor(new Color(fg.getRed(),fg.getGreen(),fg.getBlue(),60));g.drawRoundRect(0,1,getWidth()-1,getHeight()-2,7,7);}
                        }
                        super.paintComponent(graphics);
                        new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE).paintIcon(this,graphics,getWidth()-21,(getHeight()-16)/2);
                        Object insertion=getClientProperty("jlshell.tabInsertion");
                        if(insertion!=null){Color accent=UIManager.getColor("Component.focusColor");g.setColor(accent!=null?accent:SwingTheme.color("secondary"));int x="before".equals(insertion)?0:getWidth()-2;g.fillRoundRect(x,3,2,getHeight()-6,2,2);}
                    } finally {g.dispose();}
                }
            };
            button.setName("workspace.tool." + key); button.setContentAreaFilled(false);button.setBorderPainted(false);
            button.setBorder(BorderFactory.createEmptyBorder(5,9,5,25));button.setForeground(SwingTheme.color("secondary"));
            button.setSelected(key.equals(selected)); button.setFocusable(false);
            button.addActionListener(event -> showDockedTool(key));
            installToolDrag(button, () -> key);
            button.addMouseListener(new MouseAdapter() {
                private boolean shown;
                @Override public void mousePressed(MouseEvent event) { shown=false; if(event.isPopupTrigger()){showMenu(event);shown=true;} }
                @Override public void mouseReleased(MouseEvent event) { if(!shown && event.isPopupTrigger())showMenu(event);shown=false; }
                private void showMenu(MouseEvent event) {
                    var rows = new ArrayList<com.jlshell.ui.swing.idea.IdeaListPopup.Row>();
                    rows.add(com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("tab.close"),new WorkbenchIcon(WorkbenchIcon.Kind.CLOSE),null,null,()->closeBottomTool(session,anchor,key)));
                    for (var destination : ToolDockState.Anchor.values()) {
                        String title=text(destination == ToolDockState.Anchor.RIGHT ? "workspace.moveToolRight"
                                : destination == ToolDockState.Anchor.BOTTOM_LEFT ? "workspace.moveToolBottomLeft" : "workspace.moveToolBottomRight");
                        rows.add(new com.jlshell.ui.swing.idea.IdeaListPopup.Row(title,null,null,null,null,destination!=anchor,List.of(),()->moveTool(key,destination)));
                    }
                    new com.jlshell.ui.swing.idea.IdeaListPopup(rows,220,false,"",com.jlshell.ui.swing.idea.IdeaListPopup.Style.CONTEXT).show(button,event.getX(),event.getY());
                }
            });
            navigation.add(button);
        }
        Component content = selected == null || !dockVisible(anchor) ? null : session.toolComponent(selected);
        if (content instanceof SwingSftpBrowser browser) browser.setBottomDocked(true);
        if (body.getComponentCount() != 1 || content == null || body.getComponent(0) != content) {
            body.removeAll(); if (content != null) { body.add(content, BorderLayout.CENTER); content.setVisible(true); }
        }
        navigation.revalidate(); body.revalidate(); body.repaint();
        return selected;
    }

    private void closeBottomTool(SwingSessionView session, ToolDockState.Anchor anchor, String key) {
        closedBottomTools.computeIfAbsent(session, ignored -> new java.util.HashSet<>()).add(key);
        boolean empty = tools(session, anchor).stream().allMatch(closedBottomTools.get(session)::contains);
        if (empty) hideDock(anchor); else { syncToolWindow(); persistToolLayout(); }
    }

    private void publishPluginLayout(Component component, ToolDockState.Anchor anchor, boolean maximized) {
        if(component instanceof JComponent swing && swing.getClientProperty("jlshell.hostExtensions") instanceof com.jlshell.plugin.loader.ScopedHostExtensions host) {
            var position=com.jlshell.plugin.api.host.HostExtensions.Anchor.valueOf(anchor.name());
            swing.putClientProperty("jlshell.dockAnchor",position);
            swing.putClientProperty("jlshell.maximized",maximized);
            host.publishLayout(new com.jlshell.plugin.api.host.HostExtensions.Layout(position,swing.getWidth(),swing.getHeight(),swing.isShowing(),maximized));
        }
        if(component instanceof Container container) for(Component child:container.getComponents()) publishPluginLayout(child,anchor,maximized);
    }

    private void syncToolWindow() {
        if (layingOutTools) return;
        layingOutTools = true;
        try {
            for (JTabbedPane group : workspaceSplits.groups()) { group.putClientProperty("JLShell.activeGroup", group == tabs); group.repaint(); }
            Component view = tabViews.get(viewTabs.get(tabs.getSelectedComponent()));
            toolStripe.setEnabled(view instanceof SwingSessionView);
            if (!(view instanceof SwingSessionView session)) {
                releaseToolWindow(); vertical.setBottomComponent(null); vertical.setDividerSize(0);
            } else {
                toolWindowOwner = view;
                dockTitle.setText(tabs.getTitleAt(tabs.getSelectedIndex()));
                bottomRightConnection.setText(dockTitle.getText());
                bottomLeftKey = fillBottom(session, ToolDockState.Anchor.BOTTOM_LEFT, bottomLeftKey, toolNavigationHost, dockBody);
                bottomRightKey = fillBottom(session, ToolDockState.Anchor.BOTTOM_RIGHT, bottomRightKey, bottomRightNavigation, bottomRightBody);
                boolean leftOpen = toolWindowVisible && bottomLeftKey != null;
                boolean rightOpen = bottomRightWindowVisible && bottomRightKey != null;
                Component bottom = !leftOpen ? bottomRightDock : !rightOpen ? toolDock : bottomSplit;
                if (bottom == bottomSplit) { bottomSplit.setLeftComponent(toolDock); bottomSplit.setRightComponent(bottomRightDock); bottomSplit.setDividerLocation(toolLayout.bottomPercent / 100.0); }
                boolean open = leftOpen || rightOpen;
                vertical.setBottomComponent(open ? bottom : null); vertical.setDividerSize(open ? 4 : 0);
                if (open) {
                    vertical.setDividerLocation(Math.max(150, vertical.getHeight() - dockHeight - 4));
                    vertical.doLayout();
                    if (bottom == bottomSplit) bottomSplit.setDividerLocation(toolLayout.bottomPercent / 100.0);
                }
            }
            toolStripe.setSelected(toolWindowVisible);
            syncRightToolWindow(); root.revalidate(); root.repaint();
        } finally { layingOutTools = false; }
    }

    private List<String> stripeKeys(SwingSessionView session, ToolDockState.Anchor anchor) {
        List<String> available = new ArrayList<>();
        for (ToolDockState.Anchor location : ToolDockState.Anchor.values()) available.addAll(tools(session, location));
        for (Component child : session.toolNavigation().getComponents())
            if (child instanceof JToggleButton source && source.getClientProperty("jlshell.pluginKey") instanceof String key
                    && !available.contains(key)) available.add(key);
        List<String> ordered = new ArrayList<>(toolLayout.positions.keySet());
        for (String key : available) if (!ordered.contains(key)) ordered.add(key);
        return ordered.stream().filter(available::contains).filter(key -> toolLayout.anchor(key) == anchor).toList();
    }

    private void rebuildStripe(SwingSessionView session, ToolDockState.Anchor anchor, JPanel host) {
        host.removeAll();
        for (String key : stripeKeys(session, anchor)) {
            int index = session.toolIndex(key);
            JToggleButton lazy = null;
            if (index < 0) for (Component child : session.toolNavigation().getComponents())
                if (child instanceof JToggleButton source && key.equals(source.getClientProperty("jlshell.pluginKey"))) { lazy = source; break; }
            if (index < 0 && lazy == null) continue;
            JToggleButton shortcut = lazy;
            JButton button = "monitor".equals(key) ? toolStripe : new HeaderButton(shortcut == null ? toolIcon(key) : shortcut.getIcon());
            button.setName("workspace.stripe." + key);button.putClientProperty("jlshell.toolKey",key);
            String title = shortcut == null ? session.toolTitle(key) : shortcut.getToolTipText();
            button.setFocusable(false); button.setToolTipText(title);
            button.getAccessibleContext().setAccessibleName(title);
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setSelected(dockVisible(anchor) && key.equals(dockSelection(anchor)));
            if (button != toolStripe) button.addActionListener(event -> {
                int page = session.toolIndex(key);
                if (page < 0) { if (shortcut != null) shortcut.doClick(); }
                else { session.selectToolPage(page); toggleDockedTool(key); }
            });
            installToolDrag(button, () -> key); addStripe(host, button);
        }
        host.revalidate(); host.repaint();
    }

    private void syncRightToolWindow() {
        Object id = viewTabs.get(tabs.getSelectedComponent());
        Component view = tabViews.get(id);
        if (!(view instanceof SwingSessionView session)) {
            rightToolWindowOwner = null; rightToolWindowVisible = false;
            rightToolNavigationHost.removeAll(); leftBottomStripe.removeAll(); rightBottomStripe.removeAll();
            rightDockBody.removeAll(); rightDockContainer.setVisible(false);
            updateRightDockWidth(false); return;
        }
        if (rightToolWindowOwner != session && rightToolMaximized && rightToolWindowOwner instanceof SwingSessionView previous) {
            session.followToolPage(previous); rightKey = session.toolKey(session.selectedPageIndex());
        }
        rightToolWindowOwner = session; rightDockContainer.setVisible(true);
        List<String> keys = tools(session, ToolDockState.Anchor.RIGHT);
        if (!keys.contains(rightKey)) rightKey = keys.isEmpty() ? null : keys.getFirst();
        rightToolPageIndex = rightKey == null ? -1 : session.toolIndex(rightKey);
        boolean open = rightToolWindowVisible && rightKey != null;
        if (open) {
            rightDockTitle.setText(session.toolTitle(rightKey));
            toolConnection.setText(tabs.getTitleAt(tabs.getSelectedIndex()));
            toolConnection.setToolTipText(toolConnection.getText() + " · " + tabConnectionSummaries.getOrDefault(id, ""));
            Component content = session.toolComponent(rightKey);
            if (content instanceof SwingSftpBrowser browser) browser.setBottomDocked(false);
            if (rightDockBody.getComponentCount() != 1 || content == null || rightDockBody.getComponent(0) != content) {
                rightDockBody.removeAll(); if (content != null) { rightDockBody.add(content); content.setVisible(true); }
            }
        } else rightDockBody.removeAll();
        updateRightDockWidth(open);
        if (open && !rightToolMaximized) mainSplit.setDividerLocation(Math.max(240, mainSplit.getWidth() - toolLayout.width - mainSplit.getDividerSize()));
        rebuildStripe(session, ToolDockState.Anchor.RIGHT, rightToolNavigationHost);
        rebuildStripe(session, ToolDockState.Anchor.BOTTOM_LEFT, leftBottomStripe);
        rebuildStripe(session, ToolDockState.Anchor.BOTTOM_RIGHT, rightBottomStripe);
        rightRail.revalidate(); leftRail.revalidate(); rightRail.repaint(); leftRail.repaint();
        rightDockBody.revalidate(); rightDockBody.repaint();
        publishPluginLayout(rightDockBody,ToolDockState.Anchor.RIGHT,rightToolMaximized);
        publishPluginLayout(dockBody,ToolDockState.Anchor.BOTTOM_LEFT,false);
        publishPluginLayout(bottomRightBody,ToolDockState.Anchor.BOTTOM_RIGHT,false);
    }

    private com.jlshell.ui.swing.idea.IdeaListPopup popupMenu(List<SwingMenuBridge.Item> specs) {
        return new com.jlshell.ui.swing.idea.IdeaListPopup(menuRows(specs), 320, false, "");
    }

    public void close() {
        if (shutdownLayoutSnapshot == null)
            captureLayoutForShutdown().forEach(actions.setSetting());
        status.close();
        endToolDrag();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", groupFocus);
        if (saveLayoutTimer.isRunning()) persistToolLayout();
        persistWindowSize();
        saveWindowSizeTimer.stop();
        saveLayoutTimer.stop();
        tabDrags.values().forEach(TabDragController::close);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcutDispatcher);
        for (Object id : List.copyOf(tabViews.keySet())) removeTab(id);
        ToolTipManager.sharedInstance().unregisterComponent(tree);
        MacMenuBridge.uninstall(appMenuBar);
        frame.dispose();
    }

    private final class SidebarTransferHandler extends TransferHandler {
        private final java.awt.datatransfer.DataFlavor flavor =
                new java.awt.datatransfer.DataFlavor("application/x-java-jvm-local-objectref;class=java.util.List", "JLShell connection tree");

        public int getSourceActions(JComponent source) { return MOVE; }

        protected java.awt.datatransfer.Transferable createTransferable(JComponent source) {
            List<SidebarItem> items = selectedItems();
            if (items.isEmpty()) return null;
            boolean folder = items.getFirst() instanceof SidebarItem.FolderItem;
            if (items.stream().anyMatch(item -> (item instanceof SidebarItem.FolderItem) != folder)) return null;
            return new java.awt.datatransfer.Transferable() {
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{flavor}; }
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor requested) { return flavor.equals(requested); }
                public Object getTransferData(java.awt.datatransfer.DataFlavor requested) throws java.awt.datatransfer.UnsupportedFlavorException {
                    if (!isDataFlavorSupported(requested)) throw new java.awt.datatransfer.UnsupportedFlavorException(requested);
                    return items;
                }
            };
        }

        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(flavor) || !search.getText().isBlank()) return false;
            JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
            String target = null;
            if (location.getPath() != null) {
                var node = (DefaultMutableTreeNode) location.getPath().getLastPathComponent();
                if (node.getUserObject() instanceof SidebarItem.FolderItem folder) target = folder.id();
                else if (node.getUserObject() != null) return false;
            }
            try {
                @SuppressWarnings("unchecked") List<SidebarItem> items =
                        (List<SidebarItem>) support.getTransferable().getTransferData(flavor);
                return folderHierarchy.canMove(items, target);
            } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException error) { return false; }
        }

        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                @SuppressWarnings("unchecked") List<SidebarItem> items =
                        (List<SidebarItem>) support.getTransferable().getTransferData(flavor);
                JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
                String target = null;
                if (location.getPath() != null) {
                    var node = (DefaultMutableTreeNode) location.getPath().getLastPathComponent();
                    if (node.getUserObject() instanceof SidebarItem.FolderItem folder) target = folder.id();
                }
                // canImport rejects self/descendant targets; the service also validates persisted ancestry.
                actions.move().accept(List.copyOf(items), target);
                return true;
            } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException error) {
                return false;
            }
        }
    }
}
