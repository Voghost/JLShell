package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.event.MouseWheelEvent;

import static org.junit.jupiter.api.Assertions.*;

class TerminalWheelInputTest {
    @Test void macTrackpadRestoresCocoaPixelsAtEveryFontSizeIncludingIntegerAndMomentumEvents() {
        var input = new TerminalWheelInput(true);
        for (int height : new int[]{12, 17, 24, 36}) {
            for (int amount : new int[]{1, 3}) {
                for (double rotation : new double[]{-.01, -.1, -.2, -1, -2, .1, 1}) {
                    var motion = input.motion(event(0, amount, rotation), height, 40, 1);
                    assertEquals(rotation * 10, motion.rows() * height, .000001);
                    assertTrue(motion.precise(), "macOS already supplies device acceleration and momentum");
                    assertTrue(motion.direct(), "Native macOS touch input must not wait for an interpolation queue");
                }
            }
        }
    }

    @Test void jbrTouchEventsAlreadyContainPixelsAndMustNotBeMultipliedAgain() {
        for (boolean mac : new boolean[]{true, false}) {
            var motion = new TerminalWheelInput(mac).motion(event(3, 3, -.2), 20, 40, 1);
            assertEquals(-.6, motion.rows() * 20, .000001);
            assertTrue(motion.precise());
            assertTrue(motion.direct());
        }
    }

    @Test void otherPlatformsKeepWheelLinePreferencesAndFractionalSequences() {
        var input = new TerminalWheelInput(false);
        assertFalse(input.motion(event(0, 3, -1), 20, 40, 1).precise());
        var fine = input.motion(event(0, 3, -.2), 20, 40, 2);
        assertEquals(-.6, fine.rows(), .000001);
        assertTrue(fine.precise());
        assertFalse(fine.direct(), "Other precision-wheel sources keep their short interpolation");
        assertTrue(input.motion(event(0, 3, -1), 20, 40, 3).precise());
        assertFalse(input.motion(event(0, 3, -1), 20, 40, 200_000_000).precise());
        assertEquals(-40, input.motion(event(1, 1, -1), 20, 40, 400_000_000).rows());
        assertEquals(0, input.motion(event(0, 1, Double.NaN), 20, 40, 1).rows());
    }

    private static MouseWheelEvent event(int type, int amount, double rotation) {
        return new MouseWheelEvent(new Canvas(), MouseWheelEvent.MOUSE_WHEEL, 0, 0,
                10, 10, 10, 10, 0, false, type, amount, (int)Math.signum(rotation), rotation);
    }
}
