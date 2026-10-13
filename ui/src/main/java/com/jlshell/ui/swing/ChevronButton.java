package com.jlshell.ui.swing;

import javax.swing.JButton;
import java.awt.Graphics;

/** IDEA 项目/运行配置选择器的文字和尾部 chevron 保持独立，不使用字体箭头。 */
final class ChevronButton extends JButton {
    ChevronButton() { this(""); }
    ChevronButton(String text) { super(text); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        new WorkbenchIcon(WorkbenchIcon.Kind.CHEVRON_DOWN)
                .paintIcon(this, graphics, getWidth() - 20, (getHeight() - 16) / 2);
    }
}
