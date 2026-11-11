package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalHighlightConfig;
import com.jlshell.terminal.model.TerminalHighlightConfig.Rule;
import com.jlshell.terminal.support.TerminalLogHighlighter;
import com.jlshell.ui.service.I18nService;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native editor using the terminal's bounded matcher for validation and preview. */
public final class TerminalHighlightEditor {
    private TerminalHighlightEditor() {}
    public static JComponent create(TerminalHighlightConfig initial, I18nService i18n, Consumer<TerminalHighlightConfig> changed) {
        JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setOpaque(false);
        JCheckBox enabled = new JCheckBox(i18n.get("highlight.enabled"), initial.enabled()); enabled.setName("settings-highlight-enabled");
        List<Rule> rules = new ArrayList<>(initial.rules());
        DefaultComboBoxModel<Rule> model = new DefaultComboBoxModel<>(rules.toArray(Rule[]::new));
        JComboBox<Rule> selected = new JComboBox<>(model); selected.setName("settings-highlight-rule");
        JTextField name = new JTextField(24); name.setName("settings-highlight-name");
        JTextField expression = new JTextField(32); expression.setName("settings-highlight-expression");
        JCheckBox active = new JCheckBox(i18n.get("highlight.ruleEnabled"));
        JCheckBox regex = new JCheckBox(i18n.get("highlight.regex"));
        JCheckBox ignoreCase = new JCheckBox(i18n.get("highlight.ignoreCase"));
        JButton color = new JButton(" "); color.setName("settings-highlight-color"); color.setOpaque(true);
        Color[] selectedColor = {new Color(0x38BDF8)};
        JLabel status = new JLabel(" "); status.setName("settings-highlight-error");
        JTextField sample = new JTextField("2026-10-30 18:06:00 INFO connected | WARN slow query | ERROR failed | SUCCESS");
        sample.setName("settings-highlight-sample");
        JTextPane preview = new JTextPane(); preview.setName("settings-highlight-preview"); preview.setEditable(false);
        preview.setBackground(new Color(0x18191B)); preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        Runnable refresh = () -> {
            preview.setText(sample.getText());
            var document = preview.getStyledDocument();
            var style = new javax.swing.text.SimpleAttributeSet(); javax.swing.text.StyleConstants.setForeground(style, new Color(0xD4D4D4));
            document.setCharacterAttributes(0, document.getLength(), style, true);
            for (var span : new TerminalLogHighlighter(new TerminalHighlightConfig(enabled.isSelected(), rules)).match(sample.getText())) {
                var attributes = new javax.swing.text.SimpleAttributeSet(); javax.swing.text.StyleConstants.setForeground(attributes, span.color());
                document.setCharacterAttributes(span.start(), span.end() - span.start(), attributes, false);
            }
        };
        Runnable publish = () -> { changed.accept(new TerminalHighlightConfig(enabled.isSelected(), rules)); status.setText(" "); refresh.run(); };
        Runnable load = () -> {
            Rule rule = (Rule) selected.getSelectedItem();
            if (rule == null) return;
            name.setText(rule.name()); expression.setText(rule.expression()); active.setSelected(rule.enabled());
            regex.setSelected(rule.regex()); ignoreCase.setSelected(rule.ignoreCase());
            selectedColor[0] = Color.decode(rule.color()); color.setBackground(selectedColor[0]); status.setText(" ");
        };
        selected.addActionListener(event -> load.run());
        color.addActionListener(event -> {
            Color next = JColorChooser.showDialog(panel, i18n.get("highlight.color"), selectedColor[0]);
            if (next != null) { selectedColor[0] = next; color.setBackground(next); }
        });
        JButton save = button(i18n, "highlight.saveRule", () -> {
            String hex = String.format(Locale.ROOT, "#%02X%02X%02X", selectedColor[0].getRed(), selectedColor[0].getGreen(), selectedColor[0].getBlue());
            Rule next = new Rule(name.getText().strip(), active.isSelected(), regex.isSelected(), ignoreCase.isSelected(), expression.getText(), hex);
            try {
                if (next.name().isBlank()) throw new IllegalArgumentException();
                new TerminalLogHighlighter(new TerminalHighlightConfig(true, List.of(next)));
            } catch (RuntimeException error) { status.setText(i18n.get("highlight.invalid")); return; }
            int index = selected.getSelectedIndex();
            if (index < 0 && rules.size() >= TerminalHighlightConfig.MAX_RULES) { status.setText(i18n.get("highlight.limit")); return; }
            if (index < 0) { rules.add(next); index = rules.size() - 1; }
            else rules.set(index, next);
            model.removeAllElements(); rules.forEach(model::addElement); selected.setSelectedIndex(index);
            publish.run(); status.setText(i18n.get("highlight.saved"));
        });
        save.setName("settings-highlight-save");
        JButton add = button(i18n, "highlight.add", () -> {
            selected.setSelectedIndex(-1); name.setText(""); expression.setText("");
            active.setSelected(true); regex.setSelected(false); ignoreCase.setSelected(true);
            selectedColor[0] = new Color(0x38BDF8); color.setBackground(selectedColor[0]); status.setText(i18n.get("highlight.draft"));
        });
        JButton remove = button(i18n, "highlight.remove", () -> {
            int index = selected.getSelectedIndex();
            if (index >= 0) { rules.remove(index); model.removeElementAt(index); selected.setSelectedIndex(Math.min(index, rules.size() - 1)); publish.run(); }
        });
        JButton up = button(i18n, "highlight.up", () -> {
            int index = selected.getSelectedIndex();
            if (index > 0) {
                Rule rule = rules.remove(index); rules.add(index - 1, rule);
                model.removeAllElements(); rules.forEach(model::addElement); selected.setSelectedIndex(index - 1); publish.run();
            }
        });
        JButton reset = button(i18n, "highlight.reset", () -> {
            rules.clear(); rules.addAll(TerminalHighlightConfig.defaults().rules());
            model.removeAllElements(); rules.forEach(model::addElement); selected.setSelectedIndex(0); publish.run();
        });
        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 10)); fields.setOpaque(false);
        fields.add(new JLabel(i18n.get("highlight.name"))); fields.add(name);
        fields.add(new JLabel(i18n.get("highlight.expression"))); fields.add(expression);
        fields.add(new JLabel(i18n.get("highlight.color"))); fields.add(color);
        enabled.addActionListener(event -> publish.run());
        sample.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { refresh.run(); }
            public void removeUpdate(DocumentEvent event) { refresh.run(); }
            public void changedUpdate(DocumentEvent event) { refresh.run(); }
        });
        for (JComponent component : List.of(enabled, selected, row(add, remove, up, reset), fields, row(active, regex, ignoreCase),
                row(save), status, new JLabel(i18n.get("highlight.preview")), sample, new JScrollPane(preview))) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT); panel.add(component); panel.add(Box.createVerticalStrut(10));
        }
        load.run(); refresh.run(); return panel;
    }

    private static JButton button(I18nService i18n, String key, Runnable action) {
        JButton button = new JButton(i18n.get(key)); button.addActionListener(event -> action.run()); return button;
    }
    private static JPanel row(JComponent... controls) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); row.setOpaque(false);
        for (JComponent control : controls) row.add(control); return row;
    }
}
