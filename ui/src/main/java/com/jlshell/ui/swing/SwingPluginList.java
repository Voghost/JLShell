package com.jlshell.ui.swing;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 窄侧栏使用紧凑卡片；卡片文字按可用宽度绘制，不撑宽滚动区域。 */
public final class SwingPluginList extends JPanel {
    public record Entry(String id, String name, String description, String version, boolean pinned,
                        Runnable open, Consumer<Boolean> pin) {}
    private final JPanel rows = new JPanel();
    private final JTextField search = new JTextField();
    private final JLabel count = new JLabel();
    private List<Entry> entries = List.of();
    private String openText, pinText, unpinText, emptyText;

    public SwingPluginList() {
        super(new BorderLayout(0, 10)); setName("session.plugins");
        setMinimumSize(new Dimension(0, 0));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        search.setName("plugins.search"); search.putClientProperty("JTextField.leadingIcon", new WorkbenchIcon(WorkbenchIcon.Kind.SEARCH));
        search.putClientProperty("JTextField.showClearButton", true);
        add(search, BorderLayout.NORTH);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JPanel tracking = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() { return new Dimension(0, rows.getPreferredSize().height); }
        };
        tracking.add(rows, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tracking);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20); add(scroll, BorderLayout.CENTER);
        count.putClientProperty("JLShell.secondaryForeground", true);
        count.setForeground(SwingTheme.color("secondary")); add(count, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { render(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { render(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { render(); }
        });
    }

    public void update(List<Entry> values, String searchText, String open, String pin, String unpin, String empty) {
        entries = List.copyOf(values); openText = open; pinText = pin; unpinText = unpin; emptyText = empty;
        search.putClientProperty("JTextField.placeholderText", searchText); render();
    }

    private void render() {
        rows.removeAll(); String query = search.getText().strip().toLowerCase(Locale.ROOT); int visible = 0;
        for (Entry entry : entries) {
            if (!(entry.name() + " " + entry.description()).toLowerCase(Locale.ROOT).contains(query)) continue;
            visible++; rows.add(new Card(entry)); rows.add(Box.createVerticalStrut(8));
        }
        if (visible == 0) { JLabel empty = new JLabel(emptyText); empty.setForeground(SwingTheme.color("secondary")); rows.add(empty); }
        count.setText(visible + (Locale.getDefault().getLanguage().equals("zh") ? " 个插件" : " plugins")); rows.revalidate(); rows.repaint();
    }

    private final class Card extends JPanel {
        private boolean hover;
        Card(Entry entry) {
            super(new BorderLayout(0, 8)); setOpaque(false); setName("plugin." + entry.id());
            setAlignmentX(LEFT_ALIGNMENT); setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            setPreferredSize(new Dimension(0, 146)); setMinimumSize(new Dimension(0, 146));
            setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
            JPanel title = new JPanel(new BorderLayout(8, 0)); title.setOpaque(false);
            JLabel name = new JLabel(entry.name(), new WorkbenchIcon(WorkbenchIcon.Kind.PLUGIN), JLabel.LEFT);
            name.putClientProperty("html.disable", true); name.setFont(name.getFont().deriveFont(Font.BOLD)); name.setToolTipText(entry.name());
            name.setMinimumSize(new Dimension(0, 22)); title.add(name, BorderLayout.CENTER);
            JToggleButton pin = new JToggleButton(new WorkbenchIcon(WorkbenchIcon.Kind.PIN), entry.pinned());
            pin.setName("plugin-pin-" + entry.id()); pin.setToolTipText(entry.pinned() ? unpinText : pinText);
            pin.getAccessibleContext().setAccessibleName(pin.getToolTipText());
            pin.putClientProperty("JButton.buttonType", "toolBarButton"); pin.setPreferredSize(new Dimension(26, 26));
            pin.addActionListener(event -> {
                pin.setToolTipText(pin.isSelected() ? unpinText : pinText);
                pin.getAccessibleContext().setAccessibleName(pin.getToolTipText());
                entry.pin().accept(pin.isSelected());
            }); title.add(pin, BorderLayout.EAST); add(title, BorderLayout.NORTH);
            JComponent description = new JComponent() {
                @Override public Dimension getPreferredSize() { return new Dimension(0, 38); }
                @Override protected void paintComponent(Graphics graphics) {
                    graphics.setFont(UIManager.getFont("Label.font")); graphics.setColor(SwingTheme.color("secondary"));
                    FontMetrics fm = graphics.getFontMetrics(); String remaining = entry.description().replaceAll("\\s+", " ").strip();
                    for (int line = 0; line < 2 && !remaining.isEmpty(); line++) {
                        int end = remaining.length();
                        while (end > 0 && fm.stringWidth(remaining.substring(0, end) + (end < remaining.length() && line == 1 ? "…" : "")) > getWidth()) end--;
                        if (end == 0) break;
                        graphics.drawString(remaining.substring(0, end) + (end < remaining.length() && line == 1 ? "…" : ""), 0, fm.getAscent() + line * fm.getHeight());
                        remaining = remaining.substring(end);
                    }
                }
            };
            description.setToolTipText(entry.description()); add(description, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(6, 0)); footer.setOpaque(false);
            JLabel version = new JLabel(entry.version()); version.putClientProperty("JLShell.secondaryForeground", true); version.setForeground(SwingTheme.color("secondary")); footer.add(version, BorderLayout.CENTER);
            JButton open = new JButton(openText); open.setName("plugin-open-" + entry.id()); IdeaFormStyle.apply(open);
            open.addActionListener(event -> entry.open().run()); footer.add(open, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) entry.open().run(); }
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = SwingTheme.color(hover ? "headerHover" : "raised");
                if (bg == null) bg = UIManager.getColor("Panel.background");
                g.setColor(bg); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g.setColor(SwingTheme.color("border")); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            } finally { g.dispose(); }
        }
    }
}
