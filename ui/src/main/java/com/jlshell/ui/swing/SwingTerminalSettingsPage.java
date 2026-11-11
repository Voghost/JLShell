package com.jlshell.ui.swing;

import com.jlshell.core.model.FontProfile;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.ui.dialog.ColorSchemeEditDialog;
import com.jlshell.ui.dialog.ColorSchemePreview;
import com.jlshell.ui.dialog.TerminalHighlightEditor;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native settings controls. Drafts are immutable and applied by the preferences controller. */
public final class SwingTerminalSettingsPage extends JPanel implements AutoCloseable {
    public record Values(FontProfile font, TerminalColorScheme scheme, int scrollback, TerminalHighlightConfig highlights) {}
    private final Consumer<Values> changed;
    private final I18nService i18n;
    private final ThemeService themes;
    private final ColorSchemeRegistry registry;
    private final List<TerminalColorScheme> allSchemes;
    private final DefaultListModel<TerminalColorScheme> schemeModel = new DefaultListModel<>();
    private final JList<TerminalColorScheme> schemes = new JList<>(schemeModel);
    private final JTextField search = new JTextField();
    private final JLabel currentLabel = new JLabel(), pendingLabel = new JLabel(), errorLabel = new JLabel(" ");
    private final ColorSchemePreview preview = new ColorSchemePreview();
    private final JSlider opacity;
    private final JButton edit, delete;
    private final Registration schemeSubscription;
    private Values draft;
    private boolean filtering, closed;

    public SwingTerminalSettingsPage(Values initial, List<String> monospaceFonts, List<String> chineseFonts,
                                     List<TerminalColorScheme> colors, I18nService i18n, ThemeService themes, Consumer<Values> changed) {
        super(new BorderLayout());
        this.draft = initial; this.changed = changed; this.i18n = i18n; this.themes = themes;
        this.registry = themes.registry(); this.allSchemes = new ArrayList<>(colors);
        setName("settings.terminalForm");
        JPanel body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS)); body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(16, 24, 24, 24));
        body.add(section("preferences.terminal.typographySection", "preferences.terminal.typographySection.description",
                typography(initial, monospaceFonts, chineseFonts)));
        opacity = new JSlider(0, 100, (int) Math.round(initial.scheme().opacity() * 100));
        opacity.setName("terminal.opacity");
        JPanel palette = new JPanel(new BorderLayout(0, 10)); palette.setOpaque(false);
        JPanel heading = vertical(); currentLabel.setName("terminal.colorScheme.current"); pendingLabel.setName("terminal.colorScheme.preview");
        heading.add(currentLabel); heading.add(pendingLabel);
        search.setName("terminal.colorSchemes.filter"); search.putClientProperty("JTextField.placeholderText", i18n.get("preferences.terminal.colorScheme.filter"));
        heading.add(Box.createVerticalStrut(8)); heading.add(search); palette.add(heading, BorderLayout.NORTH);
        schemes.setName("terminal.colorSchemes"); schemes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        schemes.setCellRenderer((list, scheme, index, selected, focus) -> schemeCell(list, scheme, selected));
        schemes.setFixedCellHeight(Math.max(72, schemes.getFontMetrics(schemes.getFont()).getHeight() + 54));
        schemes.setFixedCellWidth(420);
        JScrollPane listScroll = new JScrollPane(schemes); listScroll.setPreferredSize(new Dimension(420, 236));
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                schemes.setFixedCellWidth(Math.max(1, listScroll.getViewport().getWidth()));
            }
        });
        palette.add(listScroll);
        JPanel footer = vertical(); footer.add(preview);
        JLabel percentage = new JLabel(opacity.getValue() + "%");
        opacity.addChangeListener(event -> {
            percentage.setText(opacity.getValue() + "%");
            if (!filtering) setScheme(draft.scheme().withOpacity(opacity.getValue() / 100d));
        });
        footer.add(row(new JLabel(i18n.get("preferences.terminal.colorScheme.opacity")), opacity, percentage));
        JButton create = button("preferences.terminal.colorScheme.new", () -> editScheme(null, true));
        JButton duplicate = button("preferences.terminal.colorScheme.duplicate", () -> {
            TerminalColorScheme selected = schemes.getSelectedValue();
            if (selected == null) return;
            String base = selected.name() + " " + i18n.get("preferences.terminal.colorScheme.duplicateName").replace("{0}", "").strip();
            String name = base; int suffix = 1;
            var names = allSchemes.stream().map(TerminalColorScheme::name).toList();
            while (names.contains(name)) name = base + " " + suffix++;
            editScheme(registry.copyScheme(selected, name), true);
        });
        edit = button("preferences.terminal.colorScheme.edit", () -> editScheme(schemes.getSelectedValue(), false));
        delete = button("preferences.terminal.colorScheme.delete", this::deleteScheme);
        footer.add(row(create, duplicate, edit, delete)); footer.add(errorLabel); palette.add(footer, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { filter(); }
            public void removeUpdate(DocumentEvent event) { filter(); }
            public void changedUpdate(DocumentEvent event) { filter(); }
        });
        schemes.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || filtering) return;
            TerminalColorScheme selected = schemes.getSelectedValue();
            if (selected == null) return;
            filtering = true; opacity.setValue((int) Math.round(selected.opacity() * 100)); filtering = false;
            edit.setEnabled(!registry.isBuiltIn(selected.name())); delete.setEnabled(edit.isEnabled());
            setScheme(selected);
        });
        body.add(Box.createVerticalStrut(24));
        body.add(section("preferences.terminal.colorsSection", "preferences.terminal.colorsSection.description", palette));
        body.add(Box.createVerticalStrut(24));
        body.add(section("highlight.title", "highlight.description", TerminalHighlightEditor.create(initial.highlights(), i18n, highlights -> {
            draft = new Values(draft.font(), draft.scheme(), draft.scrollback(), highlights); publish();
        })));
        JScrollPane scroll = new JScrollPane(new Tracking(body)); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); scroll.getVerticalScrollBar().setUnitIncrement(18); add(scroll);
        IdeaFormStyle.applyTree(body);
        filter(); preview.update(draft.scheme()); updateSchemeStatus();
        schemeSubscription = themes.onColorSchemeChanged(scheme -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (draft.scheme().equals(scheme)) { updateSchemeStatus(); return; }
            filtering = true;
            opacity.setValue((int) Math.round(scheme.opacity() * 100));
            filtering = false;
            setScheme(scheme); filter();
        }));
    }

    private JComponent typography(Values initial, List<String> monospaceFonts, List<String> chineseFonts) {
        JComboBox<String> family = new JComboBox<>(monospaceFonts.toArray(String[]::new)); family.setName("settings-terminal-font");
        family.putClientProperty("jlshell.fontPicker", true); family.setMaximumRowCount(14);
        family.setRenderer(new com.jlshell.ui.swing.idea.IdeaComboBoxRenderer(String::valueOf, true, false, java.util.Set.of(), "", ""));
        family.setSelectedItem(initial.font().family());
        String automatic = i18n.get("preferences.terminal.chineseAutomatic");
        List<String> chineseOptions = new ArrayList<>(); chineseOptions.add(automatic); chineseOptions.addAll(chineseFonts);
        JComboBox<String> chinese = new JComboBox<>(chineseOptions.toArray(String[]::new)); chinese.setName("settings-terminal-chinese-font");
        chinese.putClientProperty("jlshell.fontPicker", true); chinese.setMaximumRowCount(14);
        chinese.setRenderer(new com.jlshell.ui.swing.idea.IdeaComboBoxRenderer(String::valueOf, true, false, java.util.Set.of(), "", ""));
        chinese.setSelectedItem(initial.font().chineseFamily().isBlank() ? automatic : initial.font().chineseFamily());
        JSpinner size = new JSpinner(new SpinnerNumberModel(initial.font().size(), 8d, 32d, 1d)); size.setName("settings-terminal-size");
        JSpinner spacing = new JSpinner(new SpinnerNumberModel(initial.font().lineSpacing(), .8d, 2d, .1d)); spacing.setName("settings-terminal-spacing");
        JCheckBox ligatures = new JCheckBox(i18n.get("preferences.terminal.ligatures"), initial.font().ligaturesEnabled());
        JSpinner scrollback = new JSpinner(new SpinnerNumberModel(initial.scrollback(), TerminalRuntimeSettings.MIN_SCROLLBACK_LINES, TerminalRuntimeSettings.MAX_SCROLLBACK_LINES, 500));
        scrollback.setName("settings-terminal-scrollback");
        TerminalFontPreview fontPreview = new TerminalFontPreview(initial.font());
        Runnable sync = () -> {
            String chineseFamily = (String) chinese.getSelectedItem();
            FontProfile profile = new FontProfile((String) family.getSelectedItem(), ((Number) size.getValue()).doubleValue(),
                    ligatures.isSelected(), ((Number) spacing.getValue()).doubleValue(), automatic.equals(chineseFamily) ? "" : chineseFamily);
            draft = new Values(profile, draft.scheme(), ((Number) scrollback.getValue()).intValue(), draft.highlights());
            fontPreview.setProfile(profile); publish();
        };
        family.addActionListener(event -> sync.run()); chinese.addActionListener(event -> sync.run());
        size.addChangeListener(event -> sync.run()); spacing.addChangeListener(event -> sync.run());
        ligatures.addActionListener(event -> sync.run()); scrollback.addChangeListener(event -> sync.run());
        JPanel fields = new JPanel(new GridBagLayout()); fields.setOpaque(false);
        JComponent[] controls = {family, chinese, size, spacing, ligatures, scrollback};
        String[] labels = {"preferences.terminal.fontFamily", "preferences.terminal.chineseFontFamily", "preferences.terminal.fontSize", "preferences.terminal.lineSpacing", null, "preferences.terminal.scrollbackLines"};
        for (int index = 0; index < controls.length; index++) {
            GridBagConstraints c = new GridBagConstraints(); c.gridy = index; c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 0, 5, 12);
            fields.add(new JLabel(labels[index] == null ? "" : i18n.get(labels[index])), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; fields.add(controls[index], c);
        }
        JPanel previewPanel = new JPanel(new BorderLayout(0, 12)); previewPanel.setOpaque(false);
        previewPanel.setBorder(BorderFactory.createEmptyBorder(4, 20, 4, 0));
        previewPanel.add(new JLabel(i18n.get("preferences.terminal.preview")), BorderLayout.NORTH); previewPanel.add(fontPreview);
        JPanel panel = new JPanel(new GridLayout(1, 2, 16, 0)); panel.setOpaque(false); panel.add(fields); panel.add(previewPanel);
        return panel;
    }

    private JComponent schemeCell(JList<?> list, TerminalColorScheme scheme, boolean selected) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(selected ? UIManager.getColor("Component.focusColor") : list.getBackground(), 2, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        card.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
        JLabel name = new JLabel((selected ? "✓  " : "    ") + scheme.name() + " · " + i18n.get(scheme.isLight() ? "colorScheme.light" : "colorScheme.dark")
                + " " + i18n.get(registry.isBuiltIn(scheme.name()) ? "preferences.terminal.colorScheme.builtin" : "preferences.terminal.colorScheme.custom"));
        name.setForeground(selected ? list.getSelectionForeground() : list.getForeground()); card.add(name, BorderLayout.NORTH);
        JLabel sample = new JLabel("  user@server ~ $ ls"); sample.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sample.setOpaque(true); sample.setBackground(scheme.background()); sample.setForeground(scheme.foreground());
        sample.setPreferredSize(new Dimension(0, 28)); card.add(sample); return card;
    }

    private void setScheme(TerminalColorScheme scheme) {
        draft = new Values(draft.font(), scheme, draft.scrollback(), draft.highlights());
        preview.update(scheme); updateSchemeStatus(); publish();
    }
    private void updateSchemeStatus() {
        TerminalColorScheme active = themes.activeColorScheme(); currentLabel.setText(i18n.get("colorScheme.current", active.name()));
        pendingLabel.setText(active.equals(draft.scheme()) ? i18n.get("colorScheme.applied") : i18n.get("colorScheme.pending", draft.scheme().name()));
    }
    private void publish() { if (!closed) changed.accept(draft); }
    private void filter() {
        filtering = true;
        String query = search.getText().toLowerCase(Locale.ROOT); schemeModel.clear();
        schemeModel.addAll(allSchemes.stream().filter(scheme -> scheme.name().toLowerCase(Locale.ROOT).contains(query)).toList());
        for (int index = 0; index < schemeModel.size(); index++) if (schemeModel.get(index).name().equals(draft.scheme().name())) {
            schemes.setSelectedIndex(index); schemes.ensureIndexIsVisible(index); break;
        }
        TerminalColorScheme selected = schemes.getSelectedValue();
        edit.setEnabled(selected != null && !registry.isBuiltIn(selected.name())); delete.setEnabled(edit.isEnabled());
        filtering = false;
    }
    private void editScheme(TerminalColorScheme initial, boolean isNew) {
        ColorSchemeEditDialog.showAsync(themes, i18n, initial, isNew).whenComplete((edited, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) { errorLabel.setText(error.getMessage()); return; }
            if (edited != null) persist(() -> registry.saveCustomScheme(edited), edited);
        }));
    }
    private void deleteScheme() {
        TerminalColorScheme selected = schemes.getSelectedValue();
        if (selected == null || registry.isBuiltIn(selected.name())) return;
        persist(() -> registry.deleteCustomScheme(selected.name()), themes.activeColorScheme());
    }
    private void persist(Runnable operation, TerminalColorScheme selected) {
        CompletableFuture.supplyAsync(() -> { operation.run(); return registry.allSchemes(); }).whenComplete((items, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) { errorLabel.setText(error.getCause() == null ? error.getMessage() : error.getCause().getMessage()); return; }
            allSchemes.clear(); allSchemes.addAll(items); setScheme(selected); filter(); errorLabel.setText(" ");
        }));
    }
    private JButton button(String key, Runnable action) { JButton button = new JButton(i18n.get(key)); button.addActionListener(event -> action.run()); return button; }
    private JPanel section(String title, String hint, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout(0, 12)); panel.setOpaque(false);
        JPanel header = vertical(); JLabel label = new JLabel(i18n.get(title)); label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1));
        header.add(label); JTextArea description = new JTextArea(i18n.get(hint)); description.setEditable(false); description.setFocusable(false); description.setLineWrap(true);
        description.setWrapStyleWord(true); description.setOpaque(false); description.setFont(label.getFont().deriveFont(Font.PLAIN)); header.add(description);
        panel.add(header, BorderLayout.NORTH); panel.add(content); return panel;
    }
    private static JPanel vertical() { JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setOpaque(false); return panel; }
    private static JPanel row(JComponent... components) { JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6)); row.setOpaque(false); for (JComponent component : components) row.add(component); return row; }
    @Override public void close() { closed = true; schemeSubscription.close(); }
    private static final class Tracking extends JPanel implements Scrollable {
        Tracking(JComponent content) { super(new BorderLayout()); setOpaque(false); add(content, BorderLayout.NORTH); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(18, r.height - 18); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
