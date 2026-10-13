package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.theme.ThemeService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TabPane;
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

/**
 * SettingsDialog / SettingsEditor / SettingsTreeView 的 Swing 外壳适配。
 * 搜索树、面包屑、分栏和动作栏遵循 IDEA；现有 FX 配置页与保存动作原样复用。
 */
public final class SwingPreferencesDialog {
    private record Page(int index, String title, String group, String searchText, Node content) {}
    private record Entry(String title, Page page) { public String toString() { return title; } }

    private final JDialog dialog;
    private final JTree tree = new JTree();
    private final JTextField search = new JTextField();
    private final JLabel breadcrumb = new JLabel();
    private final JButton apply;
    private final JButton ok;
    private final SwingFxPanel content;
    private final List<Page> pages;
    private final Timer filterTimer;
    private Page selected;
    private boolean busy;
    private final Window owner;
    private final boolean ownerWasEnabled;
    private final ThemeService themes;

    private SwingPreferencesDialog(List<Page> pages, int initial, I18nService i18n,
                                   ThemeService themes, boolean applyDisabled,
                                   Runnable applyAction, Runnable okAction, Runnable closed) {
        this.pages = pages;
        this.themes = themes;
        Window parent = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (parent == null) for (Frame frame : Frame.getFrames()) {
            if (frame.isShowing() && frame.getTitle().contains("JLShell")) { parent = frame; break; }
        }
        owner = parent;
        ownerWasEnabled = owner != null && owner.isEnabled();
        // AWT 应用模态过滤器会阻止 JFXPanel 中的 Glass 下拉弹窗接收鼠标。
        // 使用禁用宿主窗口实现模态，FX 嵌套事件循环仍保持 showAndWait 的调用顺序。
        dialog = new JDialog(owner, i18n.get("preferences.title") + " — JLShell", Dialog.ModalityType.MODELESS);
        dialog.setName("jlshell.settings");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        content = new SwingFxPanel(themes);
        content.setOwnsDialogKeys(true);
        content.setName("settings.content");
        JPanel navigation = new JPanel(new BorderLayout(0, 10));
        navigation.setBorder(BorderFactory.createEmptyBorder(14, 12, 8, 0));
        search.setName("settings.search");
        search.putClientProperty("JTextField.leadingIcon", new WorkbenchIcon(WorkbenchIcon.Kind.SEARCH));
        search.putClientProperty("JTextField.placeholderText", i18n.get("settings.search"));
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(280, 32));
        navigation.add(search, BorderLayout.NORTH);
        tree.setName("settings.tree");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        styleTreeSelection();
        tree.setRowHeight(Math.max(28, tree.getFontMetrics(tree.getFont()).getHeight() + 10));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, false);
                Entry entry = (Entry) ((DefaultMutableTreeNode) value).getUserObject();
                setIcon(null);
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
        breadcrumb.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
        breadcrumb.setFont(breadcrumb.getFont().deriveFont(Font.BOLD, breadcrumb.getFont().getSize2D() + 1));
        editor.add(breadcrumb, BorderLayout.NORTH);
        editor.add(content);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigation, editor);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(1);
        split.setDividerLocation(300);
        split.setResizeWeight(0);
        navigation.setMinimumSize(new Dimension(220, 0));
        editor.setMinimumSize(new Dimension(560, 0));

        JButton cancel = new JButton(i18n.get("settings.cancel"));
        cancel.setName("settings.cancel");
        apply = new JButton(i18n.get("preferences.apply"));
        apply.setName("settings.apply");
        apply.setEnabled(!applyDisabled);
        ok = new JButton(i18n.get("settings.ok"));
        ok.setName("settings.ok");
        ok.putClientProperty("JButton.buttonType", "default");
        dialog.getRootPane().setDefaultButton(ok);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingTheme.color("border")));
        for (JButton button : List.of(cancel, apply, ok)) {
            button.setPreferredSize(new Dimension(90, 30));
            footer.add(button);
        }
        cancel.addActionListener(e -> { if (!busy) dialog.dispose(); });
        apply.addActionListener(e -> runAction(applyAction, false));
        ok.addActionListener(e -> runAction(okAction, true));
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { if (!busy) dialog.dispose(); }
            public void windowClosed(WindowEvent e) {
                filterTimer.stop();
                content.close();
                if (owner instanceof RootPaneContainer host) host.getRootPane().putClientProperty("jlshell.settingsModal", null);
                if (owner != null) owner.setEnabled(ownerWasEnabled);
                closed.run();
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> { if (!busy) dialog.dispose(); },
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().registerKeyboardAction(e -> search.requestFocusInWindow(),
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
        dialog.setSize(Math.min(1180, screen.width - 80), Math.min(800, screen.height - 80));
        dialog.setLocationRelativeTo(owner);
        rebuildTree(initial);
        if (owner instanceof RootPaneContainer host) host.getRootPane().putClientProperty("jlshell.settingsModal", true);
        if (owner != null) owner.setEnabled(false);
    }

    private void runAction(Runnable action, boolean close) {
        if (busy) return;
        busy = true;
        dialog.setEnabled(false);
        FxThread.run(() -> {
            boolean succeeded = false;
            try {
                action.run();
                themes.refreshUi();
                var theme = themes.currentTheme();
                String accent = themes.accentColor().color(), family = themes.uiFontFamily();
                int size = themes.uiFontSize();
                SwingUtilities.invokeLater(() -> {
                    if (owner instanceof RootPaneContainer host
                            && host.getRootPane().getClientProperty("jlshell.workbench") instanceof SwingWorkbench workbench) {
                        workbench.applyTheme(theme, accent, family, size);
                    }
                    SwingTheme.update(dialog);
                    styleTreeSelection();
                });
                succeeded = true;
            }
            finally {
                boolean dispose = close && succeeded;
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    dialog.setEnabled(true);
                    if (dispose) dialog.dispose();
                });
            }
        });
    }

    private void select(Page page) {
        if (selected == page) return;
        selected = page;
        breadcrumb.setText(page.group() == null ? page.title() : page.group() + "  ›  " + page.title());
        content.setView(page.content());
        FxThread.run(() -> {
            if (content.getScene() == null) return;
            content.getScene().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                if (event.isConsumed()) return;
                if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    event.consume();
                    SwingUtilities.invokeLater(() -> { if (!busy) dialog.dispose(); });
                }
            });
        });
    }

    private void styleTreeSelection() {
        tree.setRowHeight(Math.max(28, tree.getFontMetrics(tree.getFont()).getHeight() + 10));
        tree.putClientProperty("FlatLaf.style", java.util.Map.of(
                "selectionBackground", SwingTheme.color("popupSelection"),
                "selectionInactiveBackground", SwingTheme.color("popupSelection"),
                "selectionInsets", new Insets(0, 0, 0, 4), "selectionArc", 0));
    }

    private void rebuildTree(int preferred) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Entry("", null));
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        DefaultMutableTreeNode preferredNode = null;
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
            if (preferredNode == null || page.index() == preferred) preferredNode = leaf;
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        if (preferredNode != null) tree.setSelectionPath(new TreePath(preferredNode.getPath()));
    }

    private static void searchableText(Node node, StringBuilder text) {
        if (node instanceof javafx.scene.control.Labeled label) text.append(' ').append(label.getText());
        if (node instanceof javafx.scene.control.TextInputControl input) text.append(' ').append(input.getPromptText());
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) searchableText(child, text);
    }

    /** FX 与 EDT 各自继续处理事件；禁止 FX 等待 invokeAndWait，以免再次发生启动死锁。 */
    public static void showAndWait(TabPane tabs, javafx.scene.control.Button fxApply,
                                   I18nService i18n, ThemeService themes, Runnable okAction) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Settings model requires FX thread");
        List<Page> pages = new ArrayList<>();
        for (int index = 0; index < tabs.getTabs().size(); index++) {
            var tab = tabs.getTabs().get(index);
            String group = switch (index) {
                case 0, 1 -> i18n.get("settings.group.appearance");
                case 3, 4 -> i18n.get("settings.group.tools");
                default -> null;
            };
            tab.getContent().getStyleClass().add("idea-settings-content");
            StringBuilder text = new StringBuilder(tab.getText()).append(' ').append(group);
            searchableText(tab.getContent(), text);
            pages.add(new Page(index, tab.getText(), group, text.toString().toLowerCase(Locale.ROOT), tab.getContent()));
        }
        int initial = tabs.getSelectionModel().getSelectedIndex();
        boolean disabled = fxApply.isDisabled();
        Object loop = new Object();
        AtomicReference<SwingPreferencesDialog> view = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ChangeListener<Boolean> applyListener = (o, old, value) -> SwingUtilities.invokeLater(() -> {
            if (view.get() != null) view.get().apply.setEnabled(!value);
        });
        ChangeListener<Object> themeListener = (o, old, value) -> SwingUtilities.invokeLater(() -> {
            if (view.get() != null) {
                SwingTheme.update(view.get().dialog);
                view.get().styleTreeSelection();
            }
        });
        javafx.collections.ListChangeListener<javafx.stage.Window> modalListener = change -> {
            boolean modal = javafx.stage.Window.getWindows().stream().anyMatch(window ->
                    window instanceof javafx.stage.Stage stage && stage.isShowing()
                            && stage.getModality() != javafx.stage.Modality.NONE);
            SwingUtilities.invokeLater(() -> {
                if (view.get() != null) view.get().dialog.setEnabled(!modal && !view.get().busy);
            });
        };
        fxApply.disabledProperty().addListener(applyListener);
        themes.currentThemeProperty().addListener(themeListener);
        themes.accentColorProperty().addListener(themeListener);
        javafx.stage.Window.getWindows().addListener(modalListener);
        Runnable closed = () -> FxThread.run(() -> {
            fxApply.disabledProperty().removeListener(applyListener);
            themes.currentThemeProperty().removeListener(themeListener);
            themes.accentColorProperty().removeListener(themeListener);
            javafx.stage.Window.getWindows().removeListener(modalListener);
            Platform.exitNestedEventLoop(loop, null);
        });
        SwingUtilities.invokeLater(() -> {
            try {
                SwingPreferencesDialog created = new SwingPreferencesDialog(pages, initial, i18n, themes,
                        disabled, fxApply::fire, okAction, closed);
                view.set(created);
                created.dialog.setVisible(true);
            } catch (Throwable failure) { error.set(failure); closed.run(); }
        });
        Platform.enterNestedEventLoop(loop);
        if (error.get() != null) throw new IllegalStateException("Cannot display settings", error.get());
    }
}
