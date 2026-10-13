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
public final class IdeaListPopup extends JPanel {
    public enum Style { LIST, CONTEXT }
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
    private final IdeaMouseMovementTracker movement = new IdeaMouseMovementTracker();
    private JWindow popupWindow;
    private Window owner;
    private Component invoker;
    private final Timer hover = new Timer(250, e -> openChild(list.getSelectedIndex(), false));

    public IdeaListPopup(List<Row> rows, int width, boolean searchable, String searchPrompt) {
        this(rows, width, searchable, searchPrompt, Style.LIST);
    }

    public IdeaListPopup(List<Row> rows, int width, boolean searchable, String searchPrompt, Style style) {
        this.rows = List.copyOf(rows);
        setLayout(new BorderLayout());
        setName("idea.listPopup");
        hover.setRepeats(false);
        setOpaque(false);
        setBackground(SwingTheme.color("popup"));
        setBorder(BorderFactory.createEmptyBorder(style == Style.CONTEXT ? 6 : 8, 0, style == Style.CONTEXT ? 6 : 8, 0));
        list.setModel(model);
        list.setFont(UIManager.getFont("defaultFont").deriveFont(Font.PLAIN));
        list.setBackground(getBackground());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new IdeaPopupRenderer(style == Style.CONTEXT || rows.stream().anyMatch(row -> row.icon() != null), style));
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
                if (child != null && child.isShowing() && movement.isMovingTowards(e,
                        new Rectangle(child.getLocationOnScreen(), child.getSize()))) return;
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
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    e.consume();
                    execute(index);
                }
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
        int preferredWidth = width;
        for (int i = 0; i < model.size(); i++) {
            Dimension size = list.getCellRenderer().getListCellRendererComponent(list, model.get(i), i, false, false).getPreferredSize();
            height += size.height;
            preferredWidth = Math.max(preferredWidth, size.width);
        }
        setPreferredSize(new Dimension(Math.min(600, preferredWidth), Math.min(560, height)));
    }

    private void bind(JComponent target, String key, Runnable run) {
        target.getInputMap().put(KeyStroke.getKeyStroke(key), key);
        target.getActionMap().put(key, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { run.run(); }
        });
    }

    public void bindShortcut(KeyStroke key, Runnable action) {
        String name = "popup.shortcut." + key;
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent event) { setVisible(false); action.run(); }
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
        if (!isShowing() || index < 0 || index >= model.size()) return;
        Row row = model.get(index);
        if (!row.enabled() || row.children().isEmpty()) return;
        if (child != null && child.isShowing() && childIndex == index) {
            if (focus) child.list.requestFocusInWindow();
            return;
        }
        if (child != null) child.setVisible(false);
        child = new IdeaListPopup(row.children(), 170, false, "");
        child.parentPopup = this;
        movement.reset();
        childIndex = index;
        Rectangle cell = list.getCellBounds(index, index);
        Point anchor = list.getLocationOnScreen();
        anchor.translate(getWidth() - 2, cell.y - 8);
        Rectangle screen = usableScreen(list.getGraphicsConfiguration());
        if (anchor.x + child.getPreferredSize().width > screen.getMaxX()) {
            anchor.x = list.getLocationOnScreen().x - child.getPreferredSize().width + 2;
        }
        child.showAt(invoker, anchor);
        if (!focus) SwingUtilities.invokeLater(() -> list.requestFocusInWindow());
    }

    @Override public void setVisible(boolean visible) {
        if (visible) {
            if (popupWindow != null) popupWindow.setVisible(true);
            super.setVisible(true);
            return;
        }
        hover.stop();
        if (child != null) { child.setVisible(false); child = null; childIndex = -1; }
        super.setVisible(false);
        if (popupWindow != null) { popupWindow.dispose(); popupWindow = null; }
        if (parentPopup == null) IdeaPopupDispatcher.deactivate(this);
    }

    public void show(Component invoker, int x, int y) {
        Point anchor = invoker.getLocationOnScreen();
        anchor.translate(x, y);
        showAt(invoker, anchor);
    }

    private void showAt(Component component, Point anchor) {
        invoker = component;
        owner = parentPopup == null ? SwingUtilities.getWindowAncestor(component) : parentPopup.owner;
        if (parentPopup == null) IdeaPopupDispatcher.activate(this);
        popupWindow = new JWindow(owner);
        popupWindow.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) {
                if (popupWindow == event.getWindow()) setVisible(false);
            }
        });
        popupWindow.setName("idea.popupWindow");
        popupWindow.setType(Window.Type.POPUP);
        boolean alpha = popupWindow.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        if (alpha) popupWindow.setBackground(new Color(0, true));
        popupWindow.getRootPane().putClientProperty("Window.shadow", false);
        super.setVisible(true);
        IdeaPopupSurface surface = new IdeaPopupSurface(this, alpha);
        popupWindow.setContentPane(surface);
        popupWindow.pack();
        Rectangle screen = usableScreen(component.getGraphicsConfiguration());
        int margin = alpha ? IdeaPopupSurface.SHADOW : 0;
        int contentWidth = popupWindow.getWidth() - margin * 2;
        int contentHeight = popupWindow.getHeight() - margin * 2;
        int x = Math.max(screen.x, Math.min(anchor.x, screen.x + screen.width - contentWidth));
        int y = Math.max(screen.y, Math.min(anchor.y, screen.y + screen.height - contentHeight));
        popupWindow.setLocation(x - margin, y - margin);
        popupWindow.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            if (isShowing()) { if (search.getParent() != null) search.requestFocusInWindow(); else list.requestFocusInWindow(); }
        });
    }

    private static Rectangle usableScreen(GraphicsConfiguration config) {
        Rectangle screen = new Rectangle(config.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        screen.x += insets.left; screen.y += insets.top;
        screen.width -= insets.left + insets.right; screen.height -= insets.top + insets.bottom;
        return screen;
    }

    boolean containsScreen(Point point) {
        return isShowing() && new Rectangle(getLocationOnScreen(), getSize()).contains(point)
                || child != null && child.containsScreen(point);
    }

    boolean owns(Window window) {
        return window != null && (window == popupWindow || child != null && child.owns(window));
    }

    Window ownerWindow() { return owner; }

    boolean dispatchKey(int key) {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        IdeaListPopup target = this;
        while (target.child != null && target.child.isShowing()) {
            if (focused != null && SwingUtilities.isDescendingFrom(focused, target)) break;
            target = target.child;
        }
        if (focused == target.search && key != KeyEvent.VK_ESCAPE && key != KeyEvent.VK_DOWN && key != KeyEvent.VK_ENTER) return false;
        switch (key) {
            case KeyEvent.VK_DOWN -> { target.list.requestFocusInWindow(); target.navigate(1); }
            case KeyEvent.VK_UP -> target.navigate(-1);
            case KeyEvent.VK_ENTER, KeyEvent.VK_RIGHT -> target.execute(target.list.getSelectedIndex());
            case KeyEvent.VK_ESCAPE, KeyEvent.VK_LEFT -> {
                if (target.parentPopup != null) {
                    target.setVisible(false);
                    target.parentPopup.list.requestFocusInWindow();
                } else if (key == KeyEvent.VK_ESCAPE) setVisible(false);
            }
            default -> { return false; }
        }
        return true;
    }
}
