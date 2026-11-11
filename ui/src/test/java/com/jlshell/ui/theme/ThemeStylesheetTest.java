package com.jlshell.ui.theme;

import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.swing.SwingTheme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThemeStylesheetTest {
    @Test void bothNativePalettesProvideReadableTextAndSharedColors() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            for (AppTheme theme : AppTheme.values()) {
                SwingTheme.install(theme, "#3574f0", null, 13);
                var text = javax.swing.UIManager.getColor("Label.foreground");
                var background = javax.swing.UIManager.getColor("Panel.background");
                assertNotNull(text); assertNotNull(background);
                assertTrue(Math.abs(luminance(text) - luminance(background)) > .35);
                assertNotNull(SwingTheme.color("secondary"));
            }
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    private static double luminance(java.awt.Color color) {
        return (.2126 * color.getRed() + .7152 * color.getGreen() + .0722 * color.getBlue()) / 255;
    }
}
