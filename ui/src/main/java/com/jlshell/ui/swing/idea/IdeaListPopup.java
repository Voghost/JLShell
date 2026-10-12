package com.jlshell.ui.swing.idea;

import com.jlshell.ui.swing.SwingTheme;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Locale;

/** ListPopupImpl 的列表选择/分组导航适配，替换 IDEA ActionSystem 为宿主 Runnable。 */
public final class IdeaListPopup extends JPopupMenu {
    public record Row(String title, String detail, Icon icon, String shortcut, String group,
                      boolean enabled, List<Row> children, Runnable action) {
        public static Row action(String title, Icon icon, String shortcut, String group, Runnable action) {
            return new Row(title, null, icon, shortcut, group, true, List.of(), action);
        }
    }

    private final JList<Row> list = new JList<>();
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final List<Row> rows;
    private final JTextField search = new JTextField();
    private IdeaListPopup child;
    private IdeaListPopup parentPopup;
    private int childIndex = -1;
    private final Timer hover = new Timer(180, e -> openChild(list.getSelectedIndex(), false));

    public IdeaListPopup(List<Row> rows, int width, boolean searchable, String searchPrompt) {
        this.rows = List.copyOf(rows);
        setLayout(new BorderLayout());
        setName("idea.listPopup");
        hover.setRepeats(false);
        putClientProperty("Popup.borderCornerRadius", 8);
        setBackground(SwingTheme.color("popup"));
        setBorder(new javax.swing.border.AbstractBorder() {
            public Insets getBorderInsets(Component c) { return new Insets(7, 1, 7, 1); }
            public Insets getBorderInsets(Component c, Insets i) { i.set(7, 1, 7, 1); return i; }
            public void paintBorder(Component c, Graphics graphics, int x, int y, int w, int h) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(SwingTheme.color("popupBorder"));
                    g.drawRoundRect(x, y, w - 1, h - 1, 8, 8);
                } finally { g.dispose(); }
            }
        });
        list.setModel(model);
        list.setFont(UIManager.getFont("Label.font"));
        list.setBackground(getBackground());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new IdeaPopupRenderer());
        list.setFocusable(true);
        list.setName("popup.list");
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        if (searchable) {
            search.putClientProperty("JTextField.placeholderText", searchPrompt);
            search.setName("popup.search");
            search.putClientProperty("JTextField.leadingIcon", new com.jlshell.ui.swing.WorkbenchIcon(com.jlshell.ui.swing.WorkbenchIcon.Kind.SEARCH));
            search.putClientProperty("JTextField.showClearButton", true);
            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(getBackground());
            header.setBorder(BorderFactory.createEmptyBorder(2, 10, 6, 10));
            header.add(search);
            add(header, BorderLayout.NORTH);
            search.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { filter(); }
                public void removeUpdate(DocumentEvent e) { filter(); }
                public void changedUpdate(DocumentEvent e) { filter(); }
            });
            search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "list");
            search.getActionMap().put("list", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { list.requestFocusInWindow(); }
            });
        }
        filter();
        list.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint()) && list.getSelectedIndex() != index) {
                    list.setSelectedIndex(index);
                    if (child != null) { child.setVisible(false); child = null; childIndex = -1; }
                    hover.restart();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            public void mouseReleased(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) execute(index);
            }
        });
        bind(list, "ENTER", () -> execute(list.getSelectedIndex()));
        bind(list, "DOWN", () -> navigate(1));
        bind(list, "UP", () -> navigate(-1));
        bind(list, "RIGHT", () -> execute(list.getSelectedIndex()));
        bind(list, "LEFT", () -> {
            if (parentPopup != null) { setVisible(false); parentPopup.list.requestFocusInWindow(); }
        });
        bind(list, "ESCAPE", () -> setVisible(false));
        bind(search, "ENTER", () -> execute(list.getSelectedIndex()));
        bind(search, "ESCAPE", () -> setVisible(false));
        int height = getInsets().top + getInsets().bottom;
        if (searchable) height += search.getParent().getPreferredSize().height;
        for (int i = 0; i < model.size(); i++) height += list.getCellRenderer()
                .getListCellRendererComponent(list, model.get(i), i, false, false).getPreferredSize().height;
        setPopupSize(width, Math.min(560, height));
    }

    private void bind(JComponent target, String key, Runnable run) {
        target.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        target.getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { run.run(); }
        });
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        model.clear();
        String group = null;
        boolean groupPending = false;
        for (Row row : rows) {
            if (row.group() != null) { group = row.group(); groupPending = true; }
            if ((row.title() + " " + java.util.Objects.toString(row.detail(), "")).toLowerCase(Locale.ROOT).contains(query)) {
                model.addElement(new Row(row.title(), row.detail(), row.icon(), row.shortcut(), groupPending ? group : null,
                        row.enabled(), row.children(), row.action()));
                groupPending = false;
            }
        }
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    private void execute(int index) {
        if (index < 0 || index >= model.size()) return;
        Row row = model.get(index);
        if (!row.enabled()) return;
        if (!row.children().isEmpty()) {
            openChild(index, true);
        } else {
            IdeaListPopup root = this;
            while (root.parentPopup != null) root = root.parentPopup;
            root.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            if (row.action() != null) row.action().run();
        }
    }

    private void navigate(int direction) {
        int index = list.getSelectedIndex();
        for (int count = 0; count < model.size(); count++) {
            index = Math.floorMod(index + direction, model.size());
            if (model.get(index).enabled()) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                if (child != null) { child.setVisible(false); child = null; childIndex = -1; }
                return;
            }
        }
    }

    private void openChild(int index, boolean focus) {
        if (!isVisible() || index < 0 || index >= model.size()) return;
        Row row = model.get(index);
        if (!row.enabled() || row.children().isEmpty()) return;
        if (child != null && childIndex == index) {
            if (focus) child.list.requestFocusInWindow();
            return;
        }
        if (child != null) child.setVisible(false);
        child = new IdeaListPopup(row.children(), Math.max(280, getWidth()), false, "");
        child.parentPopup = this;
        childIndex = index;
        Rectangle cell = list.getCellBounds(index, index);
        child.show(list, getWidth() - 8, cell.y - 6);
        if (!focus) SwingUtilities.invokeLater(() -> list.requestFocusInWindow());
    }

    @Override public void setVisible(boolean visible) {
        if (visible == isVisible()) return;
        if (!visible) {
            if (hover != null) hover.stop();
            if (child != null) { child.setVisible(false); child = null; childIndex = -1; }
        }
        if (parentPopup == null) {
            super.setVisible(visible);
            return;
        }
        // JPopupMenu 把 JList invoker 视为独立根菜单，会在子菜单打开时关闭父菜单。
        // 子列表使用同一条 MenuSelectionManager 路径，保留根菜单的外部点击/焦点关闭处理。
        Component invoker = getInvoker();
        setInvoker(null);
        try { super.setVisible(visible); }
        finally { setInvoker(invoker); }
        var path = new java.util.ArrayList<MenuElement>();
        IdeaListPopup current = visible ? this : parentPopup;
        while (current != null) { path.addFirst(current); current = current.parentPopup; }
        MenuSelectionManager.defaultManager().setSelectedPath(path.toArray(MenuElement[]::new));
    }

    @Override public void show(Component invoker, int x, int y) {
        super.show(invoker, x, y);
        SwingUtilities.invokeLater(() -> { if (search.getParent() != null) search.requestFocusInWindow(); else list.requestFocusInWindow(); });
    }
}
