package com.jlshell.ui.dialog;

import com.jlshell.terminal.model.TerminalColorScheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
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
    private final Text prompt = new Text("user@host ~ $ ");
    private final Text command = new Text("ls");
    private final Rectangle[] swatches = new Rectangle[16];

    public ColorSchemePreview() {
        setSpacing(4);
        setPadding(new Insets(8));

        terminalBox = new VBox(4);
        terminalBox.setPadding(new Insets(6));

        sampleText = new TextFlow();
        sampleText.setMaxWidth(Double.MAX_VALUE);
        prompt.setFont(Font.font("Monospaced", 11));
        command.setFont(Font.font("Monospaced", 11));
        sampleText.getChildren().addAll(prompt, command);

        colorRow1 = new HBox(2);
        colorRow1.setAlignment(Pos.CENTER_LEFT);
        colorRow2 = new HBox(2);
        colorRow2.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < swatches.length; i++) {
            swatches[i] = new Rectangle(16, 12);
            swatches[i].setStyle("-fx-stroke:derive(-fx-text-fill,40%);-fx-stroke-width:0.5;");
            (i < 8 ? colorRow1 : colorRow2).getChildren().add(swatches[i]);
        }

        terminalBox.getChildren().addAll(sampleText, colorRow1, colorRow2);
        getChildren().add(terminalBox);
    }

    public void update(TerminalColorScheme scheme) {
        if (scheme == null) return;

        Color bg = toFxColor(scheme.background(), scheme.opacity());
        Color fg = toFxColor(scheme.foreground(), 1.0);

        // 拖动仅更新绘制属性，避免逐帧格式化 CSS 并重新运行样式解析/布局。
        terminalBox.setBackground(new Background(new BackgroundFill(bg, new CornerRadii(4), Insets.EMPTY)));

        // Sample text
        prompt.setFill(fg);
        command.setFill(toFxColor(scheme.green(), 1.0));
        java.awt.Color[] colors = {scheme.black(), scheme.red(), scheme.green(), scheme.yellow(), scheme.blue(),
                scheme.purple(), scheme.cyan(), scheme.white(), scheme.brightBlack(), scheme.brightRed(),
                scheme.brightGreen(), scheme.brightYellow(), scheme.brightBlue(), scheme.brightPurple(),
                scheme.brightCyan(), scheme.brightWhite()};
        for (int i = 0; i < swatches.length; i++) swatches[i].setFill(toFxColor(colors[i], 1.0));
    }

    private static Color toFxColor(java.awt.Color c, double opacity) {
        return new Color(c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0, opacity);
    }

}
