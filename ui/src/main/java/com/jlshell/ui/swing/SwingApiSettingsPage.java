package com.jlshell.ui.swing;

import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** API preferences and searchable RPC documentation, without a scene graph. */
public final class SwingApiSettingsPage extends JPanel {
    public record Entry(String name, String type, String description, String detail, String method,
                        String endpoint, String request, String schema) {}

    public SwingApiSettingsPage(boolean enabled, String savedPort, String status, Supplier<String> token, List<Entry> entries,
                                 I18nService i18n, BiConsumer<Boolean, String> changed) {
        super(new BorderLayout(0, 20));
        setName("settings.apiForm");
        setBorder(BorderFactory.createEmptyBorder(16, 24, 24, 24));
        JCheckBox active = new JCheckBox(i18n.get("api.enabled"), enabled);
        JTextField port = new JTextField(savedPort, 6);
        port.setEnabled(enabled);
        active.addActionListener(event -> { port.setEnabled(active.isSelected()); changed.accept(active.isSelected(), port.getText()); });
        watch(port, () -> changed.accept(active.isSelected(), port.getText()));
        JButton copyToken = new JButton(i18n.get("api.copyToken"));
        String currentToken = token.get();
        copyToken.setEnabled(currentToken != null && !currentToken.isBlank());
        copyToken.addActionListener(event -> copy(copyToken, token.get(), i18n));
        JPanel configuration = new JPanel(new BorderLayout(0, 10));
        configuration.setOpaque(false);
        configuration.add(heading(i18n.get("api.config.title")), BorderLayout.NORTH);
        configuration.add(row(active, new JLabel(i18n.get("api.port")), port, new JLabel(status), copyToken));
        configuration.add(new JLabel(i18n.get("api.tokenHint") + "  ·  " + i18n.get("api.restartRequired")), BorderLayout.SOUTH);
        add(configuration, BorderLayout.NORTH);

        DefaultListModel<Entry> model = new DefaultListModel<>();
        JList<Entry> list = new JList<>(model);
        list.setName("settings-api-methods");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(48);
        list.setCellRenderer((items, entry, index, selected, focus) -> {
            JPanel cell = new JPanel(new BorderLayout(0, 3));
            cell.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            cell.setBackground(selected ? items.getSelectionBackground() : items.getBackground());
            JLabel name = new JLabel(entry.name());
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setForeground(selected ? items.getSelectionForeground() : items.getForeground());
            JLabel type = new JLabel(entry.type());
            type.setForeground(selected ? items.getSelectionForeground() : UIManager.getColor("Label.disabledForeground"));
            cell.add(name, BorderLayout.NORTH); cell.add(type);
            return cell;
        });
        CodePane text = new CodePane();
        text.setName("settings-api-documentation");
        JPanel detail = new JPanel(new BorderLayout(0, 8));
        detail.setOpaque(false);
        JButton copyAll = new JButton(i18n.get("api.docs.copy"));
        copyAll.addActionListener(event -> copy(copyAll, text.getText(), i18n));
        JPanel copies = row(new JLabel("JSON-RPC"), copyAll);
        String[] keys = {"api.docs.copyMethod", "api.docs.copyEndpoint", "api.docs.copyRequest", "api.docs.copySchema"};
        for (int index = 0; index < keys.length; index++) {
            int section = index;
            JButton button = new JButton(i18n.get(keys[index]));
            button.addActionListener(event -> {
                Entry selected = list.getSelectedValue();
                if (selected == null) return;
                String value = switch (section) { case 0 -> selected.method(); case 1 -> selected.endpoint(); case 2 -> selected.request(); default -> selected.schema(); };
                copy(button, value, i18n);
            });
            copies.add(button);
        }
        detail.add(copies, BorderLayout.NORTH);
        detail.add(new JScrollPane(text));
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) return;
            Entry selected = list.getSelectedValue();
            text.showCode(selected == null ? "" : selected.detail());
            copyAll.setEnabled(selected != null);
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), detail);
        split.setResizeWeight(.3); split.setDividerLocation(230); split.setBorder(BorderFactory.createEmptyBorder());
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", i18n.get("api.docs.search"));
        search.setName("settings-api-search");
        Runnable filter = () -> {
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            model.addAll(entries.stream().filter(entry -> (entry.name() + " " + entry.type() + " " + entry.description()).toLowerCase(Locale.ROOT).contains(query)).toList());
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        watch(search, filter);
        JPanel browser = new JPanel(new BorderLayout(0, 12)); browser.setOpaque(false);
        browser.add(search, BorderLayout.NORTH); browser.add(split);
        browser.add(new JLabel(i18n.get("api.docs.hint")), BorderLayout.SOUTH);
        add(browser);
        IdeaFormStyle.applyTree(configuration); IdeaFormStyle.applyTree(copies); IdeaFormStyle.applyTree(search);
        filter.run();
    }

    private static void watch(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { action.run(); }
            public void removeUpdate(DocumentEvent event) { action.run(); }
            public void changedUpdate(DocumentEvent event) { action.run(); }
        });
    }
    private static JPanel row(JComponent... controls) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4)); panel.setOpaque(false);
        for (JComponent control : controls) panel.add(control);
        return panel;
    }
    private static JLabel heading(String text) {
        JLabel label = new JLabel(text); label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1)); return label;
    }
    private static void copy(JButton button, String value, I18nService i18n) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value == null ? "" : value), null);
        if (Boolean.TRUE.equals(button.getClientProperty("copy.feedback"))) return;
        String previous = button.getText(); button.setText(i18n.get("api.copy.copied")); button.putClientProperty("copy.feedback", true);
        Timer reset = new Timer(1400, event -> { button.setText(previous); button.putClientProperty("copy.feedback", false); });
        reset.setRepeats(false); reset.start();
    }

    private static final class CodePane extends JTextPane {
        private static final Pattern TOKENS = Pattern.compile("(https?://[^\\s]+)|(\"(?:\\\\.|[^\"\\\\])*\")|\\b(true|false|null)\\b|(?<![\\w.])(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b|([{}\\[\\],:])");
        private String code;
        CodePane() { setEditable(false); setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); }
        void showCode(String value) { code = value; setText(value); highlight(); setCaretPosition(0); }
        @Override public void updateUI() { super.updateUI(); if (code != null) highlight(); }
        private void highlight() {
            var document = getStyledDocument();
            SimpleAttributeSet normal = new SimpleAttributeSet();
            StyleConstants.setForeground(normal, UIManager.getColor("TextPane.foreground"));
            StyleConstants.setFontFamily(normal, Font.MONOSPACED);
            document.setCharacterAttributes(0, document.getLength(), normal, true);
            var matcher = TOKENS.matcher(code);
            while (matcher.find()) {
                Color color = matcher.group(1) != null ? UIManager.getColor("JLShell.formLink")
                        : matcher.group(2) != null ? UIManager.getColor("Label.disabledForeground")
                        : matcher.group(3) != null ? UIManager.getColor("Component.focusColor") : UIManager.getColor("TextPane.foreground");
                SimpleAttributeSet token = new SimpleAttributeSet(); StyleConstants.setForeground(token, color);
                document.setCharacterAttributes(matcher.start(), matcher.end() - matcher.start(), token, false);
            }
        }
    }
}
