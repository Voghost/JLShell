package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Native settings navigation, cached lazy pages and apply/cancel lifecycle. */
public final class SwingPreferencesDialog {
    public static final class Page {
        final int index;
        final String title, group, searchText;
        final java.util.function.Supplier<java.util.concurrent.CompletableFuture<java.util.function.Supplier<JComponent>>> loader;
        java.util.function.Supplier<JComponent> nativeView;
        java.util.concurrent.CompletableFuture<Page> loading;
        public Page(int index, String title, String group, String searchText,
                    java.util.function.Supplier<java.util.concurrent.CompletableFuture<java.util.function.Supplier<JComponent>>> loader) {
            this.index = index; this.title = title; this.group = group;
            this.searchText = (title + " " + group + " " + searchText).toLowerCase(Locale.ROOT);
            this.loader = loader;
        }
        int index() { return index; }
        String title() { return title; }
        String group() { return group; }
        String searchText() { return searchText; }
        java.util.function.Supplier<JComponent> nativeView() { return nativeView; }
        java.util.concurrent.CompletableFuture<Page> load() {
            return loader.get().thenApply(factory -> { nativeView = factory; return this; });
        }
    }
    private record Entry(String title, Page page) { public String toString() { return title; } }

    private final JDialog dialog;
    private final JTree tree = new JTree();
    private final JTextField search = new JTextField();
    private final JLabel breadcrumb = new JLabel();
    private final JPanel pageTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private final JButton apply;
    private final JButton ok;
    private final JPanel contentHost = new JPanel(new BorderLayout());
    private final java.util.Map<Integer,JComponent> nativePages = new java.util.HashMap<>();
    private final List<Page> pages;
    private final Timer filterTimer;
    private Page selected;
    private final List<Page> history = new ArrayList<>();
    private int historyIndex = -1;
    private boolean restoringHistory;
    private final JButton back = new JButton(historyIcon(false));
    private final JButton forward = new JButton(historyIcon(true));
    private boolean busy;
    private boolean disposed;
    private final JPanel inputBlocker = new JPanel();
    private Component busyFocus;
    private final Window owner;
    private final ThemeService themes;
    private final String pageLoadFailed;

    private SwingPreferencesDialog(List<Page> pages, int initial, I18nService i18n,
                                   ThemeService themes, boolean applyDisabled,
                                   java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> applyAction,
                                   java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> okAction, Runnable closed) {
        this.pages = pages;
        this.themes = themes;
        this.pageLoadFailed = i18n.get("settings.page.loadFailed");
        owner = SwingDialogChrome.owner();
        // AWT 应用模态过滤器会阻止 JFXPanel 中的 Glass 下拉弹窗接收鼠标。
        // 使用禁用宿主窗口实现模态，FX 嵌套事件循环仍保持 showAndWait 的调用顺序。
        dialog = new SwingModalDialog(owner, i18n.get("preferences.title") + " — JLShell");
        dialog.setName("jlshell.settings");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        inputBlocker.setOpaque(false);
        inputBlocker.setFocusable(true);
        inputBlocker.setFocusTraversalKeysEnabled(false);
        inputBlocker.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        inputBlocker.addMouseListener(new MouseAdapter() {});
        inputBlocker.addMouseMotionListener(new MouseMotionAdapter() {});
        inputBlocker.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) { event.consume(); }
            @Override public void keyTyped(KeyEvent event) { event.consume(); }
            @Override public void keyReleased(KeyEvent event) { event.consume(); }
        });
        dialog.setGlassPane(inputBlocker);
        inputBlocker.setVisible(false);
        JPanel windowHeader=SwingDialogChrome.configure(dialog);
        windowHeader.setPreferredSize(new Dimension(0, com.formdev.flatlaf.util.SystemInfo.isMacOS ? 28 : 34));
        JPanel navigation = new JPanel(new BorderLayout(0, 10));
        navigation.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        search.setName("settings.search");
        search.putClientProperty("JTextField.leadingIcon", new WorkbenchIcon(WorkbenchIcon.Kind.SEARCH));
        search.putClientProperty("JTextField.placeholderText", i18n.get("settings.search"));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(220, 26));
        IdeaFormStyle.apply(search);
        navigation.add(search, BorderLayout.NORTH);
        tree.setName("settings.tree");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        styleTreeSelection();
        tree.setRowHeight(Math.max(22, tree.getFontMetrics(tree.getFont()).getHeight() + 6));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, false);
                Entry entry = (Entry) ((DefaultMutableTreeNode) value).getUserObject();
                setIcon(null);
                setBackgroundNonSelectionColor(UIManager.getColor("JLShell.formBackground"));
                setText(entry.title());
                putClientProperty("html.disable", true);
                setFont(t.getFont().deriveFont(entry.page() == null ? Font.BOLD : Font.PLAIN));
                setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 4));
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) return;
            Entry entry = (Entry) node.getUserObject();
            if (entry.page() != null) select(entry.page());
            else if (node.getChildCount() > 0) tree.setSelectionPath(new TreePath(((DefaultMutableTreeNode) node.getChildAt(0)).getPath()));
        });
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        navigation.add(treeScroll);

        JPanel editor = new JPanel(new BorderLayout());
        breadcrumb.setName("settings.breadcrumb");
        breadcrumb.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 8));
        breadcrumb.setFont(breadcrumb.getFont().deriveFont(Font.BOLD, Math.max(11,breadcrumb.getFont().getSize2D()-1)));
        JPanel pageHeader = new JPanel(new BorderLayout());
        pageTabs.setName("settings.pageTabs");
        pageHeader.add(breadcrumb, BorderLayout.WEST);
        pageHeader.add(pageTabs, BorderLayout.CENTER);
        pageHeader.setName("settings.pageHeader");
        pageHeader.setOpaque(false); pageTabs.setOpaque(false); breadcrumb.setOpaque(false);
        JPanel historyToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        historyToolbar.setOpaque(false);
        configureHistoryButton(back,"settings.back",i18n.get("settings.navigation.back"),()->navigateHistory(-1));
        configureHistoryButton(forward,"settings.forward",i18n.get("settings.navigation.forward"),()->navigateHistory(1));
        historyToolbar.add(back); historyToolbar.add(forward);
        historyToolbar.setBorder(BorderFactory.createEmptyBorder(0,0,0,8));
        pageHeader.add(historyToolbar,BorderLayout.EAST);
        breadcrumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        breadcrumb.setToolTipText(i18n.get("settings.navigation.pages"));
        breadcrumb.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                JPopupMenu menu=new JPopupMenu();
                for(Page page:pages) if(selected==null || java.util.Objects.equals(page.group(),selected.group())) {
                    JMenuItem item=new JMenuItem(page.title());
                    item.addActionListener(action->revealPage(page));menu.add(item);
                }
                menu.show(breadcrumb,0,breadcrumb.getHeight());
            }
        });
        dialog.getRootPane().registerKeyboardAction(event->navigateHistory(-1),KeyStroke.getKeyStroke("alt LEFT"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().registerKeyboardAction(event->navigateHistory(1),KeyStroke.getKeyStroke("alt RIGHT"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        editor.add(pageHeader, BorderLayout.NORTH);
        contentHost.setName("settings.contentHost");
        contentHost.setBackground(UIManager.getColor("JLShell.formBackground"));
        editor.setBackground(UIManager.getColor("JLShell.formBackground"));
        pageHeader.setBackground(UIManager.getColor("JLShell.formBackground"));
        navigation.setBackground(UIManager.getColor("JLShell.formBackground"));
        editor.add(contentHost);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, editor);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setDividerLocation(240);
        split.setResizeWeight(0);
        navigation.setMinimumSize(new Dimension(190, 0));
        editor.setMinimumSize(new Dimension(480, 0));

        JButton cancel = new JButton(i18n.get("settings.cancel"));
        cancel.setName("settings.cancel");
        apply = new JButton(i18n.get("preferences.apply"));
        apply.setName("settings.apply");
        apply.setEnabled(!applyDisabled);
        ok = new JButton(i18n.get("settings.ok"));
        ok.setName("settings.ok");
        ok.putClientProperty("JButton.buttonType", "default");
        dialog.getRootPane().setDefaultButton(ok);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        footer.setName("settings.footer");
        footer.setBorder(SwingTheme.border(1, 0, 0, 0));
        for (JButton button : List.of(cancel, apply, ok)) {
            button.putClientProperty("jlshell.primary",button==ok);
            IdeaFormStyle.apply(button);
            footer.add(button);
        }
        cancel.addActionListener(e -> { if (!busy) dialog.dispose(); });
        apply.addActionListener(e -> runAction(applyAction, false));
        ok.addActionListener(e -> runAction(okAction, true));
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (!busy) dialog.dispose(); }
            public void windowClosed(WindowEvent e) {
                disposed = true;
                filterTimer.stop();
                contentHost.removeAll();
                nativePages.values().forEach(page -> {
                    if (page instanceof AutoCloseable resource) try { resource.close(); }
                    catch (Exception error) { org.slf4j.LoggerFactory.getLogger(SwingPreferencesDialog.class).warn("Cannot close settings page", error); }
                });
                nativePages.clear();
                closed.run();
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> { if (!busy) dialog.dispose(); },
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().registerKeyboardAction(e -> { if (!busy) search.requestFocusInWindow(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "tree");
        search.getActionMap().put("tree", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { tree.requestFocusInWindow(); }
        });
        filterTimer = new Timer(150, e -> rebuildTree(selected == null ? initial : selected.index()));
        filterTimer.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterTimer.restart(); }
            public void removeUpdate(DocumentEvent e) { filterTimer.restart(); }
            public void changedUpdate(DocumentEvent e) { filterTimer.restart(); }
        });
        dialog.add(split, BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(900, 640));
        Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
        dialog.setSize(Math.min(1060, screen.width - 80), Math.min(760, screen.height - 80));
        dialog.setLocationRelativeTo(owner);
        rebuildTree(initial);
    }

    private void runAction(java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> action, boolean close) {
        if (busy) return;
        busy = true;
        busyFocus = dialog.getFocusOwner();
        inputBlocker.setVisible(true);
        inputBlocker.requestFocusInWindow();
        java.util.concurrent.CompletableFuture<Void> result;
        try { result = action.get(); }
        catch (RuntimeException error) { result = java.util.concurrent.CompletableFuture.failedFuture(error); }
        result.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            busy = false;
            inputBlocker.setVisible(false);
            if (error != null) {
                org.slf4j.LoggerFactory.getLogger(SwingPreferencesDialog.class).warn("Cannot apply settings", error);
                JLabel failure = new JLabel(pageLoadFailed);
                contentHost.add(failure, BorderLayout.SOUTH);
            } else {
                if (owner instanceof RootPaneContainer host
                        && host.getRootPane().getClientProperty("jlshell.workbench") instanceof SwingWorkbench workbench) {
                    workbench.applyTheme(themes.currentTheme(), themes.accentColor().color(), themes.uiFontFamily(), themes.uiFontSize());
                }
                refreshTheme();
                if (close) dialog.dispose();
            }
            if (!close && busyFocus != null) busyFocus.requestFocusInWindow();
            busyFocus = null;
        }));
    }

    private void refreshTheme() {
        SwingTheme.update(dialog);
        // Only the selected page is attached to contentHost. Retained pages still own their
        // editors and unsaved values, so update their LAF delegates rather than recreating them.
        for (JComponent page : nativePages.values()) {
            if (page.getParent() == null) SwingTheme.update(page);
        }
        styleTreeSelection();
        dialog.revalidate();
        dialog.repaint();
    }

    private void select(Page page) {
        if (selected == page) return;
        selected = page;
        if(!restoringHistory) {
            while(history.size()>historyIndex+1)history.removeLast();
            history.add(page);historyIndex=history.size()-1;
        }
        updateHistoryButtons();
        breadcrumb.setText(page.group() == null ? page.title() : page.group() + "  ›  " + page.title());
        contentHost.removeAll();
        if (page.nativeView() != null) {
            attachPage(page);
        } else {
            JProgressBar progress = new JProgressBar();
            progress.setIndeterminate(true);
            progress.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
            contentHost.add(progress, BorderLayout.NORTH);
            contentHost.revalidate(); contentHost.repaint();
            pageTabs.removeAll(); pageTabs.revalidate(); pageTabs.repaint();
            if (page.loading == null) page.loading = page.load();
            page.loading.whenComplete((ready, failure) -> SwingUtilities.invokeLater(() -> {
                if (disposed || selected != page) return;
                if (failure != null) {
                    org.slf4j.LoggerFactory.getLogger(SwingPreferencesDialog.class)
                            .warn("Unable to initialize settings page {}", page.title(), failure);
                    contentHost.removeAll();
                    contentHost.add(new JLabel(pageLoadFailed));
                    contentHost.revalidate(); contentHost.repaint();
                    return;
                }
                attachPage(page);
            }));
        }
    }

    private void attachPage(Page page) {
        contentHost.removeAll();
        JComponent nativePage = nativePages.computeIfAbsent(page.index(), ignored -> page.nativeView().get());
        nativePage.setName("settings.nativePage." + page.index());
        contentHost.add(nativePage);
        SwingUtilities.invokeLater(() -> { if (selected == page && !disposed) resetInitialScroll(nativePage); });
        contentHost.revalidate(); contentHost.repaint();
        refreshPageTabs(page);
    }

    private void revealPage(Page page) {
        search.setText(""); filterTimer.stop(); rebuildTree(page.index());
    }

    private void navigateHistory(int step) {
        int target=historyIndex+step;
        if(busy || target<0 || target>=history.size())return;
        historyIndex=target;restoringHistory=true;
        try {revealPage(history.get(target));} finally {restoringHistory=false;updateHistoryButtons();}
    }

    private void updateHistoryButtons() {
        back.setEnabled(historyIndex>0);forward.setEnabled(historyIndex>=0 && historyIndex<history.size()-1);
    }

    private static void configureHistoryButton(JButton button,String name,String tooltip,Runnable action) {
        button.setName(name);button.setToolTipText(tooltip);button.getAccessibleContext().setAccessibleName(tooltip);
        button.putClientProperty("JButton.buttonType","toolBarButton");button.setFocusable(false);
        button.setPreferredSize(new Dimension(24,24));button.addActionListener(event->action.run());
    }

    private static Icon historyIcon(boolean forward) {
        return new Icon() {
            public int getIconWidth(){return 16;}
            public int getIconHeight(){return 16;}
            public void paintIcon(Component component,Graphics graphics,int x,int y) {
                Graphics2D g=(Graphics2D)graphics.create();
                try {
                    g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(UIManager.getColor(component.isEnabled()?"Label.foreground":"Label.disabledForeground"));
                    g.setStroke(new BasicStroke(1f));
                    if(forward){g.drawLine(3,8,12,8);g.drawLine(8,4,12,8);g.drawLine(8,12,12,8);}
                    else {g.drawLine(3,8,12,8);g.drawLine(3,8,7,4);g.drawLine(3,8,7,12);}
                } finally {g.dispose();}
            }
        };
    }

    private static void resetInitialScroll(Component component) {
        if (component instanceof JScrollPane scroll) scroll.getVerticalScrollBar().setValue(0);
        if (component instanceof Container parent) for (Component child : parent.getComponents()) resetInitialScroll(child);
    }

    private void refreshPageTabs(Page page) {
        pageTabs.removeAll();
        JComponent content = nativePages.get(page.index());
        if (content != null && content.getClientProperty("jlshell.header") instanceof JComponent header) pageTabs.add(header);
        pageTabs.revalidate();
        pageTabs.repaint();
    }

    private void styleTreeSelection() {
        tree.setRowHeight(Math.max(22, tree.getFontMetrics(tree.getFont()).getHeight() + 6));
        tree.putClientProperty("FlatLaf.style", java.util.Map.of(
                "background", UIManager.getColor("JLShell.formBackground"),
                "selectionBackground", SwingTheme.color("popupSelection"),
                "selectionInactiveBackground", SwingTheme.color("popupSelection"),
                "selectionInsets", new Insets(0, 0, 0, 4), "selectionArc", 0));
    }

    private void rebuildTree(int preferred) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Entry("", null));
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode preferredNode = null;
        int preferredScore = -1;
        for (Page page : pages) {
            if (!page.searchText().contains(query)) continue;
            DefaultMutableTreeNode parent = root;
            if (page.group() != null) {
                for (int i = 0; i < root.getChildCount(); i++) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
                    Entry entry = (Entry) node.getUserObject();
                    if (entry.page() == null && entry.title().equals(page.group())) { parent = node; break; }
                }
                if (parent == root) {
                    parent = new DefaultMutableTreeNode(new Entry(page.group(), null));
                    root.add(parent);
                }
            }
            DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(new Entry(page.title(), page));
            parent.add(leaf);
            // 分类名称的命中优先于其他页面帮助文本中的提及（如 API 文案也含“插件”）。
            int score = !query.isBlank() && page.title().toLowerCase(Locale.ROOT).contains(query)
                    ? 3 : page.index() == preferred ? 2 : 1;
            if (score > preferredScore) { preferredNode = leaf; preferredScore = score; }
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        if (preferredNode != null) tree.setSelectionPath(new TreePath(preferredNode.getPath()));
    }

    public static java.util.concurrent.CompletableFuture<Void> showAsync(List<Page> pages, int initial,
            I18nService i18n, ThemeService themes, com.jlshell.plugin.api.ui.ObservableValue<Boolean> canApply,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> applyAction,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> okAction) {
        var result = new java.util.concurrent.CompletableFuture<Void>();
        SwingUtilities.invokeLater(() -> {
            var registrations = new ArrayList<com.jlshell.plugin.api.lifecycle.Registration>();
            try {
                var view = new SwingPreferencesDialog(pages, initial, i18n, themes, !canApply.get(), applyAction, okAction, () -> {
                    registrations.forEach(com.jlshell.plugin.api.lifecycle.Registration::close);
                    result.complete(null);
                });
                registrations.add(canApply.subscribe(enabled -> SwingUtilities.invokeLater(() -> {
                    if (!view.disposed) view.apply.setEnabled(enabled);
                })));
                registrations.add(themes.onThemeChanged(theme -> SwingUtilities.invokeLater(() -> { if (!view.disposed) view.refreshTheme(); })));
                registrations.add(themes.onAccentColorChanged(accent -> SwingUtilities.invokeLater(() -> { if (!view.disposed) view.refreshTheme(); })));
                view.dialog.setVisible(true);
            } catch (RuntimeException | Error error) {
                registrations.forEach(com.jlshell.plugin.api.lifecycle.Registration::close);
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
