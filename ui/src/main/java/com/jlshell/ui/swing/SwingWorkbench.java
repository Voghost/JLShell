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
            "welcome.favorites", "welcome.noRecentConnections", "welcome.noFavorites", "tab.close");

    private final JFrame frame = new JFrame("JLShell");
    private final Actions actions;
    private final Map<String, String> texts;
    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final JTree tree = new JTree(new DefaultMutableTreeNode());
    private final JTextField search = new JTextField();
    private final JComboBox<ProjectProfile> projects = new JComboBox<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JPanel workspace = new JPanel(new CardLayout());
    private final JPanel welcome = new JPanel(new BorderLayout());
    private final JPanel recent = new JPanel();
    private final JPanel favorites = new JPanel();
    private final JLabel status = new JLabel(" ");
    private final JLabel projectName = new JLabel();
    private final JPanel mainToolbar = new JPanel(new BorderLayout());
    private final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final Map<Object, Component> tabViews = new LinkedHashMap<>();
    private final Map<Component, Object> viewTabs = new IdentityHashMap<>();
    private List<ConnectionProfile> profiles = List.of();
    private List<FolderProfile> folders = List.of();
    private final Set<String> collapsed = new HashSet<>();
    private boolean rebuilding;
    private boolean selectingTab;
    private boolean sidebarVisible = true;
    private int sidebarWidth = 300;
    private Object draggingTab;
    private final Map<KeyStroke, Runnable> shortcuts = new HashMap<>();
    private final KeyEventDispatcher shortcutDispatcher = event -> {
        if (event.getID() != java.awt.event.KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
        Runnable action = shortcuts.get(KeyStroke.getKeyStrokeForEvent(event));
        if (action == null) return false;
        action.run();
        event.consume();
        return true;
    };

    public SwingWorkbench(Actions actions, Map<String, String> texts) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Workbench requires EDT");
        this.actions = actions;
        this.texts = Map.copyOf(texts);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcutDispatcher);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent event) { actions.quit().run(); }
        });
        var icon = getClass().getResource("/icons/app_icon.png");
        if (icon != null) frame.setIconImage(new ImageIcon(icon).getImage());
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildMainToolbar(), BorderLayout.NORTH);
        root.add(buildRail(), BorderLayout.WEST);
        buildSidebar();
        workspace.add(buildWelcome(), "welcome");
        workspace.add(tabs, "tabs");
        split.setLeftComponent(sidebar);
        split.setRightComponent(workspace);
        split.setResizeWeight(0);
        split.setContinuousLayout(true);
        sidebar.setMinimumSize(new Dimension(240, 0));
        workspace.setMinimumSize(new Dimension(240, 0));
        root.add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        status.setForeground(SwingTheme.color("secondary"));
        root.add(status, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(640, 480));
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        frame.setSize(Math.min(1480, (int) (screen.width * .9)), Math.min(920, (int) (screen.height * .9)));
        frame.setLocationRelativeTo(null);
        tabs.addChangeListener(event -> {
            if (!selectingTab) actions.selectTab().accept(viewTabs.get(tabs.getSelectedComponent()));
        });
        tabs.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent event) {
                int index = tabs.indexAtLocation(event.getX(), event.getY());
                draggingTab = index < 0 ? null : viewTabs.get(tabs.getComponentAt(index));
                popup(event);
            }
            public void mouseReleased(MouseEvent event) {
                popup(event);
                int index = tabs.indexAtLocation(event.getX(), event.getY());
                if (draggingTab != null && index >= 0 && !event.isPopupTrigger()) actions.reorder().accept(draggingTab, index);
                draggingTab = null;
            }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int index = tabs.indexAtLocation(event.getX(), event.getY());
                if (index >= 0) actions.tabContext().accept(viewTabs.get(tabs.getComponentAt(index)),
                        specs -> SwingUtilities.invokeLater(() -> popupMenu(specs).show(tabs, event.getX(), event.getY())));
            }
        });
    }

    private String text(String key) { return texts.getOrDefault(key, key); }

    private JPanel buildMainToolbar() {
        mainToolbar.setBackground(SwingTheme.color("panel"));
        mainToolbar.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 8));
        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        identity.setOpaque(false);
        JLabel brand = new JLabel("JLShell");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD));
        projectName.setForeground(SwingTheme.color("secondary"));
        identity.add(brand);
        identity.add(projectName);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        toolbar.setOpaque(false);
        toolbar.add(button(WorkbenchIcon.Kind.ADD, "action.newConnection", actions.create()));
        toolbar.add(button(WorkbenchIcon.Kind.RUN, "action.connect", this::connectSelection));
        toolbar.add(button(WorkbenchIcon.Kind.SETTINGS, "action.preferences", actions.preferences()));
        mainToolbar.add(identity, BorderLayout.WEST);
        mainToolbar.add(toolbar, BorderLayout.EAST);
        return mainToolbar;
    }

    private JPanel buildRail() {
        JPanel rail = new JPanel(new BorderLayout());
        rail.setBackground(SwingTheme.color("panel"));
        rail.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, SwingTheme.color("border")));
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 8));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        top.add(button(WorkbenchIcon.Kind.SERVER, "sidebar.toggle", actions.toggleSidebar()));
        top.add(button(WorkbenchIcon.Kind.PROJECT, "project.menu.manage", actions.projects()));
        rail.add(top, BorderLayout.NORTH);
        rail.add(button(WorkbenchIcon.Kind.SETTINGS, "action.preferences", actions.preferences()), BorderLayout.SOUTH);
        return rail;
    }

    private void buildSidebar() {
        sidebar.setBackground(SwingTheme.color("panel"));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        projects.setRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof ProjectProfile project ? project.name() : "");
                return this;
            }
        });
        projects.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        projects.setAlignmentX(Component.LEFT_ALIGNMENT);
        projects.addActionListener(event -> {
            if (!rebuilding && projects.getSelectedItem() instanceof ProjectProfile project) actions.project().accept(project);
        });
        top.add(projects);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 4));
        tools.setOpaque(false);
        tools.setAlignmentX(Component.LEFT_ALIGNMENT);
        tools.add(button(WorkbenchIcon.Kind.ADD, "action.newConnection", actions.create()));
        tools.add(button(WorkbenchIcon.Kind.FOLDER, "sidebar.newFolder", actions.folder()));
        tools.add(button(WorkbenchIcon.Kind.EDIT, "action.editConnection", actions.edit()));
        tools.add(button(WorkbenchIcon.Kind.DELETE, "action.deleteConnection", actions.delete()));
        tools.add(button(WorkbenchIcon.Kind.REFRESH, "action.refresh", actions.refresh()));
        top.add(tools);
        JLabel heading = new JLabel(text("sidebar.connections"));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        top.add(heading);
        search.putClientProperty("JTextField.placeholderText", text("sidebar.search.prompt"));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(search);
        top.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        sidebar.add(top, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setLargeModel(true);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new SidebarTransferHandler());
        tree.setRowHeight(28);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private final Icon folderIcon = new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER);
            private final Icon serverIcon = new WorkbenchIcon(WorkbenchIcon.Kind.SERVER);
            private final Icon terminalIcon = new WorkbenchIcon(WorkbenchIcon.Kind.TERMINAL);
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                         boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object data = ((DefaultMutableTreeNode) value).getUserObject();
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
        tree.addTreeSelectionListener(event -> actions.select().accept(selectedItems()));
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
        rebuilding = true;
        try {
            projects.removeAllItems();
            projects.addItem(new ProjectProfile(null, text("project.label.default"), null));
            for (var project : choices) projects.addItem(project);
            for (int i = 0; i < projects.getItemCount(); i++) {
                ProjectProfile project = projects.getItemAt(i);
                if (Objects.equals(project.id(), activeId)) { projects.setSelectedIndex(i); projectName.setText(project.name()); break; }
            }
        } finally { rebuilding = false; }
    }

    public void addTab(Object id, String title, JComponent view, Runnable close) {
        tabViews.put(id, view); viewTabs.put(view, id);
        tabs.addTab(title, view);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(false);
        JLabel label = new JLabel(title);
        header.add(label);
        MouseAdapter drag = new MouseAdapter() {
            public void mousePressed(MouseEvent event) { if (SwingUtilities.isLeftMouseButton(event)) draggingTab = id; }
            public void mouseReleased(MouseEvent event) {
                Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), tabs);
                int index = tabs.indexAtLocation(point.x, point.y);
                if (event.isPopupTrigger()) actions.tabContext().accept(id,
                        specs -> SwingUtilities.invokeLater(() -> popupMenu(specs).show(tabs, point.x, point.y)));
                else if (draggingTab != null && index >= 0) actions.reorder().accept(id, index);
                draggingTab = null;
            }
            public void mouseClicked(MouseEvent event) { if (SwingUtilities.isLeftMouseButton(event)) actions.selectTab().accept(id); }
        };
        header.addMouseListener(drag);
        label.addMouseListener(drag);
        header.add(button(WorkbenchIcon.Kind.CLOSE, "tab.close", close));
        tabs.setTabComponentAt(tabs.indexOfComponent(view), header);
        ((CardLayout) workspace.getLayout()).show(workspace, "tabs");
    }

    public void removeTab(Object id) {
        Component view = tabViews.remove(id);
        if (view == null) return;
        viewTabs.remove(view);
        tabs.remove(view);
        if (view instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        if (tabs.getTabCount() == 0) ((CardLayout) workspace.getLayout()).show(workspace, "welcome");
    }

    public void selectTab(Object id) {
        Component view = tabViews.get(id);
        if (view == null) return;
        selectingTab = true;
        try { tabs.setSelectedComponent(view); } finally { selectingTab = false; }
    }

    public void reorderTabs(List<Object> order) {
        selectingTab = true;
        Component selected = tabs.getSelectedComponent();
        try {
            for (int i = 0; i < order.size(); i++) {
                Component view = tabViews.get(order.get(i));
                int index = tabs.indexOfComponent(view);
                if (index < 0 || index == i) continue;
                String title = tabs.getTitleAt(index);
                Component header = tabs.getTabComponentAt(index);
                tabs.removeTabAt(index);
                tabs.insertTab(title, null, view, null, i);
                tabs.setTabComponentAt(i, header);
            }
            if (selected != null) tabs.setSelectedComponent(selected);
        } finally { selectingTab = false; }
    }

    public void setSidebarVisible(boolean visible) {
        if (sidebarVisible == visible) return;
        sidebarVisible = visible;
        if (!visible) { sidebarWidth = split.getDividerLocation(); sidebar.setMinimumSize(new Dimension(0, 0)); }
        else sidebar.setMinimumSize(new Dimension(240, 0));
        sidebar.setVisible(visible);
        split.setDividerSize(visible ? 1 : 0);
        split.setDividerLocation(visible ? sidebarWidth : 0);
    }

    public void setFocusMode(boolean focused) { mainToolbar.setVisible(!focused); }
    public void setStatus(String message) { status.setText(message == null ? " " : message); }
    public void setTitle(String title) { frame.setTitle(title); }
    public void setMenuBar(JMenuBar menu) { frame.setJMenuBar(menu); }
    public void setShortcuts(Map<KeyStroke, Runnable> bindings) { shortcuts.clear(); shortcuts.putAll(bindings); }
    public void setEnabled(boolean enabled) { frame.setEnabled(enabled); if (enabled) frame.toFront(); }
    public void show() { frame.setVisible(true); split.setDividerLocation(sidebarVisible ? sidebarWidth : 0); frame.toFront(); tree.requestFocusInWindow(); }
    public void hide() { frame.setVisible(false); }
    public JFrame frame() { return frame; }

    public void applyTheme(AppTheme theme, String accent, String family, int fontSize) {
        SwingTheme.install(theme, accent, family, fontSize);
        SwingTheme.update(frame);
        sidebar.setBackground(SwingTheme.color("panel"));
        mainToolbar.setBackground(SwingTheme.color("panel"));
        status.setForeground(SwingTheme.color("secondary"));
        projectName.setForeground(SwingTheme.color("secondary"));
        tree.setRowHeight(Math.max(28, fontSize + 12));
        frame.revalidate(); frame.repaint();
    }

    private JPopupMenu popupMenu(List<SwingMenuBridge.Item> specs) {
        JPopupMenu popup = new JPopupMenu();
        for (var spec : specs) {
            if (spec.kind() == 1) { popup.addSeparator(); continue; }
            JMenuItem item = new JMenuItem(spec.text());
            item.setEnabled(!spec.disabled());
            item.addActionListener(event -> spec.action().run());
            popup.add(item);
        }
        return popup;
    }

    public void close() {
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
