package com.jlshell.app;

import com.jlshell.ui.swing.SwingStartupFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jlshell.ui.smoke", matches = "true")
class StartupUiSmokeTest {
    private static void capture(Window window, String name) throws Exception {
        Path output = Path.of("..", "ui", "target", "review", name + ".png");
        Files.createDirectories(output.getParent());
        BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { window.paint(graphics); } finally { graphics.dispose(); }
        ImageIO.write(image, "png", output.toFile());
    }

    @Test void startupWindowsAreNativeAndCloseReliably() throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        StartupSplash splash = new StartupSplash();
        AtomicInteger exits = new AtomicInteger();
        JDialog[] failure = new JDialog[1];
        try {
            splash.show();
            splash.firstFrame().get(10, java.util.concurrent.TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                Window window = java.util.Arrays.stream(Window.getWindows())
                        .filter(w -> "startup.splash".equals(w.getName()) && w.isShowing()).findFirst().orElseThrow();
                assertEquals(520, window.getWidth());
                try { capture(window, "startup-splash"); } catch (Exception error) { throw new RuntimeException(error); }
            });
            splash.hide();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(w -> "startup.splash".equals(w.getName()) && w.isDisplayable()));
                failure[0] = SwingStartupFailure.create(new IllegalStateException("无法初始化本地工作空间：配置数据库暂时不可访问。", new java.io.IOException("Database is locked")), exits::incrementAndGet);
                failure[0].setVisible(true);
                try { capture(failure[0], "startup-failure"); } catch (Exception error) { throw new RuntimeException(error); }
                assertTrue(failure[0].getWidth() >= 600);
                assertTrue(failure[0].getHeight() < 450, "Collapsed diagnostics must stay compact");
                JToggleButton toggle = find(failure[0], JToggleButton.class);
                int before = failure[0].getHeight(); toggle.doClick();
                assertTrue(failure[0].getHeight() > before + 150);
                try { capture(failure[0], "startup-failure-details"); } catch (Exception error) { throw new RuntimeException(error); }
                failure[0].dispatchEvent(new WindowEvent(failure[0], WindowEvent.WINDOW_CLOSING));
                failure[0].dispatchEvent(new WindowEvent(failure[0], WindowEvent.WINDOW_CLOSING));
                assertEquals(1, exits.get());
                assertFalse(failure[0].isDisplayable());
            });
            StartupSplash cancelled = new StartupSplash(); cancelled.hide(); cancelled.show();
            SwingUtilities.invokeAndWait(() -> assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(w -> "startup.splash".equals(w.getName()) && w.isShowing())));
        } finally {
            splash.hide();
            SwingUtilities.invokeAndWait(() -> { if (failure[0] != null) failure[0].dispose(); });
            Locale.setDefault(previous);
        }
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            T found = find(child, type); if (found != null) return found;
        }
        return null;
    }
}
