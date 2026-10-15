package com.jlshell.ui.swing;

import com.jlshell.ui.theme.AppTheme;
import com.jlshell.terminal.support.SwingExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class TooltipUiSmokeTest {
    @Test void ordinaryAndHtmlTooltipsUseTransparentCornersInBothThemes() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            for (AppTheme theme : new AppTheme[]{AppTheme.LIGHT, AppTheme.DARK}) {
                SwingTheme.install(theme, "#3574f0", "SansSerif", 13);
                JFrame owner = new JFrame("Tooltip check");
                JButton button = new JButton("Owner"); owner.add(button); owner.setBounds(100, 100, 360, 180); owner.setVisible(true);
                try {
                    for (boolean html : new boolean[]{false, true}) {
                        JToolTip tip = button.createToolTip();
                        tip.setTipText(html ? "<html>长连接名称<br>user@host:22</html>" : "上一级");
                        Popup popup = PopupFactory.getSharedInstance().getPopup(button, tip, 150, 160);
                        popup.show();
                        try {
                            Window window = SwingUtilities.getWindowAncestor(tip);
                            assertEquals("idea.tooltipWindow", window.getName());
                            assertEquals(0, window.getBackground().getAlpha());
                            assertFalse(window.getFocusableWindowState(), "Hover tips must not steal terminal input focus");
                            JRootPane root = ((JWindow) window).getRootPane();
                            BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g = image.createGraphics(); root.printAll(g); g.dispose();
                            assertEquals(0, image.getRGB(0, 0) >>> 24, "Outer corners must remain transparent");
                            Color body = new Color(image.getRGB(image.getWidth()/2, image.getHeight()-15), true);
                            assertEquals(SwingTheme.color("tooltip").getRGB(), body.getRGB());
                            try {
                                Path file = Path.of("../ui/target/review/tooltip-" + theme.name().toLowerCase() + (html ? "-html" : "") + ".png");
                                Files.createDirectories(file.getParent()); javax.imageio.ImageIO.write(image, "png", file.toFile());
                            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                        } finally { popup.hide(); }
                        assertNull(tip.getParent(), "Disposed tips should be released from their host surface");
                    }
                } finally { owner.dispose(); }
            }
        }).get(15, TimeUnit.SECONDS);
    }
}
