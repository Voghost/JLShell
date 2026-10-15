package com.jlshell.ui.swing;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.model.*;
import com.jlshell.sftp.service.SftpService;
import com.jlshell.sftp.service.TransferProgressListener;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.swing.idea.IdeaComponentPopup;
import com.jlshell.ui.swing.idea.IdeaListPopup;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Native Swing file tool window. All filesystem and SFTP work runs outside the EDT. */
public final class SwingSftpBrowser extends JPanel implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final SshSession session;
    private final SftpService service;
    private final I18nService i18n;
    private final Browser local, remote;
    private final JLabel message = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton cancel;
    private final List<IdeaListPopup> popups = new ArrayList<>();
    private final List<IdeaComponentPopup> directoryPopups = new ArrayList<>();
    private Runnable unbind = () -> {};
    private volatile boolean closed;
    private boolean transferring;
    private boolean connected = true;
    private volatile boolean cancelled;
    private boolean follow = true;
    private final JToggleButton followButton = new JToggleButton(new WorkbenchIcon(WorkbenchIcon.Kind.FOCUS));
    private String terminalPath;

    /** Capture the FX model on its owning thread; construct Swing components lazily on EDT. */
    public static Supplier<SwingSftpBrowser> prepare(SshSession session, SftpService service, I18nService i18n,
                                                    String start, StringProperty cwd) {
        return () -> {
            SwingSftpBrowser browser = new SwingSftpBrowser(session, service, i18n, start);
            ChangeListener<String> listener = (observable, oldPath, path) -> SwingUtilities.invokeLater(() -> browser.terminalDirectory(path));
            FxThread.run(() -> {
                cwd.addListener(listener);
                String path = cwd.get();
                SwingUtilities.invokeLater(() -> {
                    if (browser.closed) FxThread.run(() -> cwd.removeListener(listener));
                    else browser.terminalDirectory(path);
                });
            });
            browser.unbind = () -> FxThread.run(() -> cwd.removeListener(listener));
            return browser;
        };
    }

    public SwingSftpBrowser(SshSession session, SftpService service, I18nService i18n, String remoteStart) {
        this(session, service, i18n, remoteStart, Path.of(System.getProperty("user.home")));
    }

    SwingSftpBrowser(SshSession session, SftpService service, I18nService i18n, String remoteStart, Path localStart) {
        super(new BorderLayout());
        this.session = session; this.service = service; this.i18n = i18n;
        setName("sftp.browser"); setMinimumSize(new Dimension(260, 260));
        local = new Browser(false); remote = new Browser(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, local, remote);
        split.setName("sftp.verticalSplit"); split.setResizeWeight(.5); split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder()); split.setContinuousLayout(true);
        add(split);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(SwingTheme.border(1, 0, 0, 0), BorderFactory.createEmptyBorder(5, 10, 5, 6)));
        message.setName("sftp.status");
        message.setFont(UIManager.getFont("Label.font").deriveFont(11f));
        message.putClientProperty("JLShell.secondaryForeground", true);
        JPanel activity = new JPanel(new BorderLayout(5, 0)); activity.setOpaque(false);
        progress.setPreferredSize(new Dimension(80, 5)); progress.setVisible(false);
        cancel = tool(WorkbenchIcon.Kind.CLOSE, t("sftp.cancel"), this::cancelTransfer);
        cancel.setVisible(false); activity.add(progress); activity.add(cancel, BorderLayout.EAST);
        footer.add(message); footer.add(activity, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
        local.navigate(localStart.toString());
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing())
                dismissPopups();
        });
        remote.navigate(remoteStart == null || remoteStart.isBlank() ? "." : remoteStart);
    }

    private void cancelTransfer() { cancelled = true; cancel.setEnabled(false); }
    private String t(String key) { return i18n.get(key); }
    private void terminalDirectory(String path) {
        if (closed || path == null || path.isBlank()) return;
        terminalPath = path;
        if (follow && !path.equals(remote.requestedPath)) remote.navigate(path);
    }
    private void setFollow(boolean enabled) {
        follow = enabled;
        followButton.setSelected(enabled);
        if (enabled && terminalPath != null && !terminalPath.equals(remote.requestedPath)) remote.navigate(terminalPath);
    }
    private JButton tool(WorkbenchIcon.Kind icon, String title, Runnable action) {
        return tool(new WorkbenchIcon(icon), title, action);
    }
    private JButton tool(Icon icon, String title, Runnable action) {
        JButton button = new JButton(icon);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setPreferredSize(new Dimension(27, 27)); button.setFocusable(false); button.setToolTipText(title);
        button.getAccessibleContext().setAccessibleName(title); button.addActionListener(event -> action.run()); return button;
    }
    private void status(String text) { message.setText(text); message.setToolTipText(text); }
    private void failure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        status(t("sftp.error") + ": " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
    }
    private static String join(String parent, String name) { return parent.endsWith("/") ? parent + name : parent + "/" + name; }
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }
    private record Entry(String path, String name, boolean directory, long size, Instant modified, String permissions) {}
    private record Listing(String path, List<Entry> entries) {}
    private CompletableFuture<Listing> readDirectory(boolean remote, String path) {
        if (remote) return CompletableFuture.supplyAsync(() -> service.listDirectory(session, path)).thenCompose(f -> f)
                .thenApply(result -> new Listing(result.canonicalPath(), result.entries().stream()
                        .filter(e -> !e.name().equals(".") && !e.name().equals(".."))
                        .map(e -> new Entry(e.path(), e.name(), e.isDirectory(), e.size(), e.modifiedAt(), e.permissionString()))
                        .sorted(order()).toList()));
        return CompletableFuture.supplyAsync(() -> {
            Path dir = Path.of(path).toAbsolutePath().normalize();
            try (var files = Files.list(dir)) {
                return new Listing(dir.toString(), files.map(file -> {
                    try {
                        var attr = Files.readAttributes(file, BasicFileAttributes.class);
                        return new Entry(file.toString(), file.getFileName().toString(), attr.isDirectory(), attr.size(), attr.lastModifiedTime().toInstant(), "");
                    } catch (Exception ignored) { return null; }
                }).filter(Objects::nonNull).sorted(order()).toList());
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }
    private static Comparator<Entry> order() { return Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER); }

    private final class Browser extends JPanel {
        final boolean isRemote;
        final FileModel model = new FileModel();
        final JTable table = new JTable(model);
        final JPanel crumbs = new JPanel(new BreadcrumbLayout());
        final JPanel pathHost = new JPanel(new CardLayout());
        final JTextField editor = new JTextField();
        final JLabel count = new JLabel(" ");
        final JLabel empty = new JLabel(" ", SwingConstants.CENTER);
        final JPanel body = new JPanel(new CardLayout());
        String path;
        final JButton transferButton;
        long sequence;
        String requestedPath;
        Browser(boolean remoteSide) {
            super(new BorderLayout()); isRemote = remoteSide;
            setName(remoteSide ? "sftp.remote" : "sftp.local"); setMinimumSize(new Dimension(230, 130));
            JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
            JPanel titleRow = new JPanel(new BorderLayout(8, 0)); titleRow.setOpaque(false);
            titleRow.setBorder(BorderFactory.createEmptyBorder(4, 10, 0, 6));
            JLabel title = new JLabel(t(isRemote ? "sftp.remote" : "sftp.local"), new WorkbenchIcon(isRemote ? WorkbenchIcon.Kind.SERVER : WorkbenchIcon.Kind.PROJECT), JLabel.LEFT);
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            JPanel caption = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 4)); caption.setOpaque(false); caption.add(title);
            count.setFont(count.getFont().deriveFont(11f)); count.putClientProperty("JLShell.secondaryForeground", true); caption.add(count);
            titleRow.add(caption);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0)); actions.setOpaque(false);
            if (isRemote) {
                followButton.setName("sftp.followTerminal"); followButton.setSelected(follow);
                followButton.setToolTipText(t("sftp.followTerminal"));
                followButton.getAccessibleContext().setAccessibleName(t("sftp.followTerminal"));
                followButton.putClientProperty("JButton.buttonType", "toolBarButton");
                followButton.setPreferredSize(new Dimension(27, 27)); followButton.setFocusable(false);
                followButton.addActionListener(event -> setFollow(followButton.isSelected()));
                actions.add(followButton);
            }
            actions.add(tool(new FileActionIcon("home"), t("sftp.goHome"), () -> navigate(isRemote ? "." : System.getProperty("user.home"))));
            actions.add(tool(new FileActionIcon("up"), t("sftp.goUp"), this::up));
            actions.add(tool(WorkbenchIcon.Kind.REFRESH, t("action.refresh"), this::refresh));
            transferButton = tool(new FileActionIcon(isRemote ? "download" : "upload"), t(isRemote ? "sftp.download" : "sftp.upload"), () -> transfer(this, selection(), null));
            transferButton.setName(isRemote ? "sftp.download" : "sftp.upload");
            transferButton.setEnabled(false); actions.add(transferButton);
            table.getSelectionModel().addListSelectionListener(event -> updateTransferButton());
            addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent event) { count.setVisible(getWidth() >= 340); } });
            actions.add(tool(WorkbenchIcon.Kind.MORE, t("sftp.actions"), () -> menu(actions, actions.getWidth() - 28, 27)));
            titleRow.add(actions, BorderLayout.EAST); header.add(titleRow);
            JPanel pathRow = new JPanel(new BorderLayout(2, 0)); pathRow.setOpaque(false);
            pathRow.setBorder(BorderFactory.createEmptyBorder(2, 8, 5, 7));
            JButton tree = tool(new FileActionIcon("tree"), t("sftp.directoryTree"), () -> showTree(pathRow));
            tree.setName(isRemote ? "sftp.remote.tree" : "sftp.local.tree"); pathRow.add(tree, BorderLayout.WEST);
            crumbs.setOpaque(false);
            pathHost.setOpaque(false); pathHost.add(crumbs, "crumbs"); pathHost.add(editor, "edit");
            editor.setName(isRemote ? "sftp.remote.path" : "sftp.local.path"); IdeaFormStyle.apply(editor);
            editor.addActionListener(event -> { navigate(editor.getText()); showPath(false); });
            editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "path.cancel");
            editor.getActionMap().put("path.cancel", new AbstractAction() { public void actionPerformed(ActionEvent e) { showPath(false); } });
            pathRow.add(pathHost); pathRow.add(tool(WorkbenchIcon.Kind.EDIT, t("sftp.editPath"), () -> showPath(true)), BorderLayout.EAST);
            header.add(pathRow, BorderLayout.SOUTH); header.setBorder(SwingTheme.border(0, 0, 1, 0)); add(header, BorderLayout.NORTH);
            table.setName(isRemote ? "sftp.remote.table" : "sftp.local.table"); table.setFillsViewportHeight(true);
            table.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
            table.getTableHeader().setFont(table.getFont());
            table.setRowHeight(Math.max(27, table.getFontMetrics(table.getFont()).getHeight() + 9));
            table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); table.setAutoCreateRowSorter(true);
            table.getRowSorter().setSortKeys(List.of()); table.getTableHeader().setReorderingAllowed(false);
            DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
                public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focused, int row, int col) {
                    super.getTableCellRendererComponent(t, value, selected, false, row, col);
                    setBorder(BorderFactory.createEmptyBorder(0, col == 0 ? 10 : 6, 0, 8));
                    Entry entry = model.rows.get(t.convertRowIndexToModel(row));
                    setIcon(col == 0 ? (entry.directory ? new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER) : new FileIcon()) : null);
                    if (col == 1) setText(entry.directory ? "—" : formatSize(entry.size));
                    if (col == 2) setText(entry.modified == null ? "—" : TIME.format(entry.modified));
                    setHorizontalAlignment(col == 1 ? RIGHT : LEFT);
                    setToolTipText(col == 0 ? entry.path : Objects.toString(value, "")); return this;
                }
            };
            table.setDefaultRenderer(Object.class, renderer);
            table.setDefaultRenderer(Long.class, renderer);
            table.setDefaultRenderer(Instant.class, renderer);
            if (!isRemote) table.removeColumn(table.getColumnModel().getColumn(3));
            table.getColumnModel().getColumn(0).setPreferredWidth(260);
            table.getColumnModel().getColumn(0).setMinWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(74);
            table.getColumnModel().getColumn(2).setPreferredWidth(132);
            if (isRemote) table.getColumnModel().getColumn(3).setPreferredWidth(100);
            // Name/size/date remain readable in a narrow tool window. Permissions stay in the context menu.
            table.addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent event) {
                var column = table.getColumnModel().getColumn(isRemote ? 3 : 2);
                if (isRemote) { boolean wide = table.getWidth() >= 600; column.setMinWidth(0); column.setMaxWidth(wide ? 150 : 0); column.setPreferredWidth(wide ? 100 : 0); }
                var date = table.getColumnModel().getColumn(2); date.setMinWidth(0); date.setMaxWidth(table.getWidth() < 360 ? 0 : 180); date.setPreferredWidth(table.getWidth() < 360 ? 0 : 132);
            }});
            table.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openSelection();
                }
                public void mousePressed(MouseEvent event) { context(event); }
                public void mouseReleased(MouseEvent event) { context(event); }
                private void context(MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                    menu(table, e.getX(), e.getY());
                }
            });
            bind(KeyEvent.VK_ENTER, 0, "open", this::openSelection);
            bind(KeyEvent.VK_BACK_SPACE, 0, "up", this::up);
            bind(KeyEvent.VK_F5, 0, "refresh", this::refresh);
            bind(KeyEvent.VK_F2, 0, "rename", this::rename);
            bind(KeyEvent.VK_L, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(), "path", () -> showPath(true));
            table.setDragEnabled(true); table.setDropMode(DropMode.ON); table.setTransferHandler(new FileTransfer(this));
            JScrollPane scroll = new JScrollPane(table); scroll.setBorder(BorderFactory.createEmptyBorder());
            empty.setBorder(BorderFactory.createEmptyBorder(20, 8, 20, 8)); empty.putClientProperty("JLShell.secondaryForeground", true);
            body.add(scroll, "files"); body.add(empty, "empty"); add(body);
        }
        void bind(int key, int modifiers, String name, Runnable action) {
            table.getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
            table.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent event) { action.run(); } });
        }
        void showPath(boolean edit) {
            ((CardLayout) pathHost.getLayout()).show(pathHost, edit ? "edit" : "crumbs");
            if (edit) { editor.setText(path); editor.requestFocusInWindow(); editor.selectAll(); }
        }
        void refresh() { if (path != null) navigate(path); }
        void up() {
            if (path == null) return;
            String parent = isRemote ? (path.equals("/") ? "/" : path.substring(0, Math.max(1, path.lastIndexOf('/'))))
                    : Optional.ofNullable(Path.of(path).getParent()).orElse(Path.of(path)).toString();
            navigate(parent);
        }
        void navigate(String target) {
            if (closed || isRemote && !connected || target == null || target.isBlank()) return;
            dismissPopups();
            requestedPath = target;
            long request = ++sequence; count.setText(t("sftp.loading"));
            readDirectory(isRemote, target).whenComplete((listing, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || request != sequence) return;
                if (error != null) { requestedPath = path; count.setText(t("sftp.error")); failure(error); return; }
                path = listing.path; model.rows = listing.entries; model.fireTableDataChanged(); breadcrumbs();
                count.setText(i18n.get("sftp.itemCount", listing.entries.size()));
                empty.setText(t("sftp.empty")); ((CardLayout) body.getLayout()).show(body, listing.entries.isEmpty() ? "empty" : "files");
            }));
        }
        void updateTransferButton() { transferButton.setEnabled(connected && !transferring && selection().stream().anyMatch(entry -> !entry.directory)); }
        List<Entry> selection() { return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).mapToObj(model.rows::get).toList(); }
        void openSelection() { var selected = selection(); if (selected.size() == 1 && selected.getFirst().directory) navigate(selected.getFirst().path); }
        void breadcrumbs() {
            crumbs.removeAll(); editor.setText(path);
            JButton overflow = new JButton("…"); overflow.putClientProperty("JButton.buttonType", "toolBarButton");
            overflow.setToolTipText(t("sftp.ancestors")); overflow.setFocusable(false);
            overflow.setPreferredSize(new Dimension(27, 27));
            overflow.addActionListener(event -> {
                List<IdeaListPopup.Row> rows = new ArrayList<>();
                for (String ancestor : ancestors(path, isRemote)) rows.add(menuRow(ancestor, new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER), null, true, () -> navigate(ancestor)));
                showMenu(rows, 260, IdeaListPopup.Style.LIST, overflow, 0, overflow.getHeight());
            });
            crumbs.add(overflow);
            for (String ancestor : ancestors(path, isRemote)) {
                String name = isRemote ? (ancestor.equals("/") ? "/" : ancestor.substring(ancestor.lastIndexOf('/') + 1))
                        : Optional.ofNullable(Path.of(ancestor).getFileName()).map(Object::toString).orElse(ancestor);
                JButton segment = new JButton(name); segment.putClientProperty("JButton.buttonType", "toolBarButton");
                segment.setFont(table.getFont()); segment.setMargin(new Insets(3, 5, 3, 3)); segment.setFocusable(false); segment.setToolTipText(ancestor);
                segment.addActionListener(event -> showTree(segment, ancestor, ancestor)); crumbs.add(segment);
                JLabel separator = new JLabel("/"); separator.setHorizontalAlignment(SwingConstants.CENTER);
                separator.setForeground(SwingTheme.color("secondary")); separator.putClientProperty("JLShell.secondaryForeground", true);
                // The root already displays '/' and the final directory needs no trailing separator.
                separator.putClientProperty("sftp.separator", !name.equals("/") && !ancestor.equals(path));
                crumbs.add(separator);
            }
            crumbs.revalidate(); crumbs.repaint();

        }
        void showTree(Component anchor) {
            if (path == null) return;
            showTree(anchor, isRemote ? "/" : Path.of(path).getRoot().toString(), path);
        }
        void showTree(Component anchor, String rootPath, String selectedPath) {
            dismissPopups();
            JPanel content = new JPanel(new BorderLayout()); content.setBackground(SwingTheme.color("popup"));
            IdeaComponentPopup popup = new IdeaComponentPopup(content); popup.setName("sftp.directoryPopup");
            directoryPopups.add(popup);
            FolderNode root = new FolderNode(rootPath, rootPath); root.add(new DefaultMutableTreeNode(t("sftp.loading")));
            DefaultTreeModel treeModel = new DefaultTreeModel(root); JTree tree = new JTree(treeModel);
            tree.setBackground(SwingTheme.color("popup"));
            tree.putClientProperty("FlatLaf.style", "selectionArc: 8; selectionInsets: 1,2,1,2");
            tree.setToggleClickCount(0);
            tree.setName("sftp.directoryTree"); tree.setRootVisible(true); tree.setShowsRootHandles(true); tree.setRowHeight(27);
            tree.setCellRenderer(new DefaultTreeCellRenderer() {
                public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
                    super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, false);
                    setBackgroundNonSelectionColor(SwingTheme.color("popup"));
                    setIcon(new WorkbenchIcon(WorkbenchIcon.Kind.FOLDER)); setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8)); return this;
                }
            });
            tree.addTreeWillExpandListener(new TreeWillExpandListener() {
                public void treeWillExpand(TreeExpansionEvent e) { if (e.getPath().getLastPathComponent() instanceof FolderNode node) loadNode(node, treeModel, tree, popup, null); }
                public void treeWillCollapse(TreeExpansionEvent e) {}
            });
            Runnable choose = () -> { if (tree.getLastSelectedPathComponent() instanceof FolderNode node) { popup.close(); navigate(node.path); } };
            tree.addMouseListener(new MouseAdapter() {
                public void mouseReleased(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                    TreePath hit = tree.getPathForLocation(event.getX(), event.getY());
                    Rectangle bounds = hit == null ? null : tree.getPathBounds(hit);
                    // Expand controls are outside the renderer bounds. A row click selects that exact path.
                    if (bounds != null && bounds.contains(event.getPoint()) && hit.getLastPathComponent() instanceof FolderNode) {
                        tree.setSelectionPath(hit); choose.run();
                    }
                }
            });
            tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "choose");
            tree.getActionMap().put("choose", new AbstractAction() { public void actionPerformed(ActionEvent e) { choose.run(); } });
            JLabel label = new JLabel(rootPath, new FileActionIcon("tree"), JLabel.LEFT); label.setToolTipText(rootPath); label.setBorder(BorderFactory.createEmptyBorder(6, 7, 8, 7)); content.add(label, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(tree); scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setPreferredSize(new Dimension(Math.max(260, Math.min(420, getWidth() - 20)), Math.max(140, Math.min(330, SwingSftpBrowser.this.getHeight() - 100)))); content.add(scroll);
            popup.show(anchor, 0, anchor.getHeight()); loadNode(root, treeModel, tree, popup, selectedPath); tree.requestFocusInWindow();
        }
        void loadNode(FolderNode node, DefaultTreeModel treeModel, JTree tree, IdeaComponentPopup popup, String revealTarget) {
            if (node.loading || node.loaded) return; node.loading = true;
            readDirectory(isRemote, node.path).whenComplete((listing, error) -> SwingUtilities.invokeLater(() -> {
                if (closed || !popup.isShowing()) return;
                node.loading = false; node.removeAllChildren(); node.loaded = true;
                if (error != null) { node.add(new DefaultMutableTreeNode(t("sftp.error"))); failure(error); }
                else for (Entry entry : listing.entries) if (entry.directory) {
                    FolderNode child = new FolderNode(entry.path, entry.name); child.add(new DefaultMutableTreeNode(t("sftp.loading"))); node.add(child);
                }
                treeModel.nodeStructureChanged(node); TreePath nodePath = new TreePath(node.getPath()); tree.expandPath(nodePath);
                if (revealTarget != null) {
                    if (node.path.equals(revealTarget)) { tree.setSelectionPath(nodePath); tree.scrollPathToVisible(nodePath); }
                    else for (int i = 0; i < node.getChildCount(); i++) if (node.getChildAt(i) instanceof FolderNode child && ancestors(revealTarget, isRemote).contains(child.path)) { loadNode(child, treeModel, tree, popup, revealTarget); break; }
                }
            }));
        }
        void menu(Component anchor, int x, int y) {
            var selected = selection();
            List<IdeaListPopup.Row> rows = new ArrayList<>();
            boolean editable = path != null && (!isRemote || connected);
            rows.add(menuRow(t(isRemote ? "sftp.download" : "sftp.upload"), new FileActionIcon(isRemote ? "download" : "upload"), null,
                    connected && !transferring && selected.stream().anyMatch(e -> !e.directory), () -> transfer(this, selected, null)));
            rows.add(menuRow(t("sftp.newFolder"), new WorkbenchIcon(WorkbenchIcon.Kind.ADD), "", editable, this::mkdir));
            rows.add(menuRow(t("sftp.rename"), new WorkbenchIcon(WorkbenchIcon.Kind.EDIT), null, editable && selected.size() == 1, this::rename));
            rows.add(menuRow(t("sftp.delete"), new WorkbenchIcon(WorkbenchIcon.Kind.DELETE), null, editable && !selected.isEmpty(), this::delete));
            rows.add(menuRow(t("sftp.copyPath"), null, null, path != null,
                    () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected.isEmpty() ? Objects.toString(path, "") : selected.getFirst().path), null)));
            if (isRemote) {
                rows.add(menuRow(t("sftp.followTerminal"), follow ? new WorkbenchIcon(WorkbenchIcon.Kind.CHECK) : null, "", connected, () -> {
                    setFollow(!follow);
                }));
                if (selected.size() == 1) rows.add(menuRow(t("sftp.permissions") + ": " + selected.getFirst().permissions, null, null, false, null));
            }
            showMenu(rows, 190, IdeaListPopup.Style.CONTEXT, anchor, x, y);
        }

        void mkdir() {
            if (closed || isRemote && !connected) return;
            String name = ask(t("sftp.newFolder"), ""); if (name == null || path == null) return;
            String directory = path;
            mutation(() -> isRemote ? service.createDirectory(session, join(directory, name), false) : CompletableFuture.runAsync(() -> {
                try { Files.createDirectory(Path.of(directory).resolve(name)); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }));
        }
        void rename() {
            if (closed || isRemote && !connected) return;
            var selected = selection(); if (selected.size() != 1) return; Entry entry = selected.getFirst();
            String name = ask(t("sftp.rename"), entry.name); if (name == null || name.equals(entry.name)) return;
            String directory = path;
            mutation(() -> isRemote ? service.rename(session, entry.path, join(directory, name)) : CompletableFuture.runAsync(() -> {
                try { Files.move(Path.of(entry.path), Path.of(directory).resolve(name)); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }));
        }
        String ask(String title, String initial) {
            String value = (String) JOptionPane.showInputDialog(SwingSftpBrowser.this, title, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
            if (value == null || value.isBlank()) return null;
            value = value.trim();
            if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) { status(t("sftp.invalidName")); return null; }
            return value;
        }
        void delete() {
            if (closed || isRemote && !connected) return;
            var selected = selection(); if (selected.isEmpty()) return;
            String confirmation = selected.size() == 1 ? i18n.get("sftp.delete.confirm", selected.getFirst().name) : i18n.get("sftp.delete.confirmMulti", selected.size());
            if (JOptionPane.showConfirmDialog(SwingSftpBrowser.this, confirmation, t("sftp.delete"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            mutation(() -> {
                CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
                for (Entry entry : selected) result = result.thenCompose(v -> isRemote ? service.delete(session, entry.path, entry.directory) : CompletableFuture.runAsync(() -> {
                    try {
                        Files.walkFileTree(Path.of(entry.path), new SimpleFileVisitor<>() {
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error) throws java.io.IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
                        });
                    } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
                }));
                return result;
            });
        }
        void mutation(Supplier<CompletableFuture<Void>> operation) {
            count.setText(t("sftp.loading"));
            CompletableFuture.supplyAsync(operation).thenCompose(f -> f).whenComplete((v, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return; if (error != null) failure(error); refresh();
            }));
        }
    }
    private static List<String> ancestors(String path, boolean remote) {
        if (!remote) {
            List<String> result = new ArrayList<>(); Path current = Path.of(path).toAbsolutePath().normalize();
            while (current != null) { result.addFirst(current.toString()); current = current.getParent(); } return result;
        }
        List<String> result = new ArrayList<>(); result.add("/"); String current = "";
        for (String part : path.split("/")) if (!part.isEmpty()) { current += "/" + part; result.add(current); } return result;
    }
    /** Keep current folders visible. Earlier ancestors remain reachable through the leading menu. */
    private static final class BreadcrumbLayout implements LayoutManager {
        public void addLayoutComponent(String name, Component component) {}
        public void removeLayoutComponent(Component component) {}
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(40, 27); }
        public Dimension preferredLayoutSize(Container parent) { return new Dimension(160, 27); }
        private static int separatorWidth(Component component) { return Boolean.TRUE.equals(((JComponent) component).getClientProperty("sftp.separator")) ? 9 : 0; }
        public void layoutContainer(Container parent) {
            int count = parent.getComponentCount(); if (count == 0) return;
            int width = parent.getWidth(), used = 0, first = count - 2;
            int maxSegment = Math.max(40, width - 27);
            for (int i = count - 2; i >= 1; i -= 2) {
                int pair = Math.min(maxSegment, parent.getComponent(i).getPreferredSize().width) + separatorWidth(parent.getComponent(i + 1));
                if (i < count - 2 && used + pair + (i > 1 ? 27 : 0) > width) break;
                used += pair; first = i;
            }
            boolean overflow = first > 1; int x = overflow ? 27 : 0;
            Component menu = parent.getComponent(0); menu.setVisible(overflow); menu.setBounds(0, 0, 27, 27);
            for (int i = 1; i < count; i += 2) {
                Component segment = parent.getComponent(i), separator = parent.getComponent(i + 1);
                boolean visible = i >= first; segment.setVisible(visible); separator.setVisible(visible && separatorWidth(separator) > 0);
                if (visible) {
                    int separatorWidth = separatorWidth(separator);
                    int segmentWidth = Math.min(Math.min(maxSegment, segment.getPreferredSize().width), Math.max(12, width - x - separatorWidth));
                    segment.setBounds(x, 0, segmentWidth, 27); x += segmentWidth;
                    separator.setBounds(x, 0, separatorWidth, 27); x += separatorWidth;
                }
            }
        }
    }
    private static final class FolderNode extends DefaultMutableTreeNode {
        final String path; boolean loading, loaded;
        FolderNode(String path, String name) { super(name); this.path = path; }
    }
    private final class FileModel extends AbstractTableModel {
        List<Entry> rows = List.of();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 4; }
        public String getColumnName(int column) { return t(switch (column) { case 0 -> "sftp.name"; case 1 -> "sftp.size"; case 2 -> "sftp.modified"; default -> "sftp.permissions"; }); }
        public Class<?> getColumnClass(int column) { return switch (column) { case 1 -> Long.class; case 2 -> Instant.class; default -> String.class; }; }
        public Object getValueAt(int row, int column) {
            Entry entry = rows.get(row);
            return switch (column) { case 0 -> entry.name; case 1 -> entry.directory ? null : entry.size; case 2 -> entry.modified; default -> entry.permissions; };
        }
    }
    private IdeaListPopup.Row menuRow(String title, Icon icon, String group, boolean enabled, Runnable action) {
        return new IdeaListPopup.Row(title, null, icon, null, group, enabled, List.of(), action);
    }
    private void showMenu(List<IdeaListPopup.Row> rows, int width, IdeaListPopup.Style style, Component anchor, int x, int y) {
        dismissPopups();
        IdeaListPopup popup = new IdeaListPopup(rows, width, false, "", style);
        popups.add(popup); popup.show(anchor, x, y);
    }
    private void transfer(Browser from, List<Entry> entries, String destination) {
        List<Entry> files = entries.stream().filter(e -> !e.directory).toList();
        Browser target = from.isRemote ? local : remote;
        if (closed || !connected || transferring || files.isEmpty() || target.path == null) return;
        String targetPath = destination == null ? target.path : destination;
        boolean overwrite = destination != null || files.stream().anyMatch(e -> target.model.rows.stream().anyMatch(t -> t.name.equals(e.name)));
        if (overwrite && JOptionPane.showConfirmDialog(this, t("sftp.overwrite"), t("sftp.transfer"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        transferring = true; local.updateTransferButton(); remote.updateTransferButton(); cancelled = false; progress.setValue(0); progress.setVisible(true); cancel.setEnabled(true); cancel.setVisible(true);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Entry file : files) chain = chain.thenComposeAsync(ignored -> {
            if (cancelled || closed) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            SwingUtilities.invokeLater(() -> { if (!closed) status(file.name); });
            TransferRequest request = from.isRemote ? new TransferRequest(Path.of(targetPath).resolve(file.name), file.path, TransferResumeMode.OVERWRITE, 64 * 1024)
                    : new TransferRequest(Path.of(file.path), join(targetPath, file.name), TransferResumeMode.OVERWRITE, 64 * 1024);
            TransferProgressListener listener = new TransferProgressListener() {
                public boolean isCancelled() { return cancelled || closed; }
                public void onProgress(TransferProgress p) { SwingUtilities.invokeLater(() -> { if (!closed) { progress.setValue((int) (p.progressRatio() * 1000)); status(file.name + " · " + formatSize(p.transferredBytes()) + " / " + formatSize(p.totalBytes())); } }); }
            };
            return from.isRemote ? service.download(session, request, listener) : service.upload(session, request, listener);
        });
        chain.whenComplete((v, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return; transferring = false; local.updateTransferButton(); remote.updateTransferButton(); progress.setVisible(false); cancel.setVisible(false);
            if (cancelled) status(t("sftp.cancelled")); else if (error != null) failure(error); else status(t("status.transferCompleted")); target.refresh();
        }));
    }
    private record DragSelection(Browser source, List<Entry> files) {}
    private static final DataFlavor INTERNAL = new DataFlavor(DragSelection.class, "JLShell files");
    private final class FileTransfer extends TransferHandler {
        final Browser browser; FileTransfer(Browser browser) { this.browser = browser; }
        public int getSourceActions(JComponent component) { return COPY; }
        protected Transferable createTransferable(JComponent component) {
            DragSelection data = new DragSelection(browser, browser.selection());
            return new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{INTERNAL}; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return INTERNAL.equals(flavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException { if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return data; }
            };
        }
        public boolean canImport(TransferSupport support) { return !closed && connected && !transferring && browser.path != null && (support.isDataFlavorSupported(INTERNAL) || browser.isRemote && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)); }
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                String destination = null;
                if (support.isDrop() && support.getDropLocation() instanceof JTable.DropLocation drop && drop.getRow() >= 0) {
                    Entry entry = browser.model.rows.get(browser.table.convertRowIndexToModel(drop.getRow())); if (entry.directory) destination = entry.path;
                }
                if (support.isDataFlavorSupported(INTERNAL)) {
                    DragSelection data = (DragSelection) support.getTransferable().getTransferData(INTERNAL);
                    if ((data.source != local && data.source != remote) || data.source.isRemote == browser.isRemote) return false; transfer(data.source, data.files, destination);
                } else {
                    @SuppressWarnings("unchecked") var files = (List<java.io.File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    transfer(local, files.stream().filter(java.io.File::isFile).map(f -> new Entry(f.toString(), f.getName(), false, f.length(), null, "")).toList(), destination);
                }
                return true;
            } catch (Exception e) { failure(e); return false; }
        }
    }
    private record FileActionIcon(String action) implements Icon {
        public int getIconWidth() { return 16; } public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y);
            g.setColor(c.isEnabled() ? SwingTheme.color("icon") : UIManager.getColor("Label.disabledForeground"));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (action.equals("tree")) {
                g.drawRoundRect(2, 1, 4, 3, 1, 1); g.drawLine(4, 4, 4, 13);
                g.drawLine(4, 7, 9, 7); g.drawLine(4, 13, 9, 13);
                g.drawRoundRect(9, 5, 5, 4, 1, 1); g.drawRoundRect(9, 11, 5, 4, 1, 1);
            } else if (action.equals("home")) {
                g.drawPolyline(new int[]{2, 8, 14}, new int[]{7, 2, 7}, 3); g.drawPolyline(new int[]{4, 4, 12, 12}, new int[]{6, 14, 14, 6}, 4); g.drawRect(7, 9, 3, 5);
            } else if (action.equals("up")) {
                // Balanced parent-directory arrow, centred in the same 16px icon box.
                g.draw(new java.awt.geom.Line2D.Double(8, 13, 8, 3));
                java.awt.geom.Path2D arrow = new java.awt.geom.Path2D.Double();
                arrow.moveTo(3.5, 7.5); arrow.lineTo(8, 3); arrow.lineTo(12.5, 7.5);
                g.draw(arrow);
            } else {
                boolean down = action.equals("download");
                g.drawLine(8, 3, 8, 11);
                g.drawLine(8, down ? 11 : 3, 4, down ? 7 : 7); g.drawLine(8, down ? 11 : 3, 12, down ? 7 : 7);
                if (!action.equals("up")) { g.drawPolyline(new int[]{2, 2, 14, 14}, new int[]{11, 14, 14, 11}, 4); }
            }
            g.dispose();
        }
    }
    private static final class FileIcon implements Icon {
        public int getIconWidth() { return 16; } public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y); g.setColor(SwingTheme.color("icon"));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawRoundRect(4, 2, 9, 12, 2, 2); g.drawLine(6, 6, 10, 6); g.drawLine(6, 9, 10, 9); g.dispose();
        }
    }
    private void dismissPopups() {
        popups.forEach(popup -> popup.setVisible(false)); popups.clear();
        directoryPopups.forEach(IdeaComponentPopup::close); directoryPopups.clear();
    }
    public void disconnected() {
        connected = false; cancelled = true; followButton.setEnabled(false);
        local.updateTransferButton(); remote.updateTransferButton();
        remote.sequence++; remote.count.setText(t("sftp.disconnected")); status(t("sftp.disconnected"));
        dismissPopups();
    }
    public void close() { if (closed) return; closed = true; cancelled = true; unbind.run(); dismissPopups(); local.sequence++; remote.sequence++; }
}
