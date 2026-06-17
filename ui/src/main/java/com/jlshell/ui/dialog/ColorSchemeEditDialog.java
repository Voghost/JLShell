package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.ui.theme.ThemeService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Color;

/**
 * 编辑/创建自定义终端配色方案的对话框。
 */
public class ColorSchemeEditDialog {

    public static TerminalColorScheme show(Stage owner, ThemeService themeService,
                                            TerminalColorScheme initial, boolean isNew) {
        Dialog<TerminalColorScheme> dialog = new Dialog<>();
        dialog.setTitle(isNew ? "New Color Scheme" : "Edit Color Scheme");
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        themeService.applyToDialog(dialog);
        dialog.getDialogPane().setPrefWidth(520);

        TextField nameField = new TextField(initial != null ? initial.name() : "");
        nameField.setDisable(!isNew);
        nameField.setPrefWidth(200);

        // Opacity
        double initOpacity = initial != null ? initial.opacity() : 1.0;
        Slider opacitySlider = new Slider(0.0, 1.0, initOpacity);
        opacitySlider.setPrefWidth(180);
        Label opacityLabel = new Label(String.format("%.0f%%", initOpacity * 100));
        opacitySlider.valueProperty().addListener((o, ov, nv) ->
                opacityLabel.setText(String.format("%.0f%%", nv.doubleValue() * 100)));

        // Color pickers
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12, 16, 8, 16));

        int row = 0;
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row, 3, 1);
        row++;

        grid.add(new Label("Opacity:"), 0, row);
        grid.add(new HBox(8, opacitySlider, opacityLabel), 1, row, 3, 1);
        row++;

        // Special colors
        grid.add(new Label("Background:"), 0, row);
        ColorPicker bgPicker = picker(initial != null ? initial.background() : Color.BLACK);
        grid.add(bgPicker, 1, row);
        grid.add(new Label("Foreground:"), 2, row);
        ColorPicker fgPicker = picker(initial != null ? initial.foreground() : Color.WHITE);
        grid.add(fgPicker, 3, row);
        row++;

        grid.add(new Label("Cursor:"), 0, row);
        ColorPicker cursorPicker = picker(initial != null ? initial.cursorColor() : Color.WHITE);
        grid.add(cursorPicker, 1, row);
        grid.add(new Label("Selection BG:"), 2, row);
        ColorPicker selBgPicker = picker(initial != null ? initial.selectionBackground() : new Color(0x2d, 0x5f, 0xa3));
        grid.add(selBgPicker, 3, row);
        row++;

        // ANSI colors — Standard row
        Label ansiHeader = new Label("ANSI Colors");
        ansiHeader.setStyle("-fx-font-weight:bold;");
        grid.add(ansiHeader, 0, row, 4, 1);
        row++;

        ColorPicker[] ansiPickers = new ColorPicker[16];
        String[] names = {"Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White"};
        Color[] defaults = {Color.BLACK, new Color(0xcd, 0, 0), new Color(0, 0xcd, 0), new Color(0xcd, 0xcd, 0),
                new Color(0x1e, 0x90, 0xff), new Color(0xcd, 0, 0xcd), new Color(0, 0xcd, 0xcd), new Color(0xe5, 0xe5, 0xe5)};

        for (int i = 0; i < 8; i++) {
            Color c = initial != null ? initial.ansiColors()[i] : defaults[i];
            ansiPickers[i] = picker(c);
            grid.add(new Label(names[i] + ":"), 0, row);
            grid.add(ansiPickers[i], 1, row);
            Color bc = initial != null ? initial.ansiColors()[i + 8] : defaults[i].brighter();
            ansiPickers[i + 8] = picker(bc);
            grid.add(new Label("Bright " + names[i] + ":"), 2, row);
            grid.add(ansiPickers[i + 8], 3, row);
            row++;
        }

        // Preview
        ColorSchemePreview preview = new ColorSchemePreview();
        if (initial != null) preview.update(initial);
        grid.add(new Label("Preview:"), 0, row);
        grid.add(preview, 1, row, 3, 1);

        // Live preview update
        Runnable updatePreview = () -> {
            TerminalColorScheme s = buildScheme(nameField.getText(), bgPicker, fgPicker, cursorPicker,
                    selBgPicker, ansiPickers, opacitySlider.getValue(), initial);
            preview.update(s);
        };
        bgPicker.setOnAction(e -> updatePreview.run());
        fgPicker.setOnAction(e -> updatePreview.run());
        cursorPicker.setOnAction(e -> updatePreview.run());
        selBgPicker.setOnAction(e -> updatePreview.run());
        for (ColorPicker p : ansiPickers) p.setOnAction(e -> updatePreview.run());
        opacitySlider.valueProperty().addListener((o, ov, nv) -> updatePreview.run());

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                String name = nameField.getText().trim();
                if (name.isBlank()) return null;
                return buildScheme(name, bgPicker, fgPicker, cursorPicker,
                        selBgPicker, ansiPickers, opacitySlider.getValue(), initial);
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }

    private static TerminalColorScheme buildScheme(String name, ColorPicker bg, ColorPicker fg,
                                                    ColorPicker cursor, ColorPicker selBg,
                                                    ColorPicker[] ansi, double opacity,
                                                    TerminalColorScheme initial) {
        return new TerminalColorScheme(
                name,
                toAwtColor(bg.getValue()), toAwtColor(fg.getValue()), toAwtColor(cursor.getValue()),
                toAwtColor(selBg.getValue()),
                initial != null ? initial.selectionForeground() : Color.WHITE,
                initial != null ? initial.hyperlinkColor() : new Color(0x4d, 0x9c, 0xf8),
                initial != null ? initial.searchMatchBackground() : new Color(0xe0, 0xb1, 0x2d),
                initial != null ? initial.searchMatchForeground() : new Color(0x1e, 0x1f, 0x22),
                toAwtColor(ansi[0].getValue()), toAwtColor(ansi[1].getValue()),
                toAwtColor(ansi[2].getValue()), toAwtColor(ansi[3].getValue()),
                toAwtColor(ansi[4].getValue()), toAwtColor(ansi[5].getValue()),
                toAwtColor(ansi[6].getValue()), toAwtColor(ansi[7].getValue()),
                toAwtColor(ansi[8].getValue()), toAwtColor(ansi[9].getValue()),
                toAwtColor(ansi[10].getValue()), toAwtColor(ansi[11].getValue()),
                toAwtColor(ansi[12].getValue()), toAwtColor(ansi[13].getValue()),
                toAwtColor(ansi[14].getValue()), toAwtColor(ansi[15].getValue()),
                opacity
        );
    }

    private static ColorPicker picker(java.awt.Color c) {
        ColorPicker picker = new ColorPicker();
        picker.setValue(javafx.scene.paint.Color.rgb(c.getRed(), c.getGreen(), c.getBlue()));
        picker.setPrefWidth(80);
        return picker;
    }

    private static java.awt.Color toAwtColor(javafx.scene.paint.Color c) {
        return new java.awt.Color((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
    }
}
