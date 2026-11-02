package com.jlshell.terminal.support;

import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollBar;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TerminalTrackpadScrollingTest {
    @Test void nativeOnePixelStepsAccumulateAndSettleAtExactPixels() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            try (var fixture = new Fixture(200)) {
                BufferedImage following = fixture.paint();
                for (int i = 0; i < 4; i++) fixture.wheel(-.01);
                assertRegion(following, fixture.paint(), 0, 1, 8 * fixture.panel.cellHeight());
                fixture.wheel(.04); // Subpixel motion accumulates but must not force a one-pixel jump.
                for (int i = 1; i <= 8; i++) {
                    fixture.wheel(-.1); // Stock macOS AWT: -0.1 rotation is one logical pixel.
                    BufferedImage shifted = fixture.paint();
                    assertEquals(-1, fixture.panel.getVerticalScrollModel().getValue());
                    assertRegion(following, shifted, -i, 1, 8 * fixture.panel.cellHeight());
                }
                // An integer event in the same native sequence is ten pixels, not a row or a new animation.
                fixture.wheel(-1);
                BufferedImage shifted = fixture.paint();
                assertRegion(following, shifted, -18, 1, 8 * fixture.panel.cellHeight());
                fixture.wheel(.1);
                assertRegion(following, fixture.paint(), -17, 1, 8 * fixture.panel.cellHeight());
            }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test void outputKeepsFractionAndContentAnchoredWithGrowingAndFullHistory() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            for (int capacity : new int[]{200, 40}) {
                try (var fixture = new Fixture(capacity)) {
                    fixture.panel.getVerticalScrollModel().setValue(-20);
                    fixture.wheel(-.1);
                    BufferedImage before = fixture.paint();
                    int origin = fixture.panel.getVerticalScrollModel().getValue();
                    // Paint immediately after output, before JediTerm's redraw timer drains scrollDy.
                    for (int i = 0; i < 5; i++) {
                        fixture.line(100 + i);
                        BufferedImage after = fixture.paint();
                        assertEquals(origin - i - 1, fixture.panel.getVerticalScrollModel().getValue(),
                                "The edge pass must restore the compensated origin, not the stale pre-paint origin");
                        assertRegion(before, after, 0, 1, 8 * fixture.panel.cellHeight());
                    }
                    // A second native wheel event may beat the 16ms JediTerm redraw timer.
                    fixture.line(106);
                    fixture.wheel(-.1);
                    BufferedImage interleaved = fixture.paint();
                    assertEquals(origin - 6, fixture.panel.getVerticalScrollModel().getValue());
                    assertRegion(before, interleaved, -1, 1, 8 * fixture.panel.cellHeight());
                    // A real scrollbar jump must still discard the fractional viewport.
                    fixture.panel.getVerticalScrollModel().setValue(-5);
                    BufferedImage manual = fixture.paint();
                    fixture.panel.getVerticalScrollModel().setValue(0);
                    fixture.paint();
                    fixture.panel.getVerticalScrollModel().setValue(-5);
                    assertRegion(manual, fixture.paint(), 0, 1, 8 * fixture.panel.cellHeight());
                }
            }
        }).get(15, TimeUnit.SECONDS);
    }

    private static void assertRegion(BufferedImage original, BufferedImage actual, int offset, int first, int last) {
        for (int y = Math.max(first, -offset); y < last; y++) {
            for (int x = 0; x < 250; x++) {
                assertEquals(original.getRGB(x, y + offset), actual.getRGB(x, y), "Pixel mismatch at " + x + "," + y);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Probe panel;
        final JScrollBar bar = new JScrollBar();
        final JediTerminal terminal;

        Fixture(int capacity) {
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 16, false, 1), TerminalColorScheme.dark());
            var style = new StyleState();
            style.setDefaultStyle(settings.defaultTextStyle());
            var buffer = new TerminalTextBuffer(60, 12, style, capacity);
            panel = new Probe(settings, buffer, style);
            panel.init(bar);
            panel.setSize(600, panel.cellHeight() * 12);
            terminal = new JediTerminal(panel, buffer, style);
            panel.setCoordAccessor(terminal);
            for (int i = 0; i < 100; i++) line(i);
            paint();
        }

        void line(int i) {
            terminal.writeCharacters(String.format("row %03d INFO record %03d", i, i));
            terminal.carriageReturn();
            terminal.newLine();
        }

        void wheel(double rotation) {
            panel.handleMouseWheelEvent(new MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 10, 10, 10, 10, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int)Math.signum(rotation), rotation), bar);
        }

        BufferedImage paint() {
            var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { panel.paintComponent(graphics); } finally { graphics.dispose(); }
            return image;
        }

        @Override public void close() { panel.dispose(); }
    }

    private static final class Probe extends RefreshableTerminalPanel {
        private final java.util.concurrent.atomic.AtomicLong clock;
        Probe(JlshellSettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
            this(settings, buffer, style, new java.util.concurrent.atomic.AtomicLong(1_000_000_000L));
        }
        private Probe(JlshellSettingsProvider settings, TerminalTextBuffer buffer, StyleState style,
                java.util.concurrent.atomic.AtomicLong clock) {
            super(settings, buffer, style, settings, key -> key, new TerminalWheelInput(true), clock::get);
            this.clock = clock;
        }
        int cellHeight() { return myCharSize.height; }
    }
}
