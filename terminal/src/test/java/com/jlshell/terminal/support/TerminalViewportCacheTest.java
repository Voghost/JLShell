package com.jlshell.terminal.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalViewportCacheTest {
    @Test void acceleratedSurfaceUsesTheMonitorScaleOnlyOnce() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        SwingExecutors.runOnEdtAsync(() -> {
            var configuration = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            var expected = configuration.createCompatibleVolatileImage(120, 60, java.awt.Transparency.OPAQUE);
            var actual = configuration.createCompatibleVolatileImage(120, 60, java.awt.Transparency.OPAQUE);
            var cache = new TerminalViewportCache();
            try {
                for (int position : new int[]{0, 2, 5, -3}) {
                    expected.validate(configuration); actual.validate(configuration);
                    var e = expected.createGraphics(); var a = actual.createGraphics();
                    try {
                        paint(e, position, 0);
                        assertTrue(cache.paint(a, 120, 60, position, 0, g -> paint(g, position, 0)));
                    } finally { e.dispose(); a.dispose(); }
                    var reference = expected.getSnapshot(); var result = actual.getSnapshot();
                    assertArrayEquals(reference.getRGB(0, 0, 120, 60, null, 0, 120),
                            result.getRGB(0, 0, 120, 60, null, 0, 120));
                }
            } finally { cache.clear(); expected.flush(); actual.flush(); }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void releasesSurfaceAfterGestureStops() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<TerminalViewportCache>();
        var released = new java.util.concurrent.CompletableFuture<Void>();
        SwingExecutors.runOnEdtAsync(() -> {
            var cache = new TerminalViewportCache(); reference.set(cache);
            var graphics = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB).createGraphics();
            try { cache.paint(graphics, 120, 60, 0, 0, g -> paint(g, 0, 0)); }
            finally { graphics.dispose(); }
            assertTrue(cache.bytes() > 0);
            var check = new javax.swing.Timer(700, event -> {
                try { assertEquals(0, cache.bytes()); released.complete(null); }
                catch (Throwable error) { released.completeExceptionally(error); }
                finally { cache.clear(); }
            });
            check.setRepeats(false); check.start();
        }).get(10, TimeUnit.SECONDS);
        try { released.get(5, TimeUnit.SECONDS); }
        finally { SwingExecutors.runOnEdtAsync(() -> reference.get().clear()).get(5, TimeUnit.SECONDS); }
    }

    @Test void blitsBothDirectionsAtRetinaScaleAndInvalidatesContent() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            for (int scale : new int[]{1, 2}) {
                var cache = new TerminalViewportCache();
                try {
                    for (int[] frame : new int[][]{{0, 0}, {1, 0}, {4, 0}, {2, 0}, {-7, 0}, {-7, 1}, {80, 1}}) {
                        int position = frame[0], revision = frame[1];
                        var expected = new BufferedImage(120 * scale, 60 * scale, BufferedImage.TYPE_INT_RGB);
                        var actual = new BufferedImage(120 * scale, 60 * scale, BufferedImage.TYPE_INT_RGB);
                        Graphics2D reference = expected.createGraphics(), target = actual.createGraphics();
                        try {
                            reference.scale(scale, scale); target.scale(scale, scale);
                            paint(reference, position, revision);
                            assertTrue(cache.paint(target, 120, 60, position, revision,
                                    graphics -> paint(graphics, position, revision)));
                        } finally { reference.dispose(); target.dispose(); }
                        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++)
                            assertEquals(expected.getRGB(x, y), actual.getRGB(x, y),
                                    "position=" + position + " revision=" + revision + " scale=" + scale);
                    }
                    assertEquals(3, cache.fullFrames());
                    assertEquals(4, cache.blitFrames());
                    assertTrue(cache.bytes() <= TerminalViewportCache.MAX_BYTES);
                } finally { cache.clear(); }
                assertEquals(0, cache.bytes());
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void rejectsOversizedAndFractionalSurfaces() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var cache = new TerminalViewportCache();
            var target = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).createGraphics();
            try {
                assertFalse(cache.paint(target, 10000, 10000, 0, 0, g -> fail("oversized")));
                target.scale(1.5, 1.5);
                assertFalse(cache.paint(target, 10, 10, 0, 0, g -> fail("fractional")));
                assertEquals(0, cache.bytes());
            } finally { cache.clear(); target.dispose(); }
        }).get(10, TimeUnit.SECONDS);
    }

    private static void paint(Graphics2D graphics, int position, int revision) {
        graphics.translate(0, -position);
        // Exercise a renderer that resets its clip just like JediTerm.
        graphics.setClip(null);
        for (int y = position; y < position + 60; y++) {
            graphics.setColor(new Color((y * 531 + revision * 717) & 0xffffff));
            graphics.fillRect(0, y, 120, 1);
        }
    }
}
