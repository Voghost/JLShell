package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalScrollAnimationTest {
    @Test void easesRetargetsReversesAndSettlesAtBounds() {
        var scroll = new TerminalScrollAnimation();
        scroll.wheel(-3, false, 0, -100, 0, 0);
        assertEquals(0, scroll.position()); assertTrue(scroll.animating());
        scroll.advance(70_000_000L);
        assertEquals(-2.625, scroll.position(), .0001);
        scroll.wheel(-3, false, -3, -100, 0, 70_000_000L);
        scroll.advance(210_000_000L); assertEquals(-6, scroll.position());
        assertFalse(scroll.animating());
        scroll.wheel(-3, false, -6, -100, 0, 220_000_000L);
        scroll.advance(240_000_000L); double before = scroll.position();
        scroll.wheel(3, false, -7, -100, 0, 240_000_000L);
        scroll.advance(400_000_000L); assertEquals(before+3,scroll.position(), .6);
        scroll.wheel(100, false, -4, -100, 0, 410_000_000L);
        scroll.advance(600_000_000L); assertEquals(0,scroll.position());
    }
    @Test void preservesTinyTrackpadMotionAndStopsWhenUserTakesOver() {
        var scroll = new TerminalScrollAnimation();
        for(int i=0;i<10;i++) scroll.wheel(-.03, true, 0, -100, 0, i);
        scroll.advance(TerminalScrollAnimation.PRECISE_DELAY_NANOS + 9);
        assertEquals(-.3,scroll.position(), .0001); assertFalse(scroll.animating());
        assertTrue(scroll.active());
        scroll.cancel(-10); scroll.advance(1_000_000_000L);
        assertEquals(-10,scroll.position()); assertFalse(scroll.active());
        scroll.wheel(-1000, true, -10, -50, 0, 0);
        scroll.advance(TerminalScrollAnimation.PRECISE_DELAY_NANOS); assertEquals(-50,scroll.position());
    }
    @Test void historyCompensationPreservesFractionAndAnimationClock() {
        var scroll = new TerminalScrollAnimation();
        scroll.wheel(-.25, true, -20, -100, 0, 0);
        scroll.advance(TerminalScrollAnimation.PRECISE_DELAY_NANOS);
        scroll.shift(-3, -103, 0);
        assertEquals(-23.25, scroll.position());
        scroll.wheel(-3, false, -24, -103, 0, 0);
        scroll.advance(70_000_000L);
        double half = scroll.position();
        scroll.shift(-2, -105, 0);
        assertEquals(half - 2, scroll.position());
        scroll.advance(140_000_000L);
        assertEquals(-28, scroll.position());
        assertFalse(scroll.animating());
    }

    @Test void preciseEventsFillTheTimeBetweenSamplesInsteadOfJumpingToEachEndpoint() {
        var scroll = new TerminalScrollAnimation();
        scroll.wheel(-3, true, 0, -100, 0, 0);
        assertEquals(0, scroll.position(), "Input chooses a target; it must not teleport the viewport");
        scroll.advance(7_000_000);
        assertEquals(-1.05, scroll.position(), .000001);
        scroll.advance(14_000_000);
        assertEquals(-2.1, scroll.position(), .000001);
        scroll.advance(20_000_000);
        assertEquals(-3, scroll.position());
        assertFalse(scroll.animating());
    }

    @Test void continuousInputHasBoundedLatencyAndReleaseDoesNotLoseTheFinalTarget() {
        var scroll = new TerminalScrollAnimation();
        for (int i = 0; i < 1000; i++) {
            scroll.wheel(-.1, true, 0, -1000, 0, i * 8_000_000L);
        }
        long lastInput = 999 * 8_000_000L;
        scroll.advance(lastInput + TerminalScrollAnimation.PRECISE_DELAY_NANOS);
        assertEquals(-100, scroll.position(), .000001);
        assertFalse(scroll.animating(), "A long gesture must not build a long animation backlog");
    }

    @Test void thumbDraggingUsesShortInterpolationAndReversesFromTheVisiblePosition() {
        var scroll = new TerminalScrollAnimation();
        scroll.dragTo(-60, 0, -100, 0, 0);
        assertEquals(0, scroll.position());
        scroll.advance(10_000_000);
        assertEquals(-20, scroll.position());
        scroll.dragTo(-5, -20, -100, 0, 10_000_000);
        assertEquals(-20, scroll.position(), "Changing direction must not snap to the old target");
        scroll.advance(25_000_000);
        assertEquals(-12.5, scroll.position());
        scroll.advance(40_000_000);
        assertEquals(-5, scroll.position());
        assertFalse(scroll.animating());
    }

    @Test void historyCompensationTranslatesPendingInterpolationWithoutRestartingIt() {
        var scroll = new TerminalScrollAnimation();
        scroll.wheel(-2, true, -20, -100, 0, 0);
        scroll.advance(5_000_000);
        assertEquals(-20.5, scroll.position());
        scroll.shift(-3, -103, 0);
        scroll.advance(10_000_000);
        assertEquals(-24, scroll.position());
        scroll.advance(20_000_000);
        assertEquals(-25, scroll.position());
    }

    @Test void nativeTouchMotionIsImmediateAndCancelsPendingTargetsWithoutAResidualTail() {
        var scroll = new TerminalScrollAnimation();
        for (int i = 0; i < 10; i++) {
            scroll.directWheel(-.03, 0, -100, 0, i);
            assertEquals(-.03 * (i + 1), scroll.position(), .000001);
            assertFalse(scroll.animating());
        }
        scroll.directWheel(.1, -1, -100, 0, 10);
        assertEquals(-.2, scroll.position(), .000001);
        scroll.advance(1_000_000_000);
        assertEquals(-.2, scroll.position(), .000001);

        scroll.cancel(0);
        scroll.dragTo(-60, 0, -100, 0, 0);
        scroll.advance(10_000_000);
        assertEquals(-20, scroll.position());
        scroll.directWheel(.25, -20, -100, 0, 10_000_000);
        assertEquals(-19.75, scroll.position());
        assertFalse(scroll.animating());
        scroll.advance(100_000_000);
        assertEquals(-19.75, scroll.position(), "A previous drag must not resume after native touch takes over");
    }
}
