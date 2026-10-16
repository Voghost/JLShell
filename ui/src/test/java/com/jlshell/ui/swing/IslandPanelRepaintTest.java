package com.jlshell.ui.swing;

import com.jlshell.ui.theme.AppTheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Child repaint must not bypass the rounded clip, as terminal/tree updates used to do. */
@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class IslandPanelRepaintTest {
    @Test void childImmediateRepaintKeepsAllFourCornersClipped() throws Exception {
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<JPanel> child = new AtomicReference<>();
        AtomicReference<Rectangle> bounds = new AtomicReference<>();
        Color outside = new Color(45, 96, 135), inside = new Color(105, 43, 61);
        SwingUtilities.invokeAndWait(() -> {
            SwingTheme.install(AppTheme.DARK, "#3574f0", "SansSerif", 13);
            JFrame window = new JFrame();
            window.setUndecorated(true);
            window.setAlwaysOnTop(true);
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(outside);
            root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            IslandPanel rounded = new IslandPanel(new BorderLayout(), "canvas");
            JPanel content = new JPanel();
            content.setBackground(inside);
            rounded.add(content);
            root.add(rounded);
            window.setContentPane(root);
            window.setSize(240, 180);
            window.setLocationRelativeTo(null);
            window.setVisible(true);
            frame.set(window);
            child.set(content);
            bounds.set(new Rectangle(content.getLocationOnScreen(), content.getSize()));
        });
        try {
            Robot robot = new Robot();
            robot.waitForIdle();
            robot.delay(100);
            for (int repaint = 0; repaint < 2; repaint++) {
                SwingUtilities.invokeAndWait(() -> child.get().paintImmediately(0, 0, child.get().getWidth(), child.get().getHeight()));
                robot.waitForIdle();
                robot.delay(80);
                Rectangle area = bounds.get();
                for (Point corner : new Point[]{new Point(0, 0), new Point(area.width - 1, 0),
                        new Point(0, area.height - 1), new Point(area.width - 1, area.height - 1)})
                    assertEquals(outside, robot.getPixelColor(area.x + corner.x, area.y + corner.y), "Child repaint must respect all island corners");
                assertEquals(inside, robot.getPixelColor(area.x + area.width / 2, area.y + area.height / 2));
            }
        } finally { SwingUtilities.invokeAndWait(() -> frame.get().dispose()); }
    }
}
