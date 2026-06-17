package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalColorScheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * 终端配色方案预览组件。
 * 显示缩略版终端输出：背景 + 前景文字 + 16 色 ANSI 色块。
 */
public class ColorSchemePreview extends VBox {

    private final VBox terminalBox;
    private final TextFlow sampleText;
    private final HBox colorRow1;
    private final HBox colorRow2;

    public ColorSchemePreview() {
        setSpacing(4);
        setPadding(new Insets(8));

        terminalBox = new VBox(4);
        terminalBox.setPadding(new Insets(6));
        terminalBox.setStyle("-fx-background-radius:4;");

        sampleText = new TextFlow();
        sampleText.setMaxWidth(Double.MAX_VALUE);

        colorRow1 = new HBox(2);
        colorRow1.setAlignment(Pos.CENTER_LEFT);
        colorRow2 = new HBox(2);
        colorRow2.setAlignment(Pos.CENTER_LEFT);

        terminalBox.getChildren().addAll(sampleText, colorRow1, colorRow2);
        getChildren().add(terminalBox);
    }

    public void update(TerminalColorScheme scheme) {
        if (scheme == null) return;

        Color bg = toFxColor(scheme.background(), scheme.opacity());
        Color fg = toFxColor(scheme.foreground(), 1.0);

        String bgCss = toRgbCss(scheme.background(), scheme.opacity());
        String fgCss = toRgbCss(scheme.foreground(), 1.0);

        terminalBox.setStyle("-fx-background-color:" + bgCss + ";-fx-background-radius:4;");

        // Sample text
        Text prompt = new Text("user@host ~ $ ");
        prompt.setFill(fg);
        prompt.setFont(Font.font("Monospaced", 11));

        Text cmd = new Text("ls");
        cmd.setFill(toFxColor(scheme.green(), 1.0));
        cmd.setFont(Font.font("Monospaced", 11));

        sampleText.getChildren().setAll(prompt, cmd);

        // 16 ANSI color swatches
        colorRow1.getChildren().setAll(
                swatch(scheme.black()), swatch(scheme.red()), swatch(scheme.green()),
                swatch(scheme.yellow()), swatch(scheme.blue()), swatch(scheme.purple()),
                swatch(scheme.cyan()), swatch(scheme.white()));

        colorRow2.getChildren().setAll(
                swatch(scheme.brightBlack()), swatch(scheme.brightRed()), swatch(scheme.brightGreen()),
                swatch(scheme.brightYellow()), swatch(scheme.brightBlue()), swatch(scheme.brightPurple()),
                swatch(scheme.brightCyan()), swatch(scheme.brightWhite()));
    }

    private Rectangle swatch(java.awt.Color c) {
        Rectangle rect = new Rectangle(16, 12);
        rect.setFill(toFxColor(c, 1.0));
        rect.setStyle("-fx-stroke:derive(-fx-text-fill,40%);-fx-stroke-width:0.5;");
        return rect;
    }

    private static Color toFxColor(java.awt.Color c, double opacity) {
        return new Color(c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0, opacity);
    }

    private static String toRgbCss(java.awt.Color c, double opacity) {
        return String.format("rgba(%d,%d,%d,%.2f)", c.getRed(), c.getGreen(), c.getBlue(), opacity);
    }
}
