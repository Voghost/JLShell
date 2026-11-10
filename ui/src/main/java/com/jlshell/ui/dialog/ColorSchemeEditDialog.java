package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.swing.SwingFormDialog;
import com.jlshell.ui.theme.ThemeService;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Native editor for custom terminal colors. */
public final class ColorSchemeEditDialog {
    private ColorSchemeEditDialog() {}

    public static java.util.concurrent.CompletableFuture<TerminalColorScheme> showAsync(ThemeService themes, I18nService i18n,
                                            TerminalColorScheme initial, boolean isNew) {
        TerminalColorScheme base = initial == null ? TerminalColorScheme.dark() : initial;
        return SwingFormDialog.showAsync(isNew ? i18n.get("preferences.terminal.colorScheme.new")
                        : i18n.get("preferences.terminal.colorScheme.edit"), "jlshell.colors", i18n, () -> {
            JPanel fields = new JPanel(new GridBagLayout());
            fields.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            JTextField name = new JTextField(initial == null ? "" : base.name(), 22);
            name.setName("colors.name"); name.setEditable(isNew);
            JSlider opacity = new JSlider(0, 100, (int) Math.round(base.opacity() * 100));
            opacity.setName("colors.opacity");
            List<Color> colors = new ArrayList<>(List.of(base.background(), base.foreground(), base.cursorColor(), base.selectionBackground()));
            colors.addAll(List.of(base.ansiColors()));
            ColorSchemePreview preview = new ColorSchemePreview(); preview.update(base);
            Runnable refresh = () -> preview.update(build(name.getText().isBlank() ? base.name() : name.getText().strip(), colors, base, opacity.getValue() / 100d));
            opacity.addChangeListener(event -> refresh.run());
            String[] names = {"Background", "Foreground", "Cursor", "Selection", "Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White",
                    "Bright black", "Bright red", "Bright green", "Bright yellow", "Bright blue", "Bright magenta", "Bright cyan", "Bright white"};
            add(fields, 0, 0, new JLabel(i18n.get("highlight.name"))); add(fields, 1, 0, name);
            add(fields, 0, 1, new JLabel(i18n.get("preferences.terminal.colorScheme.opacity"))); add(fields, 1, 1, opacity);
            for (int index = 0; index < colors.size(); index++) {
                int target = index;
                JButton picker = new JButton(" "); picker.setName("colors.color." + index);
                picker.setBackground(colors.get(index)); picker.setOpaque(true);
                picker.setPreferredSize(new Dimension(90, 24));
                picker.addActionListener(event -> {
                    Color selected = JColorChooser.showDialog(fields, names[target], colors.get(target));
                    if (selected != null) { colors.set(target, selected); picker.setBackground(selected); refresh.run(); }
                });
                int column = (index % 2) * 2;
                add(fields, column, index / 2 + 2, new JLabel(names[index])); add(fields, column + 1, index / 2 + 2, picker);
            }
            JLabel error = new JLabel(" "); error.setForeground(new Color(200, 70, 70));
            add(fields, 0, 12, error);
            JPanel body = new JPanel(new BorderLayout(0, 12)); body.add(fields);
            body.add(preview, BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(body, () -> {
                String value = name.getText().strip();
                if (value.isEmpty()) { error.setText(i18n.get("highlight.invalid")); name.requestFocusInWindow(); return null; }
                return build(value, colors, base, opacity.getValue() / 100d);
            });
        }, 720, 680);
    }

    private static TerminalColorScheme build(String name, List<Color> colors, TerminalColorScheme base, double opacity) {
        return new TerminalColorScheme(name, colors.get(0), colors.get(1), colors.get(2), colors.get(3),
                base.selectionForeground(), base.hyperlinkColor(), base.searchMatchBackground(), base.searchMatchForeground(),
                colors.get(4), colors.get(5), colors.get(6), colors.get(7), colors.get(8), colors.get(9), colors.get(10), colors.get(11),
                colors.get(12), colors.get(13), colors.get(14), colors.get(15), colors.get(16), colors.get(17), colors.get(18), colors.get(19), opacity);
    }

    private static void add(JPanel form, int column, int row, JComponent component) {
        GridBagConstraints c = new GridBagConstraints(); c.gridx = column; c.gridy = row;
        c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 4, 5, 12);
        c.weightx = column % 2 == 1 ? 1 : 0; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(component, c);
    }
}
