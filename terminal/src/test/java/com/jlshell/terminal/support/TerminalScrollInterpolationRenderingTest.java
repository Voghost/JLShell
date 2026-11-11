package com.jlshell.terminal.support;

import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jlshell.core.model.FontProfile;
import com.jlshell.terminal.model.TerminalColorScheme;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TerminalScrollInterpolationRenderingTest {
    @Test void macTouchpadRespondsInTheInputFrameAndDoesNotDriftAfterStopOrReversal() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            try (var fixture = new Fixture()) {
                var original = fixture.paint();
                fixture.wheel(0, -2); // Native motion: 20 logical pixels in this frame, without a 20ms delay.
                assertShift(original, fixture.paint(), 20);
                fixture.clock.addAndGet(7_000_000);
                assertShift(original, fixture.paint(), 20);
                fixture.wheel(0, .5); // Follow a reversal now, rather than catch up with an old target.
                assertShift(original, fixture.paint(), 15);
                fixture.wheel(4, 0);
                fixture.clock.addAndGet(100_000_000);
                assertShift(original, fixture.paint(), 15);
                fixture.wheel(3, -2); // JBR's pixel phase, including system momentum after touch-end.
                assertShift(original, fixture.paint(), 17);
            }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test void draggingThumbRendersIntermediatePositionsAndFinishesAfterRelease() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            try (var fixture = new Fixture()) {
                var original = fixture.paint();
                fixture.bar.setValueIsAdjusting(true);
                fixture.bar.setValue(-6);
                assertEquals(0, fixture.panel.getVerticalScrollModel().getValue(), "A drag target must not teleport the terminal");
                fixture.clock.addAndGet(7_500_000);
                assertShift(original, fixture.paint(), (int)-Math.round(-1.5 * fixture.panel.heightOfRow()));
                fixture.clock.addAndGet(7_500_000);
                assertShift(original, fixture.paint(), 3 * fixture.panel.heightOfRow());
                fixture.bar.setValueIsAdjusting(false);
                fixture.clock.addAndGet(15_000_000);
                assertShift(original, fixture.paint(), 6 * fixture.panel.heightOfRow());
                assertEquals(-6, fixture.panel.getVerticalScrollModel().getValue());
                fixture.bar.setValue(-2); // Search / programmatic navigation stays immediate.
                assertEquals(-2, fixture.panel.getVerticalScrollModel().getValue());
            }
        }).get(15, TimeUnit.SECONDS);
    }

    @Test void actualBasicScrollBarMouseDragUsesTheInterpolationPath() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            try (var fixture = new Fixture()) {
                var ui = new ThumbProbe(); fixture.bar.setUI(ui);
                fixture.bar.setSize(14, fixture.panel.getHeight()); fixture.bar.doLayout();
                Rectangle thumb = ui.thumb();
                assertTrue(thumb.height > 0);
                int x = thumb.x + Math.max(1, thumb.width / 2);
                int y = thumb.y + thumb.height / 2;
                fixture.bar.dispatchEvent(mouse(fixture.bar, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
                assertTrue(fixture.bar.getValueIsAdjusting());
                fixture.bar.dispatchEvent(mouse(fixture.bar, MouseEvent.MOUSE_DRAGGED, x, y - 40, MouseEvent.NOBUTTON));
                assertEquals(0, fixture.panel.getVerticalScrollModel().getValue());
                Rectangle draggedThumb = ui.thumb();
                fixture.clock.addAndGet(10_000_000); fixture.paint();
                assertEquals(draggedThumb, ui.thumb(), "Intermediate terminal frames must not pull the thumb away from the pointer");
                int intermediate = fixture.panel.getVerticalScrollModel().getValue();
                assertTrue(intermediate < 0);
                fixture.bar.dispatchEvent(mouse(fixture.bar, MouseEvent.MOUSE_RELEASED, x, y - 40, MouseEvent.BUTTON1));
                assertFalse(fixture.bar.getValueIsAdjusting());
                fixture.clock.addAndGet(20_000_000); fixture.paint();
                assertTrue(fixture.panel.getVerticalScrollModel().getValue() < intermediate,
                        "Release must finish the last segment, not snap back or freeze at its first frame");
            }
        }).get(15, TimeUnit.SECONDS);
    }

    private static MouseEvent mouse(java.awt.Component source, int id, int x, int y, int button) {
        return new MouseEvent(source, id, System.currentTimeMillis(),
                id == MouseEvent.MOUSE_RELEASED ? 0 : MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, button);
    }

    private static void assertShift(BufferedImage original, BufferedImage actual, int pixels) {
        for (int y = Math.max(1, pixels); y < original.getHeight() - 40; y++) {
            for (int x = 0; x < 250; x++) {
                assertEquals(original.getRGB(x, y - pixels), actual.getRGB(x, y),
                        "Expected an intermediate pixel viewport at " + pixels + " pixels, mismatch " + x + "," + y);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final Probe panel;
        final InterpolatingTerminalScrollBar bar;
        Fixture() {
            var settings = new JlshellSettingsProvider(new FontProfile("Monospaced", 16, false, 1), TerminalColorScheme.dark());
            var style = new StyleState(); style.setDefaultStyle(settings.defaultTextStyle());
            var buffer = new TerminalTextBuffer(60, 12, style);
            panel = new Probe(settings, buffer, style, clock);
            bar = new InterpolatingTerminalScrollBar(panel);
            bar.setModel(panel.getVerticalScrollModel());
            panel.init(bar); panel.setSize(600, panel.heightOfRow() * 12);
            var terminal = new JediTerminal(panel, buffer, style); panel.setCoordAccessor(terminal);
            for (int i = 0; i < 100; i++) {
                terminal.writeCharacters(String.format("row %03d INFO record %03d", i, i));
                terminal.carriageReturn(); terminal.newLine();
            }
            paint();
        }
        void wheel(int type, double rotation) {
            panel.handleMouseWheelEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, 0, 0,
                    10, 10, 10, 10, 0, false, type, 1, (int)Math.signum(rotation), rotation), bar);
        }
        BufferedImage paint() {
            var image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { panel.paintComponent(graphics); } finally { graphics.dispose(); }
            return image;
        }
        @Override public void close() { panel.dispose(); }
    }

    private static final class ThumbProbe extends TerminalScrollBarUI {
        Rectangle thumb() { return new Rectangle(getThumbBounds()); }
    }

    private static final class Probe extends RefreshableTerminalPanel {
        Probe(JlshellSettingsProvider settings, TerminalTextBuffer buffer, StyleState style, AtomicLong clock) {
            super(settings, buffer, style, settings, key -> key, new TerminalWheelInput(true), clock::get);
        }
        int heightOfRow() { return myCharSize.height; }
    }
}
