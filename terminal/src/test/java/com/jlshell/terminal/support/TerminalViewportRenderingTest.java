package com.jlshell.terminal.support;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import java.awt.Graphics;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalViewportRenderingTest {
    @Test void cachedTerminalMatchesFullPaintDuringScrollOutputAndResize() throws Exception {
        var original = RefreshableTerminalPanel.class.getDeclaredMethod("paintGridViewport", Graphics.class);
        original.setAccessible(true);
        var cacheField = RefreshableTerminalPanel.class.getDeclaredField("viewportCache");
        cacheField.setAccessible(true);
        var surfaceField = TerminalViewportCache.class.getDeclaredField("surface");
        surfaceField.setAccessible(true);
        SwingExecutors.runOnEdtAsync(() -> {
            for (int scale : new int[]{1, 2, 0}) {
                if (scale == 0 && java.awt.GraphicsEnvironment.isHeadless()) continue;
                var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 14, false, 1.2), TerminalColorScheme.dark());
                var style = new StyleState(); style.setDefaultStyle(settings.defaultTextStyle());
                var buffer = new TerminalTextBuffer(70, 12, style);
                var panel = new RefreshableTerminalPanel(settings, buffer, style, settings, key -> key, new TerminalWheelInput(true));
                var bar = new javax.swing.JScrollBar(); panel.init(bar); panel.setSize(panel.getPreferredSize());
                var terminal = new JediTerminal(panel, buffer, style); panel.setCoordAccessor(terminal);
                try {
                    for (int i = 0; i < 80; i++) {
                        terminal.characterAttributes(i % 3 == 0 ? new TextStyle(TerminalColor.rgb(30, 180, 200), null) : settings.defaultTextStyle());
                        terminal.writeCharacters("row=" + i + " INFO 测试日志 WARN timeout ERROR failed");
                        terminal.carriageReturn(); terminal.newLine();
                    }
                    for (int i = 0; i < (scale == 0 ? 220 : 45); i++) {
                        if (i == 25) { terminal.writeCharacters("new output"); terminal.carriageReturn(); terminal.newLine(); }
                        if (i == 35) panel.setSize(panel.getWidth() + 12, panel.getHeight() + 3);
                        double delta = scale == 0 ? (i % 100 < 60 ? -2.37 : 1.71) : (i < 20 ? -.37 : .13);
                        panel.handleMouseWheelEvent(new MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL,
                                System.currentTimeMillis(), 0, 20, 20, 20, 20, 0, false,
                                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int)Math.signum(delta), delta), bar);
                        int width = panel.getWidth(), height = panel.getHeight();
                        var config = scale == 0 ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration() : null;
                        java.awt.image.VolatileImage actualScreen = scale == 0 ? config.createCompatibleVolatileImage(width, height) : null;
                        java.awt.image.VolatileImage expectedScreen = scale == 0 ? config.createCompatibleVolatileImage(width, height) : null;
                        var actualBuffer = scale == 0 ? null : new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
                        var expectedBuffer = scale == 0 ? null : new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
                        var a = scale == 0 ? actualScreen.createGraphics() : actualBuffer.createGraphics();
                        var e = scale == 0 ? expectedScreen.createGraphics() : expectedBuffer.createGraphics();
                        try {
                            if (scale != 0) { a.scale(scale, scale); e.scale(scale, scale); }
                            panel.paintComponent(a);
                            if (scale == 0 && i == 5) {
                                var cache = (TerminalViewportCache) cacheField.get(panel);
                                assertInstanceOf(java.awt.image.VolatileImage.class, surfaceField.get(cache));
                                assertTrue(cache.blitFrames() > 0);
                            }
                            original.invoke(panel, e);
                        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                        finally { a.dispose(); e.dispose(); }
                        var actual = scale == 0 ? actualScreen.getSnapshot() : actualBuffer;
                        var expected = scale == 0 ? expectedScreen.getSnapshot() : expectedBuffer;
                        assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                                actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()),
                                "frame=" + i + " scale=" + scale);
                        if (scale == 0) { actualScreen.flush(); expectedScreen.flush(); }
                    }
                } finally { panel.dispose(); }
            }
        }).get(90, TimeUnit.SECONDS);
    }
}
