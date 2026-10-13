package com.jlshell.ui.swing;

import com.jlshell.core.model.ConnectionType;
import com.jlshell.ui.model.ConnectionProfile;
import com.jlshell.ui.model.FavoriteConnectionProfile;
import com.jlshell.ui.model.FolderProfile;
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
import java.awt.event.MouseEvent;
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
                          java.util.function.BiConsumer<Object, java.util.function.Consumer<List<SwingMenuBridge.Item>>> tabContext) {}

    public static final List<String> TEXT_KEYS = List.of("action.newConnection", "sidebar.newFolder",
            "action.editConnection", "action.deleteConnection", "action.refresh", "action.preferences",
            "action.connect", "sidebar.toggle", "sidebar.connections", "sidebar.search.prompt", "project.menu.manage",
            "project.label.default", "welcome.title", "welcome.subtitle", "welcome.recentConnections",
            "welcome.favorites", "welcome.noRecentConnections", "welcome.noFavorites", "tab.close",
            "workspace.tools", "workspace.hideTools", "workspace.monitoring", "menu.main",
            "project.popup.current", "project.popup.other", "project.popup.defaultDescription",
            "project.search.prompt", "settings.quick.plugins", "settings.quick.shortcuts", "settings.quick.theme",
            "settings.quick.view", "settings.quick.account", "menu.help.about", "menu.help.checkUpdates",
            "menu.view", "menu.account", "menu.file", "menu.connections", "menu.help", "menu.settings",
            "theme.dark", "theme.light", "preferences.tab.plugins", "preferences.tab.shortcuts",
            "workspace.openSessions", "workspace.noOpenSessions", "tab.closeLeft", "tab.closeRight",
            "tab.closeOthersInGroup", "tab.closeAllInGroup", "tab.splitRight", "tab.splitDown", "tab.moveToGroup",
            "tab.changeSplitOrientation", "tab.unsplit", "tab.unsplitAll", "tab.emptyGroup", "tab.duplicateSession", "tab.groupMenu");

    private final JFrame frame = new JFrame("JLShell");
    private final Actions actions;
    private final Map<String, String> texts;
    private final JPanel sidebar = new IslandPanel(new BorderLayout(), "panel");
    private final JTree tree = new JTree(new DefaultMutableTreeNode());
    private final JTextField search = new JTextField();
    private final JButton projects = new JButton("JLShell  ⌄");
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
    private final JLabel status = new JLabel(" ");
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
    private final JPanel root = new JPanel(new BorderLayout());
    private final JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JPanel toolDock = new IslandPanel(new BorderLayout(), "canvas");
    private final JPanel dockBody = new JPanel(new BorderLayout());
    private final JLabel dockTitle = new JLabel();
    private final JButton toolStripe;
    private final JButton projectStripe;
    private final JButton menuButton;
    private final JButton runButton;
    private final JButton connectionName = new JButton();
    private final JPanel searchBar = new JPanel(new BorderLayout());
    private List<JMenu> mainMenus = List.of();
    private boolean toolWindowVisible = true;
    private Component toolWindowOwner;
    private int dockHeight = 250;
    private final Map<Object, JPanel> tabHosts = new LinkedHashMap<>();
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final Map<Object, Component> tabViews = new LinkedHashMap<>();
    private final Map<Component, Object> viewTabs = new IdentityHashMap<>();
    private List<ConnectionProfile> profiles = List.of();
    private List<FolderProfile> folders = List.of();
    private final Set<String> collapsed = new HashSet<>();
    private boolean rebuilding;
    private boolean selectingTab;
    private boolean sidebarVisible = true;
    private int sidebarWidth = 280;
    private final Map<KeyStroke, Runnable> shortcuts = new HashMap<>();
    private final KeyEventDispatcher shortcutDispatcher = event -> {
        if (event.getID() != java.awt.event.KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
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
        tabs = createTabGroup();
        workspaceSplits = new WorkspaceSplitLayout(tabs, this::createTabGroup);
        toolStripe = button(WorkbenchIcon.Kind.MONITOR, "workspace.tools", this::toggleToolWindow);
        projectStripe = button(WorkbenchIcon.Kind.FOLDER, "sidebar.toggle", () -> actions.toggleSidebar().run());
        menuButton = button(WorkbenchIcon.Kind.MENU, "menu.main", this::showMainMenu);
        runButton = button(WorkbenchIcon.Kind.RUN, "action.connect", this::connectSelection);
        settingsButton = button(WorkbenchIcon.Kind.SETTINGS, "action.preferences", this::showSettingsMenu);
        configureWindowHeader();
        frame.getRootPane().putClientProperty("jlshell.workbench", this);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcutDispatcher);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent event) { actions.quit().run(); }
        });
        var icon = getClass().getResource("/icons/app_icon.png");
        if (icon != null) frame.setIconImage(new ImageIcon(icon).getImage());
        root.setBackground(SwingTheme.color("frame"));
        root.add(buildMainToolbar(), BorderLayout.NORTH);
        root.add(buildRail(), BorderLayout.WEST);
        root.add(buildRightRail(), BorderLayout.EAST);
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
        buildToolDock();
        vertical.setTopComponent(split);
        vertical.setResizeWeight(1);
        vertical.setContinuousLayout(true);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        vertical.setOpaque(false);
        vertical.setDividerSize(0);
        JPanel islands = new JPanel(new BorderLayout());
        islands.setOpaque(false);
        islands.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        islands.add(vertical);
        root.add(islands, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
        status.setForeground(SwingTheme.color("secondary"));
        root.add(status, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(640, 480));
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        frame.setSize(Math.min(1480, (int) (screen.width * .9)), Math.min(920, (int) (screen.height * .9)));
        frame.setLocationRelativeTo(null);
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
        JPanel trailing = new JPanel(new BorderLayout());
        trailing.setOpaque(false);
        trailing.add(more, BorderLayout.EAST);
        group.putClientProperty("JTabbedPane.trailingComponent", trailing);
        group.addChangeListener(event -> { if (!selectingTab) activateGroup(group, true); });
        var drag = new TabDragController(group, host -> {
            selectTab(viewTabs.get(host));
            actions.selectTab().accept(viewTabs.get(host));
        }, (host, position) -> actions.reorder().accept(viewTabs.get(host), orderedHosts().indexOf(host)),
                (host, position) -> moveTabLocally(group, host, position), (host, point) -> {
            JTabbedPane destination = workspaceSplits.groupAt(SwingUtilities.convertPoint(group, point, workspaceSplits));
            if (destination == null || destination == group) return false;
            transferTab(host, group, destination);
            activateGroup(destination, true);
            pruneEmptyGroup(group);
            actions.reorder().accept(viewTabs.get(host), orderedHosts().indexOf(host));
            return true;
        });
        tabDrags.put(group, drag);
        group.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && group.getTabCount() == 0) activateGroup(group, false);
                popup(event);
            }
            public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
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
            pane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING, "medium");
        }
    }

    private JPanel buildMainToolbar() {
        mainToolbar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        mainToolbar.setPreferredSize(new Dimension(0, 40));
        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        identity.setOpaque(false);
        JPanel macButtons = new JPanel();
        macButtons.putClientProperty(com.formdev.flatlaf.FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac horizontal");
        macButtons.setOpaque(false);
        identity.add(macButtons);
        identity.add(menuButton);
        projects.putClientProperty("JButton.buttonType", "toolBarButton");
        projects.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        projects.setHorizontalAlignment(SwingConstants.LEFT);
        projects.setFocusable(false);
        projects.addActionListener(event -> showProjectPopup());
        identity.add(projects);
        headerMenus.setOpaque(false);
        identity.add(headerMenus);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        toolbar.setOpaque(false);
        connectionName.setForeground(SwingTheme.color("secondary"));
        connectionName.putClientProperty("JButton.buttonType", "toolBarButton");
        connectionName.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        connectionName.setFocusable(false);
        connectionName.setToolTipText(text("workspace.openSessions"));
        connectionName.getAccessibleContext().setAccessibleName(text("workspace.openSessions"));
        connectionName.addActionListener(event -> showSessionPopup());
        connectionName.setText(text("workspace.noOpenSessions") + "  ⌄");
        connectionName.setEnabled(false);
        toolbar.add(connectionName);
        toolbar.add(button(WorkbenchIcon.Kind.ADD, "action.newConnection", actions.create()));
        toolbar.add(button(WorkbenchIcon.Kind.SEARCH, "sidebar.search.prompt", this::toggleSearch));
        toolbar.add(settingsButton);
        JPanel windowButtons = new JPanel();
        windowButtons.putClientProperty(com.formdev.flatlaf.FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "win horizontal");
        windowButtons.setOpaque(false);
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
        MouseAdapter movement = new MouseAdapter() {
            private Point screenPoint;
            public void mousePressed(MouseEvent event) { screenPoint = event.getLocationOnScreen(); }
            public void mouseDragged(MouseEvent event) {
                if (screenPoint == null || frame.getExtendedState() != JFrame.NORMAL) return;
                Point next = event.getLocationOnScreen();
                frame.setLocation(frame.getX() + next.x - screenPoint.x, frame.getY() + next.y - screenPoint.y);
                screenPoint = next;
            }
            public void mouseReleased(MouseEvent event) { screenPoint = null; }
        };
        mainToolbar.addMouseListener(movement);
        mainToolbar.addMouseMotionListener(movement);
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
        JPanel bottom = stripeColumn();
        addStripe(bottom, toolStripe);
        rail.add(bottom, BorderLayout.SOUTH);
        return rail;
    }

    private JPanel buildRightRail() {
        JPanel rail = new JPanel(new BorderLayout());
        rail.setOpaque(false);
        rail.setPreferredSize(new Dimension(36, 0));
        JPanel bottom = stripeColumn();
        addStripe(bottom, button(WorkbenchIcon.Kind.SETTINGS, "action.preferences", actions.preferences()));
        rail.add(bottom, BorderLayout.SOUTH);
        return rail;
    }

    private void buildSidebar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        JLabel heading = new JLabel(text("sidebar.connections") + "  ⌄");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        header.add(heading);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        tools.setOpaque(false);
        tools.add(button(WorkbenchIcon.Kind.SEARCH, "sidebar.search.prompt", this::toggleSearch));
        tools.add(button(WorkbenchIcon.Kind.ADD, "action.newConnection", actions.create()));
        JButton more = button(WorkbenchIcon.Kind.MORE, "menu.main", () -> {});
        more.addActionListener(event -> {
            var rows = List.of(
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("sidebar.newFolder"), new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER), "", null, actions.folder()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.editConnection"), null, "", null, actions.edit()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.deleteConnection"), null, "", null, actions.delete()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("action.refresh"), null, "", "", actions.refresh()),
                    com.jlshell.ui.swing.idea.IdeaListPopup.Row.action(text("project.menu.manage"), new WorkbenchIcon(WorkbenchIcon.Kind.PROJECT), "", null, actions.projects()));
            var popup = new com.jlshell.ui.swing.idea.IdeaListPopup(rows, 300, false, "");
            popup.show(more, 0, more.getHeight());
        });
        tools.add(more);
        tools.add(button(WorkbenchIcon.Kind.HIDE, "sidebar.toggle", actions.toggleSidebar()));
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
            private final Icon folderIcon = new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER);
            private final Icon serverIcon = new WorkbenchIcon(WorkbenchIcon.Kind.SERVER);
            private final Icon terminalIcon = new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL);
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                         boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object data = ((DefaultMutableTreeNode) value).getUserObject();
                setFont(tree.getFont());
                setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
                setText(""); setIcon(null); setToolTipText(null);
                if (data instanceof SidebarItem item) {
                    setText(item.displayName());
                    setIcon(item instanceof SidebarItem.FolderItem ? folderIcon
                            : ((SidebarItem.ConnectionItem) item).connectionType() == ConnectionType.LOCAL_SHELL ? terminalIcon : serverIcon);
                    setToolTipText(item instanceof SidebarItem.ConnectionItem connection ? connection.summary() : item.displayName());
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
        scroll.setBorder(BorderFactory.createEmptyBorder());
        sidebar.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        runButton.setText(text("action.connect"));
        runButton.setHorizontalAlignment(SwingConstants.LEFT);
        runButton.setPreferredSize(new Dimension(0, 30));
        footer.add(runButton);
        sidebar.add(footer, BorderLayout.SOUTH);
    }

    private JPanel buildWelcome() {
        welcome.setBorder(BorderFactory.createEmptyBorder(40, 40, 32, 40));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(text("welcome.title"));
        title.setFont(title.getFont().deriveFont(24f));
        header.add(title);
        header.add(Box.createVerticalStrut(8));
        JLabel subtitle = new JLabel(text("welcome.subtitle"));
        subtitle.setForeground(SwingTheme.color("secondary"));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(20));
        JButton create = new JButton(text("action.newConnection"), new WorkbenchIcon(WorkbenchIcon.Kind.ADD));
        create.addActionListener(event -> actions.create().run());
        header.add(create);
        header.add(Box.createVerticalStrut(32));
        welcome.add(header, BorderLayout.NORTH);
        JPanel lists = new JPanel(new GridLayout(1, 2, 24, 0));
        lists.add(welcomeList(text("welcome.recentConnections"), recent));
        lists.add(welcomeList(text("welcome.favorites"), favorites));
        welcome.add(lists);
        return welcome;
    }

    private JPanel welcomeList(String title, JPanel rows) {
        JPanel list = new JPanel(new BorderLayout(0, 12));
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JLabel label = new JLabel(title);
        label.setForeground(SwingTheme.color("secondary"));
        list.add(label, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        list.add(scroll);
        return list;
    }

    public void updateWelcome(List<RecentSessionProfile> sessions, List<FavoriteConnectionProfile> starred) {
        recent.removeAll(); favorites.removeAll();
        for (var session : sessions) recent.add(welcomeRow(session.connectionId(), session.displayName(), session.summary()));
        for (var item : starred) favorites.add(welcomeRow(item.id(), item.displayName(), item.hostInfo()));
        if (sessions.isEmpty()) recent.add(new JLabel(text("welcome.noRecentConnections")));
        if (starred.isEmpty()) favorites.add(new JLabel(text("welcome.noFavorites")));
        recent.revalidate(); favorites.revalidate(); recent.repaint(); favorites.repaint();
    }

    private JButton welcomeRow(String id, String name, String summary) {
        JButton row = new JButton(name, new WorkbenchIcon(WorkbenchIcon.Kind.SERVER));
        row.putClientProperty("JButton.buttonType", "toolBarButton");
        row.setHorizontalAlignment(SwingConstants.LEFT);
        row.setToolTipText(summary);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.addActionListener(event -> profiles.stream().filter(profile -> profile.id().equals(id)).findFirst().ifPresent(actions.connect()));
        return row;
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

    public void updateConnections(List<FolderProfile> folders, List<ConnectionProfile> profiles, Set<String> collapsed) {
        this.folders = List.copyOf(folders); this.profiles = List.copyOf(profiles);
        this.collapsed.clear(); this.collapsed.addAll(collapsed);
        rebuildTree();
    }

    private void rebuildTree() {
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
        projects.setText(activeProject.name() + "  ⌄");
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
        JPanel host = new JPanel(new BorderLayout());
        host.setOpaque(false);
        host.add(view);
        tabViews.put(id, view); tabHosts.put(id, host); viewTabs.put(host, id);
        tabCloseActions.put(id, close);
        if (view instanceof SwingSessionView session) {
            session.setToolWindowRevealAction(() -> {
                if (Objects.equals(viewTabs.get(tabs.getSelectedComponent()), id)) {
                    toolWindowVisible = true;
                    syncToolWindow();
                }
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
            public void mousePressed(MouseEvent event) { popup(event); }
            public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                JTabbedPane group = groupFor(host);
                if (group != null) showTabContext(id, group, SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), group));
            }
        };
        header.addMouseListener(context);
        label.addMouseListener(context);
        JButton closeButton = button(WorkbenchIcon.Kind.CLOSE, "tab.close", close);
        closeButton.setPreferredSize(new Dimension(18, 18));
        header.add(closeButton);
        tabs.setTabComponentAt(tabs.indexOfComponent(host), header);
        syncToolWindow();
        updateSessionSelector();
        ((CardLayout) workspace.getLayout()).show(workspace, "tabs");
    }

    public void removeTab(Object id) {
        Component view = tabViews.remove(id);
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
        connectionName.setText((index < 0 ? text("workspace.noOpenSessions") : tabs.getTitleAt(index)) + "  ⌄");
        connectionName.setIcon(index < 0 ? null : new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL));
        connectionName.setEnabled(!tabViews.isEmpty());
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
        specs.stream().filter(item -> item.text().equals(text("tab.duplicateSession"))).findFirst().ifPresent(item ->
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
        if (!visible) { sidebarWidth = split.getDividerLocation(); sidebar.setMinimumSize(new Dimension(0, 0)); }
        else sidebar.setMinimumSize(new Dimension(240, 0));
        sidebar.setVisible(visible);
        split.setDividerSize(visible ? 4 : 0);
        projectStripe.setSelected(visible);
        split.setDividerLocation(visible ? sidebarWidth : 0);
    }

    public void setFocusMode(boolean focused) {
        // 一体化栏承载窗口按钮，折叠时仍保留原生标题栏所需高度。
        mainToolbar.setPreferredSize(new Dimension(0, focused ? 30 : 40));
        mainToolbar.revalidate();
    }
    public void setStatus(String message) { status.setText(message == null ? " " : message); }
    public void setTitle(String title) { frame.setTitle(title); }
    public void setMenuBar(JMenuBar menu) {
        mainMenus = Arrays.stream(menu.getComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast).toList();
        headerMenus.removeAll();
        // 对照 IDEA IdeMenuHelper：Windows/Linux 在同一标题区横向展示菜单。
        if (!com.formdev.flatlaf.util.SystemInfo.isMacOS) {
            for (JMenu source : mainMenus) {
                JButton button = new JButton(source.getText());
                button.putClientProperty("JButton.buttonType", "toolBarButton");
                button.setFocusable(false);
                button.setMargin(new Insets(4, 7, 4, 7));
                int mnemonic = source.getText().equals(text("menu.file")) ? 'F'
                        : source.getText().equals(text("menu.connections")) ? 'C'
                        : source.getText().equals(text("menu.account")) ? 'A'
                        : source.getText().equals(text("menu.view")) ? 'V'
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
        frame.setEnabled(allowed);
        if (allowed) frame.toFront();
    }
    public void show() {
        frame.setVisible(true);
        split.setDividerLocation(sidebarVisible ? sidebarWidth : 0);
        if (vertical.getBottomComponent() != null) {
            vertical.setDividerLocation(Math.max(150, vertical.getHeight() - dockHeight - 4));
        }
        frame.toFront(); tree.requestFocusInWindow();
    }
    public void hide() { frame.setVisible(false); }
    public JFrame frame() { return frame; }

    public void applyTheme(AppTheme theme, String accent, String family, int fontSize) {
        if (SwingTheme.isCurrent(theme, accent, family, fontSize)) return;
        SwingTheme.install(theme, accent, family, fontSize);
        SwingTheme.update(frame);
        root.setBackground(SwingTheme.color("frame"));
        status.setForeground(SwingTheme.color("secondary"));
        connectionName.setForeground(SwingTheme.color("secondary"));
        for (JMenu menu : mainMenus) SwingTheme.update(menu);
        dockTitle.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, UIManager.getColor("Component.focusColor")),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
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
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        JPanel labels = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        labels.setOpaque(false);
        JLabel name = new JLabel(text("workspace.tools"));
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        labels.add(name);
        dockTitle.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, UIManager.getColor("Component.focusColor")),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        labels.add(dockTitle);
        header.add(labels);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 1));
        tools.setOpaque(false);
        tools.add(button(WorkbenchIcon.Kind.HIDE, "workspace.hideTools", this::toggleToolWindow));
        header.add(tools, BorderLayout.EAST);
        toolDock.add(header, BorderLayout.NORTH);
        dockBody.setOpaque(false);
        dockBody.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        toolDock.add(dockBody);
        toolDock.setMinimumSize(new Dimension(0, 120));
    }

    /** 下方仅承载原有文件、插件及监控页面；终端始终留在主工作区。 */
    public void toggleToolWindow() {
        toolWindowVisible = !toolWindowVisible;
        syncToolWindow();
    }

    private void releaseToolWindow() {
        if (toolWindowOwner == null) return;
        if (toolDock.getHeight() > 0) dockHeight = Math.max(120, toolDock.getHeight());
        dockBody.removeAll();
        toolWindowOwner = null;
    }

    private void syncToolWindow() {
        Object id = viewTabs.get(tabs.getSelectedComponent());
        Component view = tabViews.get(id);
        boolean supported = view instanceof SwingSessionView;
        toolStripe.setEnabled(supported);
        if (toolWindowVisible && supported && view == toolWindowOwner) return;
        releaseToolWindow();
        boolean show = toolWindowVisible && supported;
        if (!show) {
            vertical.setBottomComponent(null);
            vertical.setDividerSize(0);
        } else {
            toolWindowOwner = view;
            JPanel content = ((SwingSessionView) view).toolWindow();
            content.setVisible(true);
            dockBody.add(content);
            dockTitle.setText(tabs.getTitleAt(tabs.getSelectedIndex()));
            vertical.setBottomComponent(toolDock);
            vertical.setDividerSize(4);
            vertical.revalidate();
            vertical.setDividerLocation(Math.max(150, vertical.getHeight() - dockHeight - 4));
        }
        toolStripe.setSelected(show);
        root.revalidate(); root.repaint();
    }

    private com.jlshell.ui.swing.idea.IdeaListPopup popupMenu(List<SwingMenuBridge.Item> specs) {
        return new com.jlshell.ui.swing.idea.IdeaListPopup(menuRows(specs), 320, false, "");
    }

    public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", groupFocus);
        tabDrags.values().forEach(TabDragController::close);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcutDispatcher);
        for (Object id : List.copyOf(tabViews.keySet())) removeTab(id);
        ToolTipManager.sharedInstance().unregisterComponent(tree);
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
            if (location.getPath() == null) return true;
            var node = (DefaultMutableTreeNode) location.getPath().getLastPathComponent();
            return node.getUserObject() == null || node.getUserObject() instanceof SidebarItem.FolderItem;
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
                // 循环与层级限制仍交给原有移动动作及服务处理。
                actions.move().accept(List.copyOf(items), target);
                return true;
            } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException error) {
                return false;
            }
        }
    }
}
