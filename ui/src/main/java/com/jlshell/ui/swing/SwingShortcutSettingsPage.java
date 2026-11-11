package com.jlshell.ui.swing;

import com.jlshell.core.shortcut.ShortcutConverter;
import com.jlshell.core.shortcut.ShortcutDefinition;
import com.jlshell.core.shortcut.ShortcutRegistry;
import com.jlshell.ui.service.I18nService;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shortcut editing and recording use native controls and release all key dispatchers on close. */
public final class SwingShortcutSettingsPage extends JPanel implements AutoCloseable {
    private final ShortcutRegistry registry;
    private final I18nService i18n;
    private final Runnable changed;
    private final JTextField search = new JTextField();
    private final JPanel rows = new JPanel(new GridBagLayout());
    private final JLabel results = new JLabel();
    private final List<Runnable> recordings = new ArrayList<>();
    private boolean closed;

    public SwingShortcutSettingsPage(ShortcutRegistry registry, I18nService i18n, Runnable changed) {
        super(new BorderLayout(0, 12));
        this.registry = registry;
        this.i18n = i18n;
        this.changed = changed;
        setBorder(BorderFactory.createEmptyBorder(12, 24, 24, 24));
        search.setName("settings-shortcuts-search");
        search.putClientProperty("JTextField.placeholderText", i18n.get("shortcut.search.prompt"));
        JButton reset = new JButton(i18n.get("shortcut.resetDefaults"));
        reset.setEnabled(registry != null);
        reset.addActionListener(event -> SwingFormDialog.showAsync(i18n.get("preferences.tab.shortcuts"),
                "jlshell.shortcuts.reset", i18n, () -> {
                    JPanel message = new JPanel(new BorderLayout());
                    message.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
                    message.add(new JLabel(i18n.get("shortcut.resetDefaults.confirm")));
                    return new SwingFormDialog.Form<>(message, () -> Boolean.TRUE);
                }, 480, 180).thenAccept(accepted -> {
                    if (closed || !Boolean.TRUE.equals(accepted)) return;
                    registry.resetAll();
                    changed.run();
                    rebuild();
                }));
        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        toolbar.add(search);
        toolbar.add(reset, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(toolbar);
        top.add(results, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        rows.setName("settings.actionsTree");
        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { rebuild(); }
            public void removeUpdate(DocumentEvent event) { rebuild(); }
            public void changedUpdate(DocumentEvent event) { rebuild(); }
        });
        rebuild();
    }

    private void rebuild() {
        recordings.forEach(Runnable::run);
        recordings.clear();
        rows.removeAll();
        if (registry == null) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        String category = "";
        int row = 0;
        int count = 0;
        for (ShortcutDefinition definition : registry.definitions()) {
            String text = i18n.get(definition.nameKey()) + " " + definition.id() + " "
                    + i18n.get("shortcut.category." + definition.category()) + " "
                    + display(registry.getEffectivePrimary(definition.id())) + " "
                    + display(registry.getEffectiveSecondary(definition.id()));
            if (!query.isBlank() && !text.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (!category.equals(definition.category())) {
                category = definition.category();
                JLabel title = new JLabel(i18n.get("shortcut.category." + category));
                title.setFont(title.getFont().deriveFont(Font.BOLD));
                GridBagConstraints heading = constraints(row++, 0);
                heading.gridwidth = 3;
                heading.insets = new Insets(16, 0, 8, 0);
                rows.add(title, heading);
            }
            GridBagConstraints name = constraints(row, 0);
            name.weightx = 1;
            rows.add(new JLabel(i18n.get(definition.nameKey())), name);
            rows.add(binding(definition, true), constraints(row, 1));
            rows.add(binding(definition, false), constraints(row++, 2));
            count++;
        }
        GridBagConstraints filler = constraints(row, 0);
        filler.weighty = 1;
        rows.add(Box.createVerticalGlue(), filler);
        results.setText(i18n.get("shortcut.search.results", count));
        IdeaFormStyle.applyTree(rows);
        rows.revalidate();
        rows.repaint();
    }

    private JPanel binding(ShortcutDefinition definition, boolean primary) {
        JButton record = new JButton(display(primary ? registry.getEffectivePrimary(definition.id())
                : registry.getEffectiveSecondary(definition.id())));
        record.setName("shortcut." + definition.id() + (primary ? ".primary" : ".secondary"));
        record.setPreferredSize(new Dimension(148, 30));
        recordings.add(SwingShortcutRecording.install(record,
                new SwingShortcutRecording.Binding(i18n.get("shortcut.pressKey"), spec -> {
                    var conflicts = registry.findConflicts(definition.id(), spec);
                    if (!conflicts.isEmpty()) return i18n.get("shortcut.conflict", i18n.get(conflicts.getFirst().nameKey()));
                    if (primary) registry.setUserPrimary(definition.id(), spec);
                    else registry.setUserSecondary(definition.id(), spec);
                    record.setText(display(spec));
                    changed.run();
                    return null;
                })));
        JButton clear = new JButton("×");
        clear.setToolTipText(i18n.get("shortcut.clear"));
        clear.addActionListener(event -> {
            if (primary) registry.setUserPrimary(definition.id(), null);
            else registry.setUserSecondary(definition.id(), null);
            record.setText(display(primary ? registry.getEffectivePrimary(definition.id())
                    : registry.getEffectiveSecondary(definition.id())));
            changed.run();
        });
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cell.setOpaque(false);
        cell.add(record);
        cell.add(clear);
        return cell;
    }

    private String display(String spec) {
        return spec == null || spec.isBlank() ? i18n.get("shortcut.unassigned") : ShortcutConverter.toDisplayText(spec);
    }

    private static GridBagConstraints constraints(int row, int column) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.gridx = column;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 0, 4, 8);
        return constraints;
    }

    @Override public void close() {
        closed = true;
        recordings.forEach(Runnable::run);
        recordings.clear();
    }
}
